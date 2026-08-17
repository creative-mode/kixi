package ao.creativemode.kixi.service.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import ao.creativemode.kixi.config.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@ConditionalOnProperty(prefix = "storage", name = "provider", havingValue = "local", matchIfMissing = true)
public class LocalImageStorage implements ImageStorage {

    private final StorageProperties properties;
    private final Path root;

    public LocalImageStorage(StorageProperties properties) {
        this.properties = properties;
        this.root = Paths.get(properties.getLocalRoot()).toAbsolutePath().normalize();
    }

    @Override
    public Mono<StoredObject> put(String key, MediaType contentType, byte[] content) {
        Path target = resolveKey(key);
        if (content.length > maxSize()) {
            return Mono.error(new StorageException("Image exceeds the configured size limit"));
        }
        return Mono.fromCallable(() -> {
                    try {
                        Files.createDirectories(target.getParent());
                        Files.write(target, content, StandardOpenOption.CREATE_NEW);
                        return new StoredObject(key, publicUrl(key));
                    } catch (IOException ex) {
                        throw new StorageException("Could not persist image object", ex);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> delete(String key) {
        if (key == null || key.isBlank()) {
            return Mono.empty();
        }
        Path target = resolveKey(key);
        return Mono.fromRunnable(() -> {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ex) {
                throw new StorageException("Could not delete image object", ex);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Path resolveKey(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new StorageException("Invalid image storage key");
        }
        return resolved;
    }

    private int maxSize() {
        long max = properties.getMaxObjectSizeBytes();
        if (max < 1 || max > Integer.MAX_VALUE) {
            throw new IllegalStateException("storage.max-object-size-bytes must be between 1 and 2147483647");
        }
        return (int) max;
    }

    private String publicUrl(String key) {
        String base = properties.getPublicBaseUrl();
        if (base == null || base.isBlank()) {
            throw new StorageException("storage.public-base-url must be configured for local storage");
        }
        return base.replaceAll("/$", "") + "/" + key;
    }
}
