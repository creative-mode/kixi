package ao.creativemode.kixi.service.storage;

import org.springframework.http.MediaType;

import reactor.core.publisher.Mono;

public interface ImageStorage {

    Mono<StoredObject> put(String key, MediaType contentType, byte[] content);

    Mono<Void> delete(String key);
}
