package ao.creativemode.kixi.shared.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class StorageConfigurationTest {

    @Test
    void failsClosedWhenS3HasNoBrowserReachablePublicEndpoint() {
        StorageProperties properties = validS3Properties();
        properties.setS3PublicEndpoint("");

        assertThatThrownBy(() -> new StorageConfiguration().s3Presigner(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("storage.s3-public-endpoint must be configured for S3 storage");
    }

    @Test
    void acceptsLocalHttpForMinioAndBuildsThePresigner() {
        StorageProperties properties = validS3Properties();
        properties.setS3PublicEndpoint("http://127.0.0.1:9000");

        try (S3Presigner presigner = new StorageConfiguration().s3Presigner(properties)) {
            assertThat(presigner).isNotNull();
        }
    }

    @Test
    void rejectsMalformedPublicEndpointBeforeBuildingThePresigner() {
        StorageProperties properties = validS3Properties();
        properties.setS3PublicEndpoint("not a uri");

        assertThatThrownBy(() -> new StorageConfiguration().s3Presigner(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("storage.s3-public-endpoint must be a valid URI");
    }

    private static StorageProperties validS3Properties() {
        StorageProperties properties = new StorageProperties();
        properties.setProvider("s3");
        properties.setS3AccessKey("test-access");
        properties.setS3SecretKey("test-secret");
        return properties;
    }
}
