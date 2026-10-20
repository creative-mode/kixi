package ao.creativemode.kixi.shared.storage;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Smaller copies of the stored images, made the first time a width is asked for and kept on disk.
 *
 * <p>An avatar of 1.4 MB shown in a circle of 44 px costs the whole file on every first visit; the page asks for
 * {@code ?w=96} instead and gets a few kilobytes. The original is never touched, only a fixed set of widths is
 * accepted (so the disk cannot be filled by asking for every width), and a width at or above the original's
 * returns the original. Formats the JDK cannot decode (WebP, GIF animation) are served as they are.</p>
 */
final class ImageVariants {

    /** The widths a client may ask for; anything else is ignored. */
    static final Set<Integer> WIDTHS = Set.of(48, 96, 192, 384, 768, 1280);

    private ImageVariants() {}

    /**
     * The file to serve for {@code width}: a cached variant (made now if missing) or {@code original}.
     * Blocking: call it off the event loop.
     */
    static Path resolve(Path root, String area, String folder, String file, Path original, Integer width) throws IOException {
        if (width == null || !WIDTHS.contains(width)) {
            return original;
        }
        String extension = file.substring(file.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!extension.equals("png") && !extension.equals("jpg") && !extension.equals("jpeg")) {
            return original;
        }
        Path variant = root.resolve("variants").resolve(area).resolve(folder).resolve(Integer.toString(width)).resolve(file).normalize();
        if (!variant.startsWith(root.resolve("variants"))) {
            return original;
        }
        if (Files.isRegularFile(variant)) {
            return variant;
        }
        BufferedImage source = ImageIO.read(original.toFile());
        if (source == null || source.getWidth() <= width) {
            return original;
        }
        int height = Math.max(1, Math.round((float) source.getHeight() * width / source.getWidth()));
        boolean alpha = source.getColorModel().hasAlpha();
        BufferedImage target = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        Files.createDirectories(variant.getParent());
        Path temp = variant.resolveSibling(file + "." + UUID.randomUUID() + ".tmp");
        try {
            write(target, extension, temp);
            Files.move(temp, variant, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        return variant;
    }

    private static void write(BufferedImage image, String extension, Path target) throws IOException {
        if (extension.equals("png")) {
            ImageIO.write(image, "png", target.toFile());
            return;
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(0.82f);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
    }
}
