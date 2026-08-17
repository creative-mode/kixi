package ao.creativemode.kixi.service.storage;

import ao.creativemode.kixi.config.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@ConditionalOnProperty(prefix = "storage", name = "provider", havingValue = "s3")
public class S3ImageStorage implements ImageStorage {

    private final S3Client client;
    private final StorageProperties properties;

    public S3ImageStorage(S3Client client, StorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public Mono<StoredObject> put(String key, MediaType contentType, byte[] content) {
        if (content.length > maxSize()) {
            return Mono.error(new StorageException("Image exceeds the configured size limit"));
        }
        return Mono.fromCallable(() -> {
                        client.putObject(
                                PutObjectRequest.builder()
                                        .bucket(properties.getS3Bucket())
                                        .key(key)
                                        .contentType(contentType.toString())
                                        .contentLength((long) content.length)
                                        .build(),
                                RequestBody.fromBytes(content));
                        return new StoredObject(key, publicUrl(key));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> delete(String key) {
        if (key == null || key.isBlank()) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(properties.getS3Bucket())
                        .key(key)
                        .build()))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
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
            String endpoint = properties.getS3Endpoint();
            if (endpoint == null || endpoint.isBlank()) {
                endpoint = "https://" + properties.getS3Bucket() + ".s3." + properties.getS3Region() + ".amazonaws.com";
            } else {
                endpoint = endpoint.replaceAll("/$", "") + "/" + properties.getS3Bucket();
            }
            base = endpoint;
        }
        return base.replaceAll("/$", "") + "/" + key;
    }
}
