package ao.creativemode.kixi.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import ao.creativemode.kixi.client.OcrUploadedFile;
import ao.creativemode.kixi.dto.ocr.OcrResponse;
import ao.creativemode.kixi.model.Question;
import ao.creativemode.kixi.model.QuestionImage;
import ao.creativemode.kixi.repository.QuestionImageRepository;
import ao.creativemode.kixi.service.storage.ImageStorage;
import ao.creativemode.kixi.service.storage.StoredObject;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Materializes OCR image regions after the questions have been persisted.
 * Only raster source uploads are eligible: PDF page rendering remains owned by
 * the OCR service and cannot be safely reconstructed from the original bytes.
 */
@Service
public class OcrImageAssociationService {

    private static final int REGION_CONTRACT_VERSION = 1;
    private static final int MAX_CROP_BYTES = 10 * 1024 * 1024;

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

        List<RegionCandidate> candidates = regions.stream()
                .filter(region -> region != null && region.isQuestionImage())
                .map(region -> candidate(region, questionsByNumber, sourceFiles))
                .flatMap(Optional::stream)
                .toList();

        if (candidates.isEmpty()) {
            return Mono.just(List.of());
        }

        List<StoredObject> createdObjects = new ArrayList<>();
        return Flux.fromIterable(candidates)
                .concatMap(candidate -> storage.put(
                                candidate.key(),
                                MediaType.IMAGE_PNG,
                                candidate.pngBytes())
                        .doOnNext(createdObjects::add)
                        .flatMap(stored -> saveEntity(candidate, stored)))
                .collectList()
                .onErrorResume(error -> cleanup(createdObjects).then(Mono.error(error)));
    }

    private Optional<RegionCandidate> candidate(
            OcrResponse.ImageToUpload region,
            Map<Integer, Question> questionsByNumber,
            List<OcrUploadedFile> sourceFiles) {
        Integer questionNumber = parseQuestionNumber(region.getQuestionNumber());
        if (questionNumber == null || region.contractVersion() == null
                || region.contractVersion() != REGION_CONTRACT_VERSION
                || region.bbox() == null || region.bbox().size() != 4
                || sourceFiles == null || region.sourceFileIndex() == null
                || region.sourceFileIndex() < 0
                || region.sourceFileIndex() >= sourceFiles.size()
                || !isRaster(sourceFiles.get(region.sourceFileIndex()))) {
            return Optional.empty();
        }

        Question question = questionsByNumber.get(questionNumber);
        if (question == null) {
            return Optional.empty();
        }

        byte[] png = cropToPng(sourceFiles.get(region.sourceFileIndex()), region);
        if (png == null || png.length == 0 || png.length > MAX_CROP_BYTES) {
            return Optional.empty();
        }

        return Optional.of(new RegionCandidate(
                question,
                region.description(),
                "questions/" + question.getId() + "/ocr-" + java.util.UUID.randomUUID() + ".png",
                png));
    }

    private Mono<QuestionImage> saveEntity(RegionCandidate candidate, StoredObject stored) {
        QuestionImage entity = new QuestionImage();
        entity.setQuestionId(candidate.question().getId());
        entity.setImageUrl(stored.publicUrl());
        entity.setStorageKey(stored.key());
        entity.setCaption(candidate.description());
        entity.setOrderIndex(0);

        return repository.save(entity)
                .onErrorResume(error -> storage.delete(stored.key()).then(Mono.error(error)));
    }

    private Mono<Void> cleanup(List<StoredObject> objects) {
        return Flux.fromIterable(objects)
                .concatMap(object -> storage.delete(object.key()).onErrorResume(ignored -> Mono.empty()))
                .then();
    }

    private byte[] cropToPng(OcrUploadedFile source, OcrResponse.ImageToUpload region) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source.content()));
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
            return ImageIO.read(new ByteArrayInputStream(file.content())) != null;
        } catch (IOException | RuntimeException error) {
            return false;
        }
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
            String key,
            byte[] pngBytes) {
    }
}
