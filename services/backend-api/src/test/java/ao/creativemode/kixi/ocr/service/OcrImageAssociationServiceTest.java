package ao.creativemode.kixi.ocr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import ao.creativemode.kixi.ocr.client.OcrUploadedFile;
import ao.creativemode.kixi.ocr.dto.OcrResponse;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionImage;
import ao.creativemode.kixi.exams.repository.QuestionImageRepository;
import ao.creativemode.kixi.shared.storage.ImageStorage;
import ao.creativemode.kixi.shared.storage.StoredObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OcrImageAssociationServiceTest {

    private QuestionImageRepository repository;
    private ImageStorage storage;
    private OcrImageAssociationService service;

    @BeforeEach
    void setUp() {
        repository = mock(QuestionImageRepository.class);
        storage = mock(ImageStorage.class);
        service = new OcrImageAssociationService(repository, storage);
    }

    @Test
    void cropsRasterQuestionRegionAndAssociatesItWithPersistedQuestion() {
        Question question = new Question();
        question.setId(7L);
        question.setNumber(3);

        OcrResponse.ImageToUpload region = new OcrResponse.ImageToUpload(
            "questao-3.png",
            "Figura da questão 3",
            "questao_3",
            0,
            List.of(2, 2, 8, 8),
            10,
            10,
            1,
            0,
            "formula",
            0.93
        );
        OcrUploadedFile source = new OcrUploadedFile(
            "exam.png",
            MediaType.IMAGE_PNG,
            pngBytes(10, 10)
        );
        StoredObject stored = new StoredObject(
            "questions/7/ocr-image.png",
            "/uploads/questions/7/ocr-image.png"
        );
        AtomicReference<QuestionImage> persisted = new AtomicReference<>();
        when(storage.put(anyString(), any(), any())).thenReturn(Mono.just(stored));
        when(repository.save(any(QuestionImage.class))).thenAnswer(invocation -> {
            QuestionImage image = invocation.getArgument(0);
            persisted.set(image);
            return Mono.just(image);
        });

        StepVerifier.create(service.persistQuestionImages(
                List.of(question), List.of(region), List.of(source)))
            .assertNext(images -> assertThat(images).hasSize(1))
            .verifyComplete();

        assertThat(persisted.get().getQuestionId()).isEqualTo(7L);
        assertThat(persisted.get().getStorageKey()).isEqualTo(stored.key());
        assertThat(persisted.get().getCaption()).isEqualTo("Figura da questão 3");
        assertThat(persisted.get().getOcrKind()).isEqualTo("formula");
        assertThat(persisted.get().getOcrPageIndex()).isZero();
        assertThat(persisted.get().getOcrScore()).isEqualTo(0.93);
        verify(storage).put(anyString(), any(), any(byte[].class));
    }

    @Test
    void severalFiguresOfOneQuestionKeepThePageOrder() {
        Question question = new Question();
        question.setId(9L);
        question.setNumber(2);
        List<OcrResponse.ImageToUpload> regions = List.of(
            new OcrResponse.ImageToUpload("a.png", "Figura 1", "questao_2", 0, List.of(0, 0, 5, 5), 10, 10, 1, 0, "figure", 0.9),
            new OcrResponse.ImageToUpload("b.png", "Tabela", "questao_2", 0, List.of(5, 5, 10, 10), 10, 10, 1, 0, "table", 0.8));
        OcrUploadedFile source = new OcrUploadedFile("exam.png", MediaType.IMAGE_PNG, pngBytes(10, 10));
        java.util.List<QuestionImage> saved = new java.util.ArrayList<>();
        when(storage.put(anyString(), any(), any())).thenAnswer(invocation ->
            Mono.just(new StoredObject(invocation.getArgument(0), "/uploads/" + invocation.getArgument(0))));
        when(repository.save(any(QuestionImage.class))).thenAnswer(invocation -> {
            QuestionImage image = invocation.getArgument(0);
            saved.add(image);
            return Mono.just(image);
        });

        StepVerifier.create(service.persistQuestionImages(List.of(question), regions, List.of(source)))
            .assertNext(images -> assertThat(images).hasSize(2))
            .verifyComplete();

        assertThat(saved).extracting(QuestionImage::getOrderIndex).containsExactly(0, 1);
        assertThat(saved).extracting(QuestionImage::getCaption).containsExactly("Figura 1", "Tabela");
        assertThat(saved).extracting(QuestionImage::getOcrKind).containsExactly("figure", "table");
        assertThat(saved).extracting(QuestionImage::getOcrPageIndex).containsExactly(0, 0);
        assertThat(saved).extracting(QuestionImage::getOcrScore).containsExactly(0.9, 0.8);
    }

    @Test
    void previewCropsGiveTheBuilderDataUrlsForQuestionsAndFooterButNotHeader() {
        List<OcrResponse.ImageToUpload> regions = List.of(
            new OcrResponse.ImageToUpload("h.png", "Cabeçalho", "cabecalho", 0, List.of(0, 0, 10, 4), 10, 10, 1, 0),
            new OcrResponse.ImageToUpload("q.png", "Figura", "questao_1", 0, List.of(2, 2, 8, 8), 10, 10, 1, 0, "figure", 0.9),
            new OcrResponse.ImageToUpload("p.png", "Figura de um PDF", "questao_1", 0, List.of(2, 2, 8, 8), 10, 10, 1, 1, "figure", 0.9),
            new OcrResponse.ImageToUpload("f.png", "Rodapé original", "rodape", 0, List.of(0, 5, 10, 10), 10, 10, 1, 0));
        List<OcrUploadedFile> files = List.of(
            new OcrUploadedFile("exam.png", MediaType.IMAGE_PNG, pngBytes(10, 10)),
            new OcrUploadedFile("exam.pdf", MediaType.APPLICATION_PDF, "%PDF-1.4".getBytes()));

        List<String> crops = service.previewCrops(regions, files);

        assertThat(crops).hasSize(4);
        assertThat(crops.get(0)).isNull();
        assertThat(crops.get(1)).startsWith("data:image/png;base64,");
        assertThat(crops.get(2)).isNull();
        assertThat(crops.get(3)).startsWith("data:image/png;base64,");
    }

    @Test
    void previewCropsTheGlobalOcrPageFromTheIndexedMultiPagePdf() throws IOException {
        OcrUploadedFile unrelatedRaster = new OcrUploadedFile(
                "unrelated.png", MediaType.IMAGE_PNG, pngBytes(10, 10));
        OcrUploadedFile sourcePdf = new OcrUploadedFile(
                "exam.pdf", MediaType.APPLICATION_PDF, pdfBytes(
                        new Color[] {Color.RED, Color.BLUE}, 72, 72));
        OcrResponse.ImageToUpload region = pdfRegion(1, 1, List.of(0, 0, 300, 300), 300, 300);

        List<String> crops = service.previewCrops(List.of(region), List.of(unrelatedRaster, sourcePdf));

        assertThat(crops).hasSize(1);
        BufferedImage cropped = imageFromDataUrl(crops.get(0));
        assertThat(cropped.getWidth()).isEqualTo(300);
        assertThat(cropped.getHeight()).isEqualTo(300);
        assertThat(new Color(cropped.getRGB(150, 150))).isEqualTo(new Color(255, 0, 0));
    }

    @Test
    void mapsGlobalOcrPageToTheSelectedPdfLocalPage() throws IOException {
        Question question = new Question();
        question.setId(21L);
        question.setNumber(1);
        OcrUploadedFile unrelatedRaster = new OcrUploadedFile(
                "unrelated.png", MediaType.IMAGE_PNG, pngBytes(10, 10));
        OcrUploadedFile sourcePdf = new OcrUploadedFile(
                "exam.pdf", MediaType.APPLICATION_PDF,
                pdfBytes(new Color[] {Color.RED, Color.BLUE}, 72, 72));
        OcrResponse.ImageToUpload region = pdfRegion(1, 1, List.of(0, 0, 300, 300), 300, 300);
        AtomicReference<byte[]> uploadedPng = new AtomicReference<>();
        when(storage.put(anyString(), any(), any(byte[].class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            uploadedPng.set(invocation.getArgument(2));
            return Mono.just(new StoredObject(key, "/uploads/" + key));
        });
        when(repository.save(any(QuestionImage.class))).thenAnswer(invocation ->
                Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.persistQuestionImages(
                        List.of(question), List.of(region), List.of(unrelatedRaster, sourcePdf)))
                .assertNext(images -> assertThat(images).hasSize(1))
                .verifyComplete();

        BufferedImage cropped = ImageIO.read(new java.io.ByteArrayInputStream(uploadedPng.get()));
        assertThat(cropped.getWidth()).isEqualTo(300);
        assertThat(cropped.getHeight()).isEqualTo(300);
        assertThat(new Color(cropped.getRGB(150, 150))).isEqualTo(new Color(255, 0, 0));
    }

    @Test
    void secondPdfUsesItsOwnPageIndexAfterAPrecedingMultiPagePdf() throws IOException {
        OcrUploadedFile firstPdf = new OcrUploadedFile(
                "first.pdf", MediaType.APPLICATION_PDF,
                pdfBytes(new Color[] {Color.RED, Color.BLUE}, 72, 72));
        OcrUploadedFile secondPdf = new OcrUploadedFile(
                "second.pdf", MediaType.APPLICATION_PDF,
                pdfBytes(new Color[] {Color.GREEN, Color.MAGENTA}, 72, 72));
        OcrResponse.ImageToUpload region = pdfRegion(3, 1, List.of(0, 0, 300, 300), 300, 300);

        List<String> crops = service.previewCrops(List.of(region), List.of(firstPdf, secondPdf));

        assertThat(new Color(imageFromDataUrl(crops.get(0)).getRGB(150, 150)))
                .isEqualTo(new Color(255, 0, 255));
    }

    @Test
    void ignoresPdfRegionsWithAnInvalidPageIndex() throws IOException {
        OcrUploadedFile sourcePdf = new OcrUploadedFile(
                "exam.pdf", MediaType.APPLICATION_PDF, pdfBytes(new Color[] {Color.RED}, 72, 72));
        OcrResponse.ImageToUpload region = pdfRegion(1, 0, List.of(0, 0, 300, 300), 300, 300);

        List<String> crops = service.previewCrops(List.of(region), List.of(sourcePdf));

        assertThat(crops).containsExactly((String) null);
    }

    @Test
    void skipsPdfPagesThatExceedTheRenderDimensionLimit() throws IOException {
        OcrUploadedFile sourcePdf = new OcrUploadedFile(
                "wide.pdf", MediaType.APPLICATION_PDF, pdfBytes(new Color[] {Color.GREEN}, 2_000, 72));
        OcrResponse.ImageToUpload region = pdfRegion(0, 0, List.of(0, 0, 8_334, 300), 8_334, 300);

        List<String> crops = service.previewCrops(List.of(region), List.of(sourcePdf));

        assertThat(crops).containsExactly((String) null);
    }

    @Test
    void skipsPdfDocumentsAboveThePageCountLimit() throws IOException {
        Color[] colors = new Color[51];
        Arrays.fill(colors, Color.RED);
        OcrUploadedFile sourcePdf = new OcrUploadedFile(
                "too-many-pages.pdf", MediaType.APPLICATION_PDF, pdfBytes(colors, 72, 72));
        OcrResponse.ImageToUpload region = pdfRegion(0, 0, List.of(0, 0, 300, 300), 300, 300);

        List<String> crops = service.previewCrops(List.of(region), List.of(sourcePdf));

        assertThat(crops).containsExactly((String) null);
    }

    @Test
    void skipsPdfSourcesAboveTheUploadSizeLimitWithoutParsingThem() {
        byte[] oversizedContent = new byte[20 * 1024 * 1024 + 1];
        byte[] header = "%PDF-1.4".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, oversizedContent, 0, header.length);
        OcrUploadedFile sourcePdf = new OcrUploadedFile(
                "oversized.pdf", MediaType.APPLICATION_PDF, oversizedContent);
        OcrResponse.ImageToUpload region = pdfRegion(0, 0, List.of(0, 0, 300, 300), 300, 300);

        List<String> crops = service.previewCrops(List.of(region), List.of(sourcePdf));

        assertThat(crops).containsExactly((String) null);
    }

    @Test
    void stillCropsRasterSourcesUsingTheExistingCoordinateScaling() {
        OcrResponse.ImageToUpload region = new OcrResponse.ImageToUpload(
                "questao.png", "Figura raster", "questao_1", 0,
                List.of(2, 2, 8, 8), 10, 10, 1, 0, "figure", 0.9);
        OcrUploadedFile source = new OcrUploadedFile(
                "exam.png", MediaType.IMAGE_PNG, pngBytes(10, 10));

        List<String> crops = service.previewCrops(List.of(region), List.of(source));

        assertThat(crops).hasSize(1).first().asString().startsWith("data:image/png;base64,");
    }

    private OcrResponse.ImageToUpload pdfRegion(
            int pageIndex, int sourceFileIndex, List<Integer> bbox, int width, int height) {
        return new OcrResponse.ImageToUpload(
                "figure.png", "Figura da questão 1", "questao_1", pageIndex,
                bbox, width, height, 1, sourceFileIndex, "figure", 0.9);
    }

    private BufferedImage imageFromDataUrl(String dataUrl) throws IOException {
        assertThat(dataUrl).startsWith("data:image/png;base64,");
        byte[] bytes = java.util.Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1));
        return ImageIO.read(new java.io.ByteArrayInputStream(bytes));
    }

    private byte[] pdfBytes(Color[] pageColors, float widthPoints, float heightPoints) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (Color color : pageColors) {
                PDPage page = new PDPage(new PDRectangle(widthPoints, heightPoints));
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.setNonStrokingColor(color);
                    content.addRect(0, 0, widthPoints, heightPoints);
                    content.fill();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] pngBytes(int width, int height) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
            return output.toByteArray();
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }
}
