package ao.creativemode.kixi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Object storage configuration. The default keeps local development compatible
 * with the existing static upload directory; production should use S3.
 */
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    private String provider = "local";
    private String localRoot = "services/backend-api/src/main/resources/static/uploads";
    private String publicBaseUrl = "/uploads";
    private String s3Endpoint = "";
    private String s3Region = "us-east-1";
    private String s3Bucket = "kixi-images";
    private String s3AccessKey = "";
    private String s3SecretKey = "";
    private boolean s3PathStyleAccess = true;
    private long maxObjectSizeBytes = 20L * 1024 * 1024;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getLocalRoot() {
        return localRoot;
    }

    public void setLocalRoot(String localRoot) {
        this.localRoot = localRoot;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String getS3Endpoint() {
        return s3Endpoint;
    }

    public void setS3Endpoint(String s3Endpoint) {
        this.s3Endpoint = s3Endpoint;
    }

    public String getS3Region() {
        return s3Region;
    }

    public void setS3Region(String s3Region) {
        this.s3Region = s3Region;
    }

    public String getS3Bucket() {
        return s3Bucket;
    }

    public void setS3Bucket(String s3Bucket) {
        this.s3Bucket = s3Bucket;
    }

    public String getS3AccessKey() {
        return s3AccessKey;
    }

    public void setS3AccessKey(String s3AccessKey) {
        this.s3AccessKey = s3AccessKey;
    }

    public String getS3SecretKey() {
        return s3SecretKey;
    }

    public void setS3SecretKey(String s3SecretKey) {
        this.s3SecretKey = s3SecretKey;
    }

    public boolean isS3PathStyleAccess() {
        return s3PathStyleAccess;
    }

    public void setS3PathStyleAccess(boolean s3PathStyleAccess) {
        this.s3PathStyleAccess = s3PathStyleAccess;
    }

    public long getMaxObjectSizeBytes() {
        return maxObjectSizeBytes;
    }

    public void setMaxObjectSizeBytes(long maxObjectSizeBytes) {
        this.maxObjectSizeBytes = maxObjectSizeBytes;
    }
}
