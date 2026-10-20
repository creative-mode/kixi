package ao.creativemode.kixi.shared.storage;

import ao.creativemode.kixi.shared.config.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Service
@ConditionalOnProperty(prefix = "storage", name = "provider", havingValue = "s3")
public class S3ImageStorage implements ImageStorage {

    private final S3Client client;
    private final S3Presigner presigner;
    private final StorageProperties properties;

    public S3ImageStorage(S3Client client, S3Presigner presigner, StorageProperties properties) {
        this.client = client;
        this.presigner = presigner;
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
                        return new StoredObject(key, "storage://" + key);
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

    @Override
    public Mono<byte[]> get(String key) {
        if (key == null || key.isBlank()) {
            return Mono.error(new StorageException("Invalid image storage key"));
        }
        return Mono.fromCallable(() -> client.getObjectAsBytes(GetObjectRequest.builder()
                                .bucket(properties.getS3Bucket())
                                .key(key)
                                .build())
                        .asByteArray())
                .onErrorMap(error -> !(error instanceof StorageException),
                        error -> new StorageException("Could not read image object", error))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private int maxSize() {
        long max = properties.getMaxObjectSizeBytes();
        if (max < 1 || max > Integer.MAX_VALUE) {
            throw new IllegalStateException("storage.max-object-size-bytes must be between 1 and 2147483647");
        }
        return (int) max;
    }

    private String publicUrl(String key) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(15))
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(properties.getS3Bucket())
                                .key(key)
                                .build())
                        .build())
                .url()
                .toExternalForm();
    }

    @Override
    public String urlFor(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.contains("..")) {
            throw new StorageException("Invalid image storage key");
        }
        return publicUrl(key);
    }

    @Override
    public String resolveUrl(String reference) {
        if (reference == null || reference.isBlank()) {
            return reference;
        }
        String key = reference.startsWith("storage://")
                ? reference.substring("storage://".length())
                : legacyKey(reference);
        return key == null ? reference : urlFor(key);
    }

    private String legacyKey(String reference) {
        String endpoint = properties.getS3Endpoint();
        String[] bases = {
                properties.getPublicBaseUrl(),
                endpoint == null || endpoint.isBlank()
                        ? null : endpoint.replaceAll("/$", "") + "/" + properties.getS3Bucket()
        };
        for (String base : bases) {
            if (base != null && !base.isBlank()) {
                String prefix = base.replaceAll("/$", "") + "/";
                if (reference.startsWith(prefix)) {
                    return reference.substring(prefix.length());
                }
            }
        }
        return null;
    }
}
