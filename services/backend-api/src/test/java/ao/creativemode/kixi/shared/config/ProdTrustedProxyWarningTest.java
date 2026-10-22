package ao.creativemode.kixi.shared.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ProdTrustedProxyWarningTest {

    @Test
    void warnsWhenProductionHasNoTrustedProxyConfigured(CapturedOutput output) {
        new ProdTrustedProxyWarning(new TrustedProxyProperties()).warnIfNoTrustedProxyIsConfigured();

        assertThat(output.getOut())
                .contains("APP_TRUSTED_PROXY_IPS is empty in the prod profile")
                .contains("Configure only the direct proxy peers");
    }

    @Test
    void staysQuietWhenTrustedProxyAddressesAreConfigured(CapturedOutput output) {
        TrustedProxyProperties properties = new TrustedProxyProperties();
        properties.setIps(java.util.List.of("172.20.0.5"));

        new ProdTrustedProxyWarning(properties).warnIfNoTrustedProxyIsConfigured();

        assertThat(output.getOut()).doesNotContain("APP_TRUSTED_PROXY_IPS is empty");
    }
}
