package ao.creativemode.kixi.identity.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProdJwtSecretGuardTest {

    private static JwtProperties propertiesWith(String secret) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        return properties;
    }

    @Test
    void acceptsStrongSecret() {
        assertThatCode(() -> new ProdJwtSecretGuard(
                propertiesWith("a-strong-random-secret-of-at-least-32-chars")).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingSecret() {
        assertThatThrownBy(() -> new ProdJwtSecretGuard(propertiesWith(null)).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
        assertThatThrownBy(() -> new ProdJwtSecretGuard(propertiesWith("   ")).validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new ProdJwtSecretGuard(propertiesWith("too-short")).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void rejectsPlaceholders() {
        assertThatThrownBy(() -> new ProdJwtSecretGuard(
                propertiesWith("changeme-please-replace-this-value-123")).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
        assertThatThrownBy(() -> new ProdJwtSecretGuard(
                propertiesWith("test-only-secret-that-is-at-least-32-characters")).validate())
                .isInstanceOf(IllegalStateException.class);
    }
}
