package ao.creativemode.kixi.donations.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/** Small real files for the donation tests: bytes that are what they claim, and bytes that are not. */
public final class DonationFixtures {

    private DonationFixtures() {
    }

    public static byte[] png(int width, int height, int shade) {
        return image("png", width, height, shade);
    }

    public static byte[] jpeg(int width, int height, int shade) {
        return image("jpg", width, height, shade);
    }

    private static byte[] image(String format, int width, int height, int shade) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, (shade * 65793) & 0xFFFFFF);
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A PNG whose header says it is {@code width} x {@code height}: the IHDR is all the sniffer reads. */
    public static byte[] pngClaiming(int width, int height) {
        byte[] real = png(1, 1, 7);
        byte[] out = real.clone();
        // IHDR data starts at byte 16: 4 bytes of width, 4 of height, big endian.
        write32(out, 16, width);
        write32(out, 20, height);
        return out;
    }

    private static void write32(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >>> 24);
        bytes[at + 1] = (byte) (value >>> 16);
        bytes[at + 2] = (byte) (value >>> 8);
        bytes[at + 3] = (byte) value;
    }

    /** A WebP header with the VP8X layout: enough for the type and the size, not a picture. */
    public static byte[] webp(int width, int height) {
        byte[] out = new byte[40];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 4);
        out[4] = 32;
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, out, 8, 4);
        System.arraycopy("VP8X".getBytes(StandardCharsets.US_ASCII), 0, out, 12, 4);
        out[16] = 10;
        int w = width - 1;
        int h = height - 1;
        out[24] = (byte) w;
        out[25] = (byte) (w >> 8);
        out[26] = (byte) (w >> 16);
        out[27] = (byte) h;
        out[28] = (byte) (h >> 8);
        out[29] = (byte) (h >> 16);
        return out;
    }

    public static byte[] pdf(int pages, String extraObject) {
        StringBuilder text = new StringBuilder("%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\n");
        for (int i = 0; i < pages; i++) {
            text.append(i + 2).append(" 0 obj << /Type /Page >> endobj\n");
        }
        text.append(extraObject == null ? "" : extraObject).append("\n%%EOF\n");
        return text.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** A Windows executable renamed to .png by whoever sends it. */
    public static byte[] executable() {
        byte[] out = new byte[64];
        out[0] = 'M';
        out[1] = 'Z';
        return out;
    }
}
