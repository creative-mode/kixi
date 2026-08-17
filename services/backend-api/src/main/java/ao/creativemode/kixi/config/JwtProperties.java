package ao.creativemode.kixi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.stereotype.Component;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Component
@ConfigurationProperties(prefix = "app.jwt")
@Validated
public class JwtProperties {

    @NotBlank(message = "app.jwt.secret must be configured")
    @Size(min = 32, message = "app.jwt.secret must contain at least 32 characters")
    private String secret;

    @Min(value = 60000, message = "app.jwt.expiration-ms must be at least 60000 milliseconds")
    private long expirationMs = 86400000L; // 24 hours

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public void setExpirationMs(long expirationMs) {
        this.expirationMs = expirationMs;
    }
}
