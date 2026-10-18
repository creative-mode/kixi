package ao.creativemode.kixi.shared.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "app.security.rate-limit")
@Validated
public class SecurityRateLimitProperties {

    @Min(1)
    private int loginMaxAttempts = 10;

    @Min(1)
    private long loginWindowSeconds = 900;

    @Min(1)
    private int uploadMaxAttempts = 10;

    @Min(1)
    private long uploadWindowSeconds = 60;

    @Min(1)
    private int enrollmentMaxAttempts = 10;

    @Min(1)
    private long enrollmentWindowSeconds = 60;

    public int getLoginMaxAttempts() {
        return loginMaxAttempts;
    }

    public void setLoginMaxAttempts(int loginMaxAttempts) {
        this.loginMaxAttempts = loginMaxAttempts;
    }

    public long getLoginWindowSeconds() {
        return loginWindowSeconds;
    }

    public void setLoginWindowSeconds(long loginWindowSeconds) {
        this.loginWindowSeconds = loginWindowSeconds;
    }

    public int getUploadMaxAttempts() {
        return uploadMaxAttempts;
    }

    public void setUploadMaxAttempts(int uploadMaxAttempts) {
        this.uploadMaxAttempts = uploadMaxAttempts;
    }

    public long getUploadWindowSeconds() {
        return uploadWindowSeconds;
    }

    public void setUploadWindowSeconds(long uploadWindowSeconds) {
        this.uploadWindowSeconds = uploadWindowSeconds;
    }

    public int getEnrollmentMaxAttempts() {
        return enrollmentMaxAttempts;
    }

    public void setEnrollmentMaxAttempts(int enrollmentMaxAttempts) {
        this.enrollmentMaxAttempts = enrollmentMaxAttempts;
    }

    public long getEnrollmentWindowSeconds() {
        return enrollmentWindowSeconds;
    }

    public void setEnrollmentWindowSeconds(long enrollmentWindowSeconds) {
        this.enrollmentWindowSeconds = enrollmentWindowSeconds;
    }
}
