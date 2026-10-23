package ao.creativemode.kixi.shared.security;

import ao.creativemode.kixi.shared.config.TrustedProxyProperties;
import java.net.InetSocketAddress;
import org.springframework.web.server.ServerWebExchange;

/**
 * The address a request is counted against (login #123, registration, donations #252).
 *
 * <p>{@code X-Forwarded-For} is read only when the peer that sent it is listed in
 * {@link TrustedProxyProperties}. That condition is the whole design and it cuts both ways:
 * believing the header unconditionally lets any caller invent a fresh address per attempt, while
 * ignoring it behind a real proxy makes every user on the platform share one bucket. So the default
 * is to believe nothing, and the operator names the proxy when the topology makes the header the
 * honest answer.</p>
 *
 * <p>Only the rightmost entry is used. A trusted direct peer must not let an earlier, client-supplied
 * value in a forwarded chain choose the rate-limit bucket. An empty final entry falls back to the
 * peer rather than keying everyone on "".</p>
 *
 * <p>Extracted from {@code AuthController} without changing what it answers.</p>
 */
public final class ClientAddress {

    private ClientAddress() {
    }

    public static String resolve(ServerWebExchange exchange, TrustedProxyProperties trustedProxies) {
        if (exchange == null) {
            return "unknown";
        }
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }
        String peer = remoteAddress.getAddress().getHostAddress();
        if (!trustedProxies.getIps().contains(peer)) {
            return peer;
        }
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded == null) {
            return peer;
        }
        String[] hops = forwarded.split(",", -1);
        String last = hops[hops.length - 1].trim();
        return last.isEmpty() ? peer : last;
    }
}
