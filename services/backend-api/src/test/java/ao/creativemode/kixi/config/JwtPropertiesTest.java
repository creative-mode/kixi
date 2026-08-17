package ao.creativemode.kixi.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JwtPropertiesTest {

    @Test
    void doesNotProvideAReusableDefaultSigningSecret() {
        assertThat(new JwtProperties().getSecret()).isNull();
    }
}
