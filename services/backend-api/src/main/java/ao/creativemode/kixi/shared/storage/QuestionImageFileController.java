package ao.creativemode.kixi.shared.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import ao.creativemode.kixi.shared.config.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Serves the question figures that {@link LocalImageStorage} keeps on disk, at the address it stored in
 * {@code image_url} ({@code /uploads/questions/{questionId}/{file}}).
 *
 * <p>With the local provider nothing served that address: the figures an OCR cut out of an exam, and the
 * ones a teacher attached, were saved but never reached an {@code <img>}. Only {@code questions/} is served,
 * and only a plain image file name, so the route cannot read {@code donations/} (which are not public) or
 * leave the storage root.
 */
@RestController
@ConditionalOnProperty(prefix = "storage", name = "provider", havingValue = "local", matchIfMissing = true)
public class QuestionImageFileController {

    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,120}\\.(png|jpe?g|webp|gif)", Pattern.CASE_INSENSITIVE);
    private static final Map<String, MediaType> TYPES = Map.of(
            "png", MediaType.IMAGE_PNG,
            "jpg", MediaType.IMAGE_JPEG,
            "jpeg", MediaType.IMAGE_JPEG,
            "webp", MediaType.parseMediaType("image/webp"),
            "gif", MediaType.IMAGE_GIF);

    private final Path root;

    public QuestionImageFileController(StorageProperties properties) {
        this.root = Paths.get(properties.getLocalRoot()).toAbsolutePath().normalize();
    }

    @GetMapping("/uploads/questions/{questionId}/{file}")
    public Mono<ResponseEntity<byte[]>> serve(@PathVariable String questionId, @PathVariable String file) {
        if (!questionId.matches("\\d{1,18}")) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        return read("questions", questionId, file);
    }

    /** The photos and logos chosen from the device: {@code /uploads/images/{avatar|logo}/{file}}. */
    @GetMapping("/uploads/images/{kind}/{file}")
    public Mono<ResponseEntity<byte[]>> serveImage(@PathVariable String kind, @PathVariable String file) {
        if (!kind.equals("avatar") && !kind.equals("logo")) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        return read("images", kind, file);
    }

    private Mono<ResponseEntity<byte[]>> read(String area, String folder, String file) {
        if (!FILE.matcher(file).matches() || file.contains("..")) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        Path target = root.resolve(area).resolve(folder).resolve(file).normalize();
        if (!target.startsWith(root.resolve(area))) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        String extension = file.substring(file.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return Mono.<ResponseEntity<byte[]>>fromCallable(() -> {
                    try {
                        return ResponseEntity.ok()
                                .contentType(TYPES.getOrDefault(extension, MediaType.APPLICATION_OCTET_STREAM))
                                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                                .header("X-Content-Type-Options", "nosniff")
                                .body(Files.readAllBytes(target));
                    } catch (IOException missing) {
                        return ResponseEntity.<byte[]>notFound().build();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
