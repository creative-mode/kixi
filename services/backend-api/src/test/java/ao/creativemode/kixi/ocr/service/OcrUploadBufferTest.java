package ao.creativemode.kixi.ocr.service;

import static ao.creativemode.kixi.donations.service.DonationFixtures.png;
import static ao.creativemode.kixi.donations.service.DonationFixtures.pngClaiming;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class OcrUploadBufferTest {

    @Test
    void validatesPixelDimensionsBeforeRelayingTheFile() {
        StepVerifier.create(OcrUploadBuffer.buffer(List.of(file("bomb.png", pngClaiming(50_000, 50_000)))))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(400);
                })
                .verify();
    }

    @Test
    void acceptsAValidRasterWithinTheHeaderLimits() {
        StepVerifier.create(OcrUploadBuffer.buffer(List.of(file("exam.png", png(1, 1, 7)))))
                .assertNext(files -> {
                    assertThat(files).hasSize(1);
                    assertThat(files.get(0).filename()).isEqualTo("exam.png");
                })
                .verifyComplete();
    }

    private static FilePart file(String filename, byte[] content) {
        FilePart part = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        DataBuffer dataBuffer = DefaultDataBufferFactory.sharedInstance.wrap(content);
        when(part.filename()).thenReturn(filename);
        when(part.headers()).thenReturn(headers);
        when(part.content()).thenReturn(Flux.just(dataBuffer));
        return part;
    }
}
