package ao.creativemode.kixi.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageVariantsTest {

    @TempDir Path root;

    private Path original(String name, int w, int h, int type, String format) throws IOException {
        Path dir = Files.createDirectories(root.resolve("images").resolve("avatar"));
        Path file = dir.resolve(name);
        BufferedImage image = new BufferedImage(w, h, type);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                image.setRGB(x, y, (x * 7 + y * 13) | 0xFF000000);
            }
        }
        ImageIO.write(image, format, file.toFile());
        return file;
    }

    @Test
    void aBigAvatarGetsASmallVariantKeptOnDisk() throws IOException {
        Path file = original("a.png", 1200, 900, BufferedImage.TYPE_INT_ARGB, "png");

        Path variant = ImageVariants.resolve(root, "images", "avatar", "a.png", file, 96);

        assertThat(variant).isNotEqualTo(file).startsWith(root.resolve("variants"));
        BufferedImage small = ImageIO.read(variant.toFile());
        assertThat(small.getWidth()).isEqualTo(96);
        assertThat(small.getHeight()).isEqualTo(72);
        assertThat(Files.size(variant)).isLessThan(Files.size(file));
        assertThat(ImageVariants.resolve(root, "images", "avatar", "a.png", file, 96)).isEqualTo(variant);
    }

    @Test
    void aJpegStaysAJpeg() throws IOException {
        Path file = original("b.jpg", 800, 800, BufferedImage.TYPE_INT_RGB, "jpg");

        Path variant = ImageVariants.resolve(root, "images", "avatar", "b.jpg", file, 192);

        assertThat(variant.getFileName().toString()).isEqualTo("b.jpg");
        assertThat(ImageIO.read(variant.toFile()).getWidth()).isEqualTo(192);
    }

    @Test
    void aWidthAtOrAboveTheOriginalReturnsTheOriginal() throws IOException {
        Path file = original("c.png", 90, 90, BufferedImage.TYPE_INT_RGB, "png");

        assertThat(ImageVariants.resolve(root, "images", "avatar", "c.png", file, 96)).isEqualTo(file);
    }

    @Test
    void onlyTheFixedWidthsAreAccepted() throws IOException {
        Path file = original("d.png", 1000, 1000, BufferedImage.TYPE_INT_RGB, "png");

        assertThat(ImageVariants.resolve(root, "images", "avatar", "d.png", file, 97)).isEqualTo(file);
        assertThat(ImageVariants.resolve(root, "images", "avatar", "d.png", file, null)).isEqualTo(file);
        assertThat(root.resolve("variants")).doesNotExist();
    }

    @Test
    void formatsTheJdkCannotReadAreServedAsTheyAre() throws IOException {
        Path dir = Files.createDirectories(root.resolve("images").resolve("avatar"));
        Path webp = Files.write(dir.resolve("e.webp"), new byte[] {1, 2, 3});

        assertThat(ImageVariants.resolve(root, "images", "avatar", "e.webp", webp, 96)).isEqualTo(webp);
    }
}
