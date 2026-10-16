package ao.creativemode.kixi.shared.storage;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.ApiAbuseRateLimiter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * An image chosen from the device for a profile photo or a school logo: the form has a file input, not a field
 * waiting for a URL. The file is stored under {@code images/{kind}/} and the address that comes back is what the
 * forms keep in {@code photo} / {@code logo}, so no other contract changes.
 *
 * <p>What the bytes are is decided by their first bytes (never by the name or the type the browser sent), only
 * JPEG, PNG and WebP are taken, an image that decodes into far more pixels than it has bytes is refused, and the
 * upload shares the per-account upload limit with the other upload routes.</p>
 */
@RestController
@RequestMapping("/api/v1/images")
public class ProfileImageController {

    /** The folders an upload can go to. Anything else is a 400, so the key cannot be steered. */
    private static final Set<String> KINDS = Set.of("avatar", "logo");
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final long MAX_PIXELS = 16_000_000L;

    private final ImageStorage storage;
    private final CurrentAccountService currentAccountService;
    private final ApiAbuseRateLimiter abuseRateLimiter;

    public ProfileImageController(ImageStorage storage, CurrentAccountService currentAccountService,
                                  ApiAbuseRateLimiter abuseRateLimiter) {
        this.storage = storage;
        this.currentAccountService = currentAccountService;
        this.abuseRateLimiter = abuseRateLimiter;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Map<String, String>>> upload(
            @RequestParam("kind") String kind,
            @RequestPart("file") FilePart file) {
        if (!KINDS.contains(kind)) {
            return Mono.error(ApiException.badRequest("The image kind must be one of " + KINDS));
        }
        return currentAccountService.requiredAccountId()
                .flatMap(abuseRateLimiter::checkUpload)
                .then(DataBufferUtils.join(file.content(), MAX_BYTES))
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .onErrorMap(DataBufferLimitException.class, error -> ApiException.badRequest("The image is larger than 2 MB"))
                .switchIfEmpty(Mono.error(ApiException.badRequest("The image is empty")))
                .flatMap(bytes -> {
                    UploadSniffer.Kind detected = UploadSniffer.detect(bytes)
                            .filter(k -> k != UploadSniffer.Kind.PDF)
                            .orElseThrow(() -> new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type",
                                    "Send a JPEG, PNG or WebP image.", "IMAGE_TYPE_UNSUPPORTED"));
                    UploadSniffer.requireSafe(detected, bytes, MAX_PIXELS, 1);
                    String key = "images/" + kind + "/" + UUID.randomUUID() + "." + detected.extension();
                    return storage.put(key, MediaType.parseMediaType(detected.mimeType()), bytes);
                })
                .map(stored -> ResponseEntity.status(HttpStatus.CREATED).body(Map.of("url", stored.publicUrl())));
    }
}
