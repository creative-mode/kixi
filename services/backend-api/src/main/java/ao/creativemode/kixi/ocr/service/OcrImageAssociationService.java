package ao.creativemode.kixi.ocr.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import ao.creativemode.kixi.ocr.client.OcrUploadedFile;
import ao.creativemode.kixi.ocr.dto.OcrResponse;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionImage;
import ao.creativemode.kixi.exams.repository.QuestionImageRepository;
import ao.creativemode.kixi.shared.storage.ImageStorage;
import ao.creativemode.kixi.shared.storage.StoredObject;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Materializes OCR image regions after the questions have been persisted.
 */
@Service
public class OcrImageAssociationService {

    private static final int REGION_CONTRACT_VERSION = 1;
    private static final int MAX_CROP_BYTES = 10 * 1024 * 1024;
    private static final int MAX_PREVIEW_BYTES = 1536 * 1024;
    private static final int MAX_PREVIEW_TOTAL_BYTES = 8 * 1024 * 1024;
    private static final int PDF_RENDER_DPI = 300;
    private static final int MAX_PDF_SOURCE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_PDF_PAGES = 50;
    private static final int MAX_PDF_PAGE_DIMENSION = 8_000;
    private static final long MAX_PDF_PAGE_PIXELS = 20_000_000L;
    private static final long MAX_CACHED_PDF_PAGE_PIXELS = 40_000_000L;
    private static final long MAX_PDF_CROP_TOTAL_BYTES = 50L * 1024 * 1024;
    private static final long PDF_MAIN_MEMORY_BYTES = 16L * 1024 * 1024;
    private static final long PDF_STORAGE_BYTES = 128L * 1024 * 1024;

    private final QuestionImageRepository repository;
    private final ImageStorage storage;

    public OcrImageAssociationService(
            QuestionImageRepository repository,
            ImageStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    public Mono<List<QuestionImage>> persistQuestionImages(
            List<Question> questions,
            List<OcrResponse.ImageToUpload> regions,
            List<OcrUploadedFile> sourceFiles) {
        if (questions == null || questions.isEmpty() || regions == null || regions.isEmpty()) {
            return Mono.just(List.of());
        }

        Map<Integer, Question> questionsByNumber = questions.stream()
                .filter(question -> question.getNumber() != null)
                .collect(Collectors.toMap(
                        Question::getNumber,
                        Function.identity(),
                        (first, ignored) -> first));

        List<RegionCandidate> candidates;
        try (CropContext cropContext = new CropContext(sourceFiles)) {
            candidates = regions.stream()
                    .filter(region -> region != null && region.isQuestionImage())
                    .map(region -> candidate(region, questionsByNumber, cropContext))
                    .flatMap(Optional::stream)
                    .toList();
        }

        if (candidates.isEmpty()) {
            return Mono.just(List.of());
        }

        List<StoredObject> createdObjects = new ArrayList<>();
        // A question with several figures keeps them in the order the page has them.
        Map<Long, Integer> nextOrder = new java.util.HashMap<>();
        return Flux.fromIterable(candidates)
                .concatMap(candidate -> storage.put(
                                candidate.key(),
                                MediaType.IMAGE_PNG,
                                candidate.pngBytes())
                        .doOnNext(createdObjects::add)
                        .flatMap(stored -> saveEntity(
                                candidate, stored,
                                nextOrder.merge(candidate.question().getId(), 1, Integer::sum) - 1)))
                .collectList()
                .onErrorResume(error -> cleanup(createdObjects).then(Mono.error(error)));
    }

    private Optional<RegionCandidate> candidate(
            OcrResponse.ImageToUpload region,
            Map<Integer, Question> questionsByNumber,
            CropContext cropContext) {
        Integer questionNumber = parseQuestionNumber(region.getQuestionNumber());
        if (questionNumber == null || region.contractVersion() == null
                || region.contractVersion() != REGION_CONTRACT_VERSION
                || region.bbox() == null || region.bbox().size() != 4
                || (region.pageIndex() != null && region.pageIndex() < 0)
                || (region.score() != null && (!Double.isFinite(region.score())
                        || region.score() < 0.0 || region.score() > 1.0))
                || region.sourceFileIndex() == null || !cropContext.hasSource(region.sourceFileIndex())) {
            return Optional.empty();
        }

        Question question = questionsByNumber.get(questionNumber);
        if (question == null) {
            return Optional.empty();
        }

        byte[] png = cropToPng(region, cropContext);
        if (png == null || png.length == 0 || png.length > MAX_CROP_BYTES
                || !cropContext.reservePdfCrop(region.sourceFileIndex(), png.length)) {
            return Optional.empty();
        }

        return Optional.of(new RegionCandidate(
                question,
                region.description(),
                region.kind(),
                region.pageIndex(),
                region.score(),
                "questions/" + question.getId() + "/ocr-" + java.util.UUID.randomUUID() + ".png",
                png));
    }

    /**
     * The crop of each region as a PNG data URL, by position (null where there is none), so the exam builder
     * can show question figures and the source footer before anything is saved. Header regions get none;
     * a crop over {@link #MAX_PREVIEW_BYTES}, or past {@link #MAX_PREVIEW_TOTAL_BYTES} in all, is left out.
     */
    public List<String> previewCrops(List<OcrResponse.ImageToUpload> regions, List<OcrUploadedFile> sourceFiles) {
        List<String> crops = new ArrayList<>();
        if (regions == null) {
            return crops;
        }
        long total = 0;
        try (CropContext cropContext = new CropContext(sourceFiles)) {
            for (OcrResponse.ImageToUpload region : regions) {
                String dataUrl = null;
                Integer index = region == null ? null : region.sourceFileIndex();
                if (region != null && (region.isQuestionImage() || region.isFooter()) && region.bbox() != null
                        && region.bbox().size() == 4 && index != null && cropContext.hasSource(index)) {
                    byte[] png = cropToPng(region, cropContext);
                    if (png != null && png.length <= MAX_PREVIEW_BYTES
                            && total + png.length <= MAX_PREVIEW_TOTAL_BYTES) {
                        total += png.length;
                        dataUrl = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png);
                    }
                }
                crops.add(dataUrl);
            }
        }
        return crops;
    }

    private Mono<QuestionImage> saveEntity(RegionCandidate candidate, StoredObject stored, int orderIndex) {
        QuestionImage entity = new QuestionImage();
        entity.setQuestionId(candidate.question().getId());
        entity.setImageUrl(stored.publicUrl());
        entity.setStorageKey(stored.key());
        entity.setCaption(candidate.description());
        entity.setOcrKind(candidate.ocrKind());
        entity.setOcrPageIndex(candidate.ocrPageIndex());
        entity.setOcrScore(candidate.ocrScore());
        entity.setOrderIndex(orderIndex);

        return repository.save(entity)
                .onErrorResume(error -> storage.delete(stored.key()).then(Mono.error(error)));
    }

    private Mono<Void> cleanup(List<StoredObject> objects) {
        return Flux.fromIterable(objects)
                .concatMap(object -> storage.delete(object.key()).onErrorResume(ignored -> Mono.empty()))
                .then();
    }

    private byte[] cropToPng(OcrResponse.ImageToUpload region, CropContext cropContext) {
        try {
            BufferedImage image = cropContext.image(region);
            if (image == null) {
                return null;
            }

            int sourceWidth = region.sourceWidth() == null ? image.getWidth() : region.sourceWidth();
            int sourceHeight = region.sourceHeight() == null ? image.getHeight() : region.sourceHeight();
            if (sourceWidth < 1 || sourceHeight < 1) {
                return null;
            }

            double scaleX = image.getWidth() / (double) sourceWidth;
            double scaleY = image.getHeight() / (double) sourceHeight;
            List<Integer> bbox = region.bbox();
            int x1 = clamp((int) Math.floor(bbox.get(0) * scaleX), 0, image.getWidth() - 1);
            int y1 = clamp((int) Math.floor(bbox.get(1) * scaleY), 0, image.getHeight() - 1);
            int x2 = clamp((int) Math.ceil(bbox.get(2) * scaleX), x1 + 1, image.getWidth());
            int y2 = clamp((int) Math.ceil(bbox.get(3) * scaleY), y1 + 1, image.getHeight());

            BufferedImage cropped = image.getSubimage(x1, y1, x2 - x1, y2 - y1);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(cropped, "png", output)) {
                return null;
            }
            return output.toByteArray();
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private boolean isRaster(OcrUploadedFile file) {
        try {
            OcrUploadBuffer.validateContent(file.content());
            return ImageIO.read(new ByteArrayInputStream(file.content())) != null;
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    private boolean isPdf(OcrUploadedFile file) {
        byte[] content = file.content();
        return content.length >= 5
                && content[0] == '%'
                && content[1] == 'P'
                && content[2] == 'D'
                && content[3] == 'F'
                && content[4] == '-';
    }

    private final class CropContext implements AutoCloseable {
        private final List<OcrUploadedFile> sourceFiles;
        private final Map<Integer, Boolean> pdfSources = new HashMap<>();
        private final Map<Integer, Boolean> rasterSources = new HashMap<>();
        private final Map<Integer, Integer> sourcePageOffsets = new HashMap<>();
        private PDDocument activePdfDocument;
        private Integer activePdfSourceIndex;
        private final Map<PageKey, BufferedImage> renderedPages = new HashMap<>();
        private final Set<PageKey> rejectedPages = new java.util.HashSet<>();
        private long cachedPixelCount;
        private long retainedPdfCropBytes;

        private CropContext(List<OcrUploadedFile> sourceFiles) {
            this.sourceFiles = sourceFiles == null ? List.of() : sourceFiles;
        }

        private boolean hasSource(int sourceFileIndex) {
            return sourceFileIndex >= 0 && sourceFileIndex < sourceFiles.size();
        }

        private boolean reservePdfCrop(int sourceFileIndex, int cropBytes) {
            OcrUploadedFile source = sourceFiles.get(sourceFileIndex);
            if (!pdfSources.computeIfAbsent(sourceFileIndex, ignored -> isPdf(source))) {
                return true;
            }
            if (retainedPdfCropBytes + cropBytes > MAX_PDF_CROP_TOTAL_BYTES) {
                return false;
            }
            retainedPdfCropBytes += cropBytes;
            return true;
        }

        private BufferedImage image(OcrResponse.ImageToUpload region) {
            Integer sourceIndex = region.sourceFileIndex();
            if (sourceIndex == null || !hasSource(sourceIndex)) {
                return null;
            }

            OcrUploadedFile source = sourceFiles.get(sourceIndex);
            if (pdfSources.computeIfAbsent(sourceIndex, ignored -> isPdf(source))) {
                return renderedPage(sourceIndex, region.pageIndex());
            }
            if (!rasterSources.computeIfAbsent(sourceIndex, ignored -> isRaster(source))) {
                return null;
            }
            try {
                return ImageIO.read(new ByteArrayInputStream(source.content()));
            } catch (IOException | RuntimeException error) {
                return null;
            }
        }

        private BufferedImage renderedPage(int sourceIndex, Integer pageIndex) {
            if (pageIndex == null || pageIndex < 0) {
                return null;
            }
            int pageOffset = sourcePageOffset(sourceIndex);
            if (pageOffset < 0 || pageIndex < pageOffset) {
                return null;
            }
            int sourcePageIndex = pageIndex - pageOffset;
            PageKey key = new PageKey(sourceIndex, sourcePageIndex);
            BufferedImage cached = renderedPages.get(key);
            if (cached != null) {
                return cached;
            }
            if (rejectedPages.contains(key)) {
                return null;
            }

            try {
                PDDocument document = activePdfDocument;
                if (document == null || activePdfSourceIndex == null || activePdfSourceIndex != sourceIndex) {
                    closeActivePdf();
                    OcrUploadedFile source = sourceFiles.get(sourceIndex);
                    if (source.content().length > MAX_PDF_SOURCE_BYTES) {
                        rejectedPages.add(key);
                        return null;
                    }
                    try (ByteArrayInputStream input = new ByteArrayInputStream(source.content())) {
                        document = PDDocument.load(
                                input,
                                MemoryUsageSetting.setupMixed(PDF_MAIN_MEMORY_BYTES, PDF_STORAGE_BYTES));
                    }
                    if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                        document.close();
                        rejectedPages.add(key);
                        return null;
                    }
                    activePdfDocument = document;
                    activePdfSourceIndex = sourceIndex;
                }

                if (sourcePageIndex >= document.getNumberOfPages()) {
                    rejectedPages.add(key);
                    return null;
                }

                PDPage page = document.getPage(sourcePageIndex);
                if (!isPageWithinRenderLimits(page)) {
                    rejectedPages.add(key);
                    return null;
                }

                PDFRenderer renderer = new PDFRenderer(document);
                renderer.setSubsamplingAllowed(true);
                BufferedImage rendered = renderer.renderImageWithDPI(sourcePageIndex, PDF_RENDER_DPI, ImageType.RGB);
                if (rendered.getWidth() > MAX_PDF_PAGE_DIMENSION
                        || rendered.getHeight() > MAX_PDF_PAGE_DIMENSION
                        || (long) rendered.getWidth() * rendered.getHeight() > MAX_PDF_PAGE_PIXELS) {
                    rejectedPages.add(key);
                    return null;
                }
                long pagePixels = (long) rendered.getWidth() * rendered.getHeight();
                if (cachedPixelCount + pagePixels <= MAX_CACHED_PDF_PAGE_PIXELS) {
                    renderedPages.put(key, rendered);
                    cachedPixelCount += pagePixels;
                }
                return rendered;
            } catch (IOException | RuntimeException error) {
                rejectedPages.add(key);
                return null;
            }
        }

        /** OCR page indexes are global across uploaded files; PDFBox indexes restart per source PDF. */
        private int sourcePageOffset(int sourceIndex) {
            if (sourceIndex < 0 || sourceIndex >= sourceFiles.size()) return -1;
            Integer cached = sourcePageOffsets.get(sourceIndex);
            if (cached != null) return cached;

            int offset = 0;
            for (int index = 0; index < sourceIndex; index++) {
                OcrUploadedFile source = sourceFiles.get(index);
                int count;
                if (pdfSources.computeIfAbsent(index, ignored -> isPdf(source))) {
                    count = pdfPageCount(source);
                } else {
                    count = rasterSources.computeIfAbsent(index, ignored -> isRaster(source)) ? 1 : 0;
                }
                if (count < 0) {
                    sourcePageOffsets.put(sourceIndex, -1);
                    return -1;
                }
                offset = Math.addExact(offset, count);
            }
            sourcePageOffsets.put(sourceIndex, offset);
            return offset;
        }

        private int pdfPageCount(OcrUploadedFile source) {
            if (source.content().length > MAX_PDF_SOURCE_BYTES) return -1;
            try (ByteArrayInputStream input = new ByteArrayInputStream(source.content());
                    PDDocument document = PDDocument.load(
                            input, MemoryUsageSetting.setupMixed(PDF_MAIN_MEMORY_BYTES, PDF_STORAGE_BYTES))) {
                return document.getNumberOfPages() <= MAX_PDF_PAGES ? document.getNumberOfPages() : -1;
            } catch (IOException | RuntimeException error) {
                return -1;
            }
        }

        private boolean isPageWithinRenderLimits(PDPage page) {
            PDRectangle cropBox = page.getCropBox();
            double userUnit = page.getUserUnit();
            double widthPoints = cropBox.getWidth() * userUnit;
            double heightPoints = cropBox.getHeight() * userUnit;
            int rotation = Math.floorMod(page.getRotation(), 360);
            if (rotation == 90 || rotation == 270) {
                double swap = widthPoints;
                widthPoints = heightPoints;
                heightPoints = swap;
            } else if (rotation != 0 && rotation != 180) {
                return false;
            }

            double widthPixels = Math.ceil(widthPoints * PDF_RENDER_DPI / 72.0);
            double heightPixels = Math.ceil(heightPoints * PDF_RENDER_DPI / 72.0);
            return Double.isFinite(widthPixels)
                    && Double.isFinite(heightPixels)
                    && widthPixels >= 1
                    && heightPixels >= 1
                    && widthPixels <= MAX_PDF_PAGE_DIMENSION
                    && heightPixels <= MAX_PDF_PAGE_DIMENSION
                    && widthPixels * heightPixels <= MAX_PDF_PAGE_PIXELS;
        }

        @Override
        public void close() {
            closeActivePdf();
            renderedPages.clear();
            cachedPixelCount = 0;
        }

        private void closeActivePdf() {
            if (activePdfDocument == null) {
                return;
            }
            try {
                activePdfDocument.close();
            } catch (IOException ignored) {
                // Cleanup must not mask crop or persistence results.
            }
            activePdfDocument = null;
            activePdfSourceIndex = null;
        }
    }

    private record PageKey(int sourceFileIndex, int pageIndex) {
    }

    private Integer parseQuestionNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String digits = value.replaceFirst("^(\\d+).*$", "$1");
        try {
            return digits.equals(value) || digits.matches("\\d+")
                    ? Integer.valueOf(digits)
                    : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private record RegionCandidate(
            Question question,
            String description,
            String ocrKind,
            Integer ocrPageIndex,
            Double ocrScore,
            String key,
            byte[] pngBytes) {
    }
}
