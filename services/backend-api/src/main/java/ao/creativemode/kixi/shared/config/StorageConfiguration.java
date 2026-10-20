package ao.creativemode.kixi.shared.config;

import java.net.URI;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

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

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "storage", name = "provider", havingValue = "s3")
    S3Presigner s3Presigner(StorageProperties properties) {
        if (properties.getS3AccessKey().isBlank() || properties.getS3SecretKey().isBlank()) {
            throw new IllegalStateException("storage.s3-access-key and storage.s3-secret-key must be configured for S3 storage");
        }
        if (properties.getS3PublicEndpoint().isBlank()) {
            throw new IllegalStateException("storage.s3-public-endpoint must be configured for S3 storage");
        }
        URI publicEndpoint = URI.create(properties.getS3PublicEndpoint());
        String host = publicEndpoint.getHost();
        boolean localEndpoint = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
        if (!"https".equalsIgnoreCase(publicEndpoint.getScheme()) && !localEndpoint) {
            throw new IllegalStateException("storage.s3-public-endpoint must use HTTPS outside local development");
        }
        if (publicEndpoint.getHost() == null || publicEndpoint.getRawUserInfo() != null
                || publicEndpoint.getRawQuery() != null || publicEndpoint.getRawFragment() != null) {
            throw new IllegalStateException("storage.s3-public-endpoint must be a valid endpoint without credentials or query parameters");
        }
        return S3Presigner.builder()
                .region(Region.of(properties.getS3Region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getS3AccessKey(), properties.getS3SecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isS3PathStyleAccess())
                        .build())
                .endpointOverride(publicEndpoint)
                .build();
    }
}
