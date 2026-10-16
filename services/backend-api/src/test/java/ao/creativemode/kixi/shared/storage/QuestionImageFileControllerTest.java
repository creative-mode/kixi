package ao.creativemode.kixi.shared.storage;

import java.nio.file.Files;
import java.nio.file.Path;

import ao.creativemode.kixi.shared.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The figures kept on disk reach the browser, and nothing else on the disk does. */
class QuestionImageFileControllerTest {

    @TempDir
    Path root;

    private WebTestClient client;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("questions/7"));
        Files.write(root.resolve("questions/7/ocr-1b2c.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        Files.createDirectories(root.resolve("donations/3"));
        Files.write(root.resolve("donations/3/secret.png"), new byte[] {1, 2, 3});
        StorageProperties properties = new StorageProperties();
        properties.setLocalRoot(root.toString());
        client = WebTestClient.bindToController(new QuestionImageFileController(properties)).build();
    }

    @Test
    void aStoredFigureIsServedAsAnImageThatTheBrowserMayCacheForAYear() {
        client.get().uri("/uploads/questions/7/ocr-1b2c.png").exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("image/png")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().value("Cache-Control", v -> org.assertj.core.api.Assertions.assertThat(v).contains("max-age=31536000").contains("immutable"))
                .expectBody(byte[].class).value(body -> org.assertj.core.api.Assertions.assertThat(body).hasSize(4));
    }

    @Test
    void aPhotoOrLogoChosenFromTheDeviceIsServedToo() throws Exception {
        Files.createDirectories(root.resolve("images/avatar"));
        Files.write(root.resolve("images/avatar/9f.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        client.get().uri("/uploads/images/avatar/9f.png").exchange().expectStatus().isOk().expectHeader().contentType("image/png");
        client.get().uri("/uploads/images/outra/9f.png").exchange().expectStatus().isNotFound();
        client.get().uri("/uploads/images/avatar/..%2F..%2Fdonations%2F3%2Fsecret.png").exchange().expectStatus().isNotFound();
    }

    @Test
    void aMissingFigureIsA404() {
        client.get().uri("/uploads/questions/7/nao-existe.png").exchange().expectStatus().isNotFound();
    }

    @Test
    void nothingOutsideTheQuestionFiguresIsServed() {
        // a file that is not an image, a path that climbs out, and another folder of the same storage
        client.get().uri("/uploads/questions/7/ocr-1b2c.txt").exchange().expectStatus().isNotFound();
        client.get().uri("/uploads/questions/7/..%2F..%2Fdonations%2F3%2Fsecret.png").exchange().expectStatus().isNotFound();
        client.get().uri("/uploads/questions/..%2Fdonations/secret.png").exchange().expectStatus().isNotFound();
        client.get().uri("/uploads/questions/abc/ocr-1b2c.png").exchange().expectStatus().isNotFound();
    }
}
