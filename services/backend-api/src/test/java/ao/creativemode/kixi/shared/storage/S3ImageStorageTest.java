package ao.creativemode.kixi.shared.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ao.creativemode.kixi.shared.config.StorageProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3ImageStorageTest {

    @Test
    void returnsFreshTemporaryUrlsForStableReferencesAndLegacyPublicUrls() {
        StorageProperties properties = new StorageProperties();
        properties.setProvider("s3");
        properties.setS3Endpoint("http://minio:9000");
        properties.setS3PublicEndpoint("https://images.example.test");
        properties.setS3Bucket("kixi-images");
        properties.setS3AccessKey("test-access");
        properties.setS3SecretKey("test-secret");

        try (S3Presigner presigner = S3Presigner.builder()
                .region(Region.of(properties.getS3Region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getS3AccessKey(), properties.getS3SecretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .endpointOverride(java.net.URI.create(properties.getS3PublicEndpoint()))
                .build()) {
            S3ImageStorage storage = new S3ImageStorage(mock(S3Client.class), presigner, properties);

            String current = storage.resolveUrl("storage://questions/14/figure.png");
            String legacy = storage.resolveUrl("http://minio:9000/kixi-images/questions/14/figure.png");

            assertTrue(current.startsWith("https://images.example.test/kixi-images/questions/14/figure.png?"));
            assertTrue(current.contains("X-Amz-Expires=900"));
            assertTrue(current.contains("X-Amz-Signature="));
            assertTrue(legacy.startsWith("https://images.example.test/kixi-images/questions/14/figure.png?"));
            assertTrue(legacy.contains("X-Amz-Expires=900"));
            assertTrue(storage.isManagedImageReference("storage://images/avatar/profile.png"));
            assertTrue(storage.isManagedImageReference("http://minio:9000/kixi-images/images/avatar/profile.png"));
            assertFalse(storage.isManagedImageReference("storage://questions/14/figure.png"));
            assertFalse(storage.isManagedImageReference("http://minio:9000/kixi-images/questions/14/figure.png"));
            assertFalse(storage.isManagedImageReference("text containing /images/not-an-object"));
        }
    }
}
