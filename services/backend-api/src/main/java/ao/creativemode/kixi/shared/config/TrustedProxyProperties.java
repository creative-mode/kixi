package ao.creativemode.kixi.shared.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The proxies whose {@code X-Forwarded-For} may be believed.
 *
 * <p>Empty by default, and empty means the header is not read at all. A header anybody
 * can write is not evidence of anything, so believing it unconditionally would let a
 * caller mint a fresh address on every attempt and walk straight through a rate limiter
 * keyed on the address.</p>
 *
 * <p>Behind a reverse proxy the situation inverts: the address the application sees is
 * the proxy's, shared by every user behind it, so a limiter keyed on it counts the whole
 * platform as one client. Listing the proxy here is what makes the header useful, and
 * listing it is a decision the operator makes with the topology in front of them.</p>
 *
 * <p>Bound to {@code APP_TRUSTED_PROXY_IPS}, comma separated, without a scheme.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.trusted-proxy")
public class TrustedProxyProperties {

    private List<String> ips = new ArrayList<>();

    public List<String> getIps() {
        return ips;
    }

    public void setIps(List<String> ips) {
        this.ips = ips == null ? new ArrayList<>() : ips;
    }
}