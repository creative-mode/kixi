package ao.creativemode.kixi.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import ao.creativemode.kixi.shared.exception.GlobalExceptionHandler;
import ao.creativemode.kixi.shared.service.ApiAbuseRateLimiter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import reactor.core.publisher.Mono;

/** A photo or a logo chosen from the device: only real images, only the two folders, only what fits in 2 MB. */
class ProfileImageControllerTest {

    private ImageStorage storage;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        storage = mock(ImageStorage.class);
        when(storage.put(any(), any(), any())).thenAnswer(call ->
                Mono.just(new StoredObject(call.getArgument(0), "/uploads/" + call.getArgument(0))));
        CurrentAccountService account = mock(CurrentAccountService.class);
        when(account.requiredAccountId()).thenReturn(Mono.just(7L));
        ApiAbuseRateLimiter limiter = mock(ApiAbuseRateLimiter.class);
        when(limiter.checkUpload(7L)).thenReturn(Mono.empty());
        client = WebTestClient.bindToController(new ProfileImageController(storage, account, limiter))
                .controllerAdvice(new GlobalExceptionHandler()).build();
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private WebTestClient.ResponseSpec send(String kind, byte[] content, String name) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return name;
            }
        }).contentType(MediaType.IMAGE_PNG);
        return client.post().uri("/api/v1/images?kind=" + kind)
                .body(BodyInserters.fromMultipartData(body.build())).exchange();
    }

    @Test
    void aRealImageIsStoredUnderItsKindAndTheAddressComesBack() throws Exception {
        send("avatar", png(), "eu.png").expectStatus().isCreated()
                .expectBody().jsonPath("$.url").value(url -> assertThat((String) url).matches("/uploads/images/avatar/[0-9a-f-]{36}\\.png"));
        verify(storage).put(startsWith("images/avatar/"), eq(MediaType.IMAGE_PNG), any(byte[].class));
    }

    @Test
    void whatIsNotAnImageIsRefusedWhateverItIsCalled() {
        send("avatar", "<?php echo 1; ?> not an image at all".getBytes(), "foto.png").expectStatus().isEqualTo(415);
        verify(storage, never()).put(any(), any(), any());
    }

    @Test
    void onlyTheAvatarAndLogoFoldersExist() throws Exception {
        send("../../etc", png(), "x.png").expectStatus().isBadRequest();
        send("questions", png(), "x.png").expectStatus().isBadRequest();
        verify(storage, never()).put(any(), any(), any());
    }

    @Test
    void anImageOverTwoMegabytesIsRefused() {
        byte[] big = new byte[2 * 1024 * 1024 + 4096];
        big[0] = (byte) 0x89; big[1] = 'P'; big[2] = 'N'; big[3] = 'G';
        send("logo", big, "enorme.png").expectStatus().isBadRequest();
    }
}
