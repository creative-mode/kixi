package ao.creativemode.kixi.ocr.service;

import ao.creativemode.kixi.ocr.client.OcrUploadedFile;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.storage.UploadSniffer;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Bounded, header-only validation for files before they are relayed to OCR. */
public final class OcrUploadBuffer {

    public static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    public static final long MAX_REQUEST_SIZE = 50L * 1024 * 1024;
    public static final long MAX_IMAGE_PIXELS = UploadSniffer.MAX_SAFE_IMAGE_PIXELS;
    public static final int MAX_PDF_PAGES = 50;

    private OcrUploadBuffer() {
    }

    public static Mono<List<OcrUploadedFile>> buffer(List<FilePart> files) {
        AtomicLong totalBytes = new AtomicLong();
        return Flux.fromIterable(files)
                .concatMap(file -> {
                    long remainingRequestBytes = MAX_REQUEST_SIZE - totalBytes.get();
                    if (remainingRequestBytes <= 0) {
                        return Mono.error(ApiException.badRequest(
                                "OCR input exceeds the 50 MB aggregate limit"));
                    }
                    int fileLimit = (int) Math.min(MAX_FILE_SIZE, remainingRequestBytes);
                    return DataBufferUtils.join(file.content(), fileLimit)
                            .switchIfEmpty(Mono.error(ApiException.badRequest(
                                    "Uploaded file cannot be empty")))
                            .map(buffer -> {
                                try {
                                    byte[] content = new byte[buffer.readableByteCount()];
                                    buffer.read(content);
                                    if (content.length == 0) {
                                        throw ApiException.badRequest("Uploaded file cannot be empty");
                                    }
                                    totalBytes.addAndGet(content.length);
                                    validateContent(content);
                                    return new OcrUploadedFile(
                                            file.filename(), file.headers().getContentType(), content);
                                } finally {
                                    DataBufferUtils.release(buffer);
                                }
                            });
                })
                .collectList()
                .onErrorMap(DataBufferLimitException.class,
                        error -> ApiException.badRequest(
                                "OCR input exceeds the 20 MB per-file or 50 MB aggregate limit"));
    }

    public static void validateContent(byte[] content) {
        var detected = UploadSniffer.detect(content);
        if (detected.isPresent()) {
            UploadSniffer.requireSafe(detected.get(), content, MAX_IMAGE_PIXELS, MAX_PDF_PAGES);
            return;
        }
        if (!isBmpOrTiff(content)) {
            throw ApiException.badRequest("Unsupported or invalid OCR file");
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                throw ApiException.badRequest("Unsupported or invalid OCR image");
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw ApiException.badRequest("Unsupported or invalid OCR image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels < 1 || pixels > MAX_IMAGE_PIXELS) {
                    throw ApiException.badRequest("OCR image exceeds the maximum pixel count");
                }
            } finally {
                reader.dispose();
            }
        } catch (java.io.IOException error) {
            throw ApiException.badRequest("Unsupported or invalid OCR image");
        }
    }

    private static boolean isBmpOrTiff(byte[] content) {
        boolean bmp = content.length >= 26 && content[0] == 'B' && content[1] == 'M';
        boolean littleEndianTiff = content.length >= 8
                && content[0] == 'I' && content[1] == 'I' && content[2] == 42 && content[3] == 0;
        boolean bigEndianTiff = content.length >= 8
                && content[0] == 'M' && content[1] == 'M' && content[2] == 0 && content[3] == 42;
        return bmp || littleEndianTiff || bigEndianTiff;
    }
}
