package ao.creativemode.kixi.shared.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Warns production operators when address-based rate limits cannot see client addresses. */
@Component
@Profile("prod")
public class ProdTrustedProxyWarning {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProdTrustedProxyWarning.class);

    private final TrustedProxyProperties trustedProxyProperties;

    public ProdTrustedProxyWarning(TrustedProxyProperties trustedProxyProperties) {
        this.trustedProxyProperties = trustedProxyProperties;
    }

    @PostConstruct
    void warnIfNoTrustedProxyIsConfigured() {
        if (trustedProxyProperties.getIps().isEmpty()) {
            LOGGER.warn("APP_TRUSTED_PROXY_IPS is empty in the prod profile. "
                    + "When the API is behind a proxy, address-based rate limits use the proxy address "
                    + "for every client. Configure only the direct proxy peers that connect to the API.");
        }
    }
}
