package ao.creativemode.kixi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.config.StorageProperties;
import ao.creativemode.kixi.dto.questionimage.QuestionImageRequest;
import ao.creativemode.kixi.model.QuestionImage;
import ao.creativemode.kixi.repository.QuestionImageRepository;
import ao.creativemode.kixi.shared.storage.ImageStorage;
import ao.creativemode.kixi.shared.storage.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.mock;

class QuestionImageServiceTest {

    private QuestionImageRepository repository;
    private ImageStorage storage;
    private StorageProperties properties;
    private QuestionImageService service;

    @BeforeEach
    void setUp() {
        repository = mock(QuestionImageRepository.class);
        storage = mock(ImageStorage.class);
        properties = new StorageProperties();
        service = new QuestionImageService(repository, storage, properties);
    }

    @Test
    void storesRasterImageWithGeneratedKeyAndPersistsStorageKey() {
        FilePart file = filePart(MediaType.IMAGE_PNG, "../../unsafe.png", 3L);
        StoredObject stored = new StoredObject("questions/7/object.png", "/uploads/questions/7/object.png");
        AtomicReference<QuestionImage> persisted = new AtomicReference<>();
        when(storage.put(anyString(), any(), any())).thenReturn(Mono.just(stored));
        when(repository.save(any(QuestionImage.class))).thenAnswer(invocation -> {
            QuestionImage entity = invocation.getArgument(0);
            entity.setId(11L);
            persisted.set(entity);
            return Mono.just(entity);
        });

        StepVerifier.create(service.createWithFile(
                        new QuestionImageRequest(7L, "Figura", 2), Mono.just(file)))
                .assertNext(response -> assertThat(response.imageUrl()).isEqualTo(stored.publicUrl()))
                .verifyComplete();

        assertThat(persisted.get().getStorageKey()).isEqualTo(stored.key());
        verify(storage).put(anyString(), any(), any());
    }

    @Test
    void rejectsNonRasterUploadBeforeStorage() {
        FilePart file = filePart(MediaType.APPLICATION_PDF, "exam.pdf", 3L);

        StepVerifier.create(service.createWithFile(
                        new QuestionImageRequest(7L, null, null), Mono.just(file)))
                .expectErrorMessage("Only JPEG, PNG and WebP images are supported")
                .verify();

        verify(storage, never()).put(anyString(), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsRasterMimeTypeWithNonImageBytes() {
        FilePart file = filePart(MediaType.IMAGE_PNG, "spoofed.png", 9L, new byte[] {1, 2, 3});

        StepVerifier.create(service.createWithFile(
                        new QuestionImageRequest(7L, null, null), Mono.just(file)))
                .expectErrorMessage("Image content does not match its media type")
                .verify();

        verify(storage, never()).put(anyString(), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void purgesStorageObjectBeforeDeletingDatabaseRow() {
        QuestionImage entity = new QuestionImage();
        entity.setId(11L);
        entity.setStorageKey("questions/7/object.png");
        when(repository.findByIdAndDeletedAtIsNotNull(11L)).thenReturn(Mono.just(entity));
        when(storage.delete(entity.getStorageKey())).thenReturn(Mono.empty());
        when(repository.delete(entity)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(11L)).verifyComplete();

        verify(storage).delete(entity.getStorageKey());
        verify(repository).delete(entity);
    }

    @Test
    void findAllActiveMapsEveryEntityToResponse() {
        when(repository.findAllByDeletedAtIsNull()).thenReturn(Flux.just(image(1L, "cap-1")));

        StepVerifier.create(service.findAllActive())
                .assertNext(response -> assertThat(response.caption()).isEqualTo("cap-1"))
                .verifyComplete();
    }

    @Test
    void findAllDeletedReturnsOnlyTrashedEntities() {
        when(repository.findAllByDeletedAtIsNotNull()).thenReturn(Flux.just(image(2L, "cap-2")));

        StepVerifier.create(service.findAllDeleted())
                .assertNext(response -> assertThat(response.id()).isEqualTo(2L))
                .verifyComplete();
    }

    @Test
    void findByQuestionIdDelegatesToRepository() {
        when(repository.findByQuestionIdAndDeletedAtIsNullOrderByOrderIndexAsc(7L))
                .thenReturn(Flux.just(image(1L, "cap-1")));

        StepVerifier.create(service.findByQuestionId(7L))
                .assertNext(response -> assertThat(response.questionId()).isEqualTo(7L))
                .verifyComplete();
    }

    @Test
    void findByIdActiveReturnsNotFoundForMissingImage() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findByIdActive(99L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(404);
                })
                .verify();
    }

    @Test
    void updateRejectsMissingImageWithoutSaving() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(99L, new QuestionImageRequest(7L, "nova legenda", 1)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void updateAppliesNewCaptionAndOrderIndex() {
        QuestionImage existing = image(1L, "antiga");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any(QuestionImage.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.update(1L, new QuestionImageRequest(7L, "nova", 5)))
                .assertNext(response -> {
                    assertThat(response.caption()).isEqualTo("nova");
                    assertThat(response.orderIndex()).isEqualTo(5);
                })
                .verifyComplete();
    }

    @Test
    void softDeleteRejectsMissingImage() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.softDelete(99L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void softDeleteMarksEntityAsDeleted() {
        QuestionImage existing = image(1L, "cap");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(existing)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.softDelete(1L)).verifyComplete();

        assertThat(existing.isDeleted()).isTrue();
    }

    @Test
    void restoreRejectsImageThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.restore(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void restoreClearsDeletedAt() {
        QuestionImage deleted = image(1L, "cap");
        deleted.markAsDeleted();
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.save(deleted)).thenReturn(Mono.just(deleted));

        StepVerifier.create(service.restore(1L)).verifyComplete();

        assertThat(deleted.isDeleted()).isFalse();
    }

    @Test
    void hardDeleteRejectsImageThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(storage, never()).delete(anyString());
    }

    private QuestionImage image(Long id, String caption) {
        QuestionImage image = new QuestionImage();
        image.setId(id);
        image.setQuestionId(7L);
        image.setCaption(caption);
        image.setOrderIndex(0);
        return image;
    }

    private FilePart filePart(MediaType type, String filename, long length) {
        return filePart(type, filename, length, pngBytes());
    }

    private FilePart filePart(MediaType type, String filename, long length, byte[] bytes) {
        FilePart file = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.setContentLength(length);
        when(file.headers()).thenReturn(headers);
        when(file.filename()).thenReturn(filename);
        when(file.content()).thenReturn(Flux.just(new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap(bytes)));
        return file;
    }

    private byte[] pngBytes() {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }
}
