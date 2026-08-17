package ao.creativemode.kixi.config;

import java.net.URI;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "storage", name = "provider", havingValue = "s3")
    S3Client s3Client(StorageProperties properties) {
        if (properties.getS3AccessKey().isBlank() || properties.getS3SecretKey().isBlank()) {
            throw new IllegalStateException("storage.s3-access-key and storage.s3-secret-key must be configured for S3 storage");
        }

        var builder = S3Client.builder()
                .region(Region.of(properties.getS3Region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getS3AccessKey(), properties.getS3SecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isS3PathStyleAccess())
                        .build());

        if (!properties.getS3Endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.getS3Endpoint()));
        }
        return builder.build();
    }
}
