package ao.creativemode.kixi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import ao.creativemode.kixi.client.OcrUploadedFile;
import ao.creativemode.kixi.dto.ocr.OcrResponse;
import ao.creativemode.kixi.model.Question;
import ao.creativemode.kixi.model.QuestionImage;
import ao.creativemode.kixi.repository.QuestionImageRepository;
import ao.creativemode.kixi.service.storage.ImageStorage;
import ao.creativemode.kixi.service.storage.StoredObject;
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
            0
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
        verify(storage).put(anyString(), any(), any(byte[].class));
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
