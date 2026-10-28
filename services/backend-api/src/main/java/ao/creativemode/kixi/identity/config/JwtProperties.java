package ao.creativemode.kixi.identity.config;

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

    /**
     * Quanto tempo se confia no estado da conta antes de o voltar a ler (kixi#450). O token vive 24 h, mas uma conta desactivada ou apagada, ou
     * um papel retirado, passa a valer em segundos e não em horas: no máximo de {@code revalidationMs} em {@code revalidationMs} confirma-se na
     * base. 0 desliga a verificação (o token volta a valer sozinho até expirar).
     */
    @Min(value = 0, message = "app.jwt.revalidation-ms cannot be negative")
    private long revalidationMs = 30_000L;

    public long getRevalidationMs() {
        return revalidationMs;
    }

    public void setRevalidationMs(long revalidationMs) {
        this.revalidationMs = revalidationMs;
    }

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
