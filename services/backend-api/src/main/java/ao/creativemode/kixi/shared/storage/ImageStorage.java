package ao.creativemode.kixi.shared.storage;

import org.springframework.http.MediaType;

import reactor.core.publisher.Mono;

public interface ImageStorage {

    Mono<StoredObject> put(String key, MediaType contentType, byte[] content);

    Mono<Void> delete(String key);

    /** The bytes stored under a key. Errors with {@link StorageException} when there is nothing there. */
    Mono<byte[]> get(String key);
}
