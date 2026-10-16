package ao.creativemode.kixi.shared.storage;

import ao.creativemode.kixi.shared.exception.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.HttpStatus;

/**
 * What an uploaded file really is (issue #252).
 *
 * <p>Decided by the first bytes and never by the name or the {@code Content-Type} the client
 * sent: both are written by the caller, and a renamed executable passes any check that reads them.
 * Then, for what is accepted, the two ways a small file hurts the server that reads it:</p>
 * <ul>
 *   <li>an image that decodes into far more pixels than it has bytes (a decompression bomb);</li>
 *   <li>a PDF that carries script or an embedded file, which the OCR service would open.</li>
 * </ul>
 */
public final class UploadSniffer {

    /** The only formats a donation takes: what a phone camera, a scanner or a PDF export produces. */
    public enum Kind {
        JPEG("image/jpeg", "jpg"),
        PNG("image/png", "png"),
        WEBP("image/webp", "webp"),
        PDF("application/pdf", "pdf");

        private final String mimeType;
        private final String extension;

        Kind(String mimeType, String extension) {
            this.mimeType = mimeType;
            this.extension = extension;
        }

        public String mimeType() {
            return mimeType;
        }

        public String extension() {
            return extension;
        }
    }

    /** What a PDF must not carry. A scanned exam has none of it; an attack almost always has one. */
    private static final Pattern ACTIVE_PDF_CONTENT =
            Pattern.compile("/(JavaScript|JS|Launch|EmbeddedFile|RichMedia)(?![A-Za-z])");
    private static final Pattern PDF_PAGE = Pattern.compile("/Type\\s*/Page(?![A-Za-z])");

    private UploadSniffer() {
    }

    /** The kind of the bytes, or empty when they are none of the four. */
    public static Optional<Kind> detect(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return Optional.empty();
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return Optional.of(Kind.JPEG);
        }
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
            return Optional.of(Kind.PNG);
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return Optional.of(Kind.WEBP);
        }
        if (bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-') {
            return Optional.of(Kind.PDF);
        }
        return Optional.empty();
    }

    /**
     * Refuses a file that is the right kind but unsafe to hand to the OCR. Returns the number of
     * pages for a PDF when it could be counted (a PDF that keeps its pages inside compressed object
     * streams reads as unknown, and null is not a refusal).
     */
    public static Integer requireSafe(Kind kind, byte[] content, long maxImagePixels, int maxPdfPages) {
        return switch (kind) {
            case PDF -> requireSafePdf(content, maxPdfPages);
            case WEBP -> {
                requireReasonablePixels(webpPixels(content), maxImagePixels);
                yield null;
            }
            case JPEG, PNG -> {
                requireReasonablePixels(rasterPixels(content), maxImagePixels);
                yield null;
            }
        };
    }

    private static Integer requireSafePdf(byte[] content, int maxPages) {
        // ISO-8859-1 maps every byte to one char, so the search sees the file as it is.
        String text = new String(content, StandardCharsets.ISO_8859_1);
        if (ACTIVE_PDF_CONTENT.matcher(text).find()) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type",
                    "The PDF carries script or an embedded file. Send photos of the pages or a flattened PDF.",
                    "DONATION_PDF_ACTIVE_CONTENT");
        }
        int pages = 0;
        var matcher = PDF_PAGE.matcher(text);
        while (matcher.find()) {
            pages++;
        }
        if (pages > maxPages) {
            throw ApiException.badRequest("A PDF can have at most " + maxPages + " pages");
        }
        return pages == 0 ? null : pages;
    }

    private static void requireReasonablePixels(long pixels, long max) {
        if (pixels > max) {
            throw ApiException.badRequest("The image is too large to read: " + pixels + " pixels");
        }
    }

    /** Width times height from the header only: nothing is decoded. */
    private static long rasterPixels(byte[] content) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalidImage();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return (long) reader.getWidth(0) * (long) reader.getHeight(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof ApiException api) {
                throw api;
            }
            throw invalidImage();
        }
    }

    /** The three WebP layouts keep their size in different places; none needs a decoder. */
    static long webpPixels(byte[] b) {
        if (b.length < 30) {
            throw invalidImage();
        }
        String chunk = new String(b, 12, 4, StandardCharsets.ISO_8859_1);
        long width;
        long height;
        switch (chunk) {
            case "VP8X" -> {
                width = 1 + le24(b, 24);
                height = 1 + le24(b, 27);
            }
            case "VP8 " -> {
                width = (b[26] & 0xFF | (b[27] & 0x3F) << 8);
                height = (b[28] & 0xFF | (b[29] & 0x3F) << 8);
            }
            case "VP8L" -> {
                long bits = (b[21] & 0xFFL) | (b[22] & 0xFFL) << 8 | (b[23] & 0xFFL) << 16 | (b[24] & 0xFFL) << 24;
                width = 1 + (bits & 0x3FFF);
                height = 1 + ((bits >> 14) & 0x3FFF);
            }
            default -> throw invalidImage();
        }
        return width * height;
    }

    private static long le24(byte[] b, int at) {
        return (b[at] & 0xFFL) | (b[at + 1] & 0xFFL) << 8 | (b[at + 2] & 0xFFL) << 16;
    }

    private static ApiException invalidImage() {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type",
                "The file is not a readable image", "DONATION_INVALID_IMAGE");
    }
}
