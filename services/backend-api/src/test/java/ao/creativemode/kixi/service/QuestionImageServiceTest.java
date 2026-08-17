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

import ao.creativemode.kixi.config.StorageProperties;
import ao.creativemode.kixi.dto.questionimage.QuestionImageRequest;
import ao.creativemode.kixi.model.QuestionImage;
import ao.creativemode.kixi.repository.QuestionImageRepository;
import ao.creativemode.kixi.service.storage.ImageStorage;
import ao.creativemode.kixi.service.storage.StoredObject;
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
