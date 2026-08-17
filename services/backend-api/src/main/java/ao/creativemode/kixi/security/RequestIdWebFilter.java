package ao.creativemode.kixi.security;

import java.util.UUID;

import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/** Adds a server-generated request ID without trusting a client-supplied value. */
public class RequestIdWebFilter implements WebFilter {

    public static final String HEADER_NAME = "X-Request-ID";
    public static final String ATTRIBUTE_NAME = RequestIdWebFilter.class.getName() + ".id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = "req-" + UUID.randomUUID().toString().replace("-", "");
        exchange.getAttributes().put(ATTRIBUTE_NAME, requestId);
        exchange.getResponse().getHeaders().set(HEADER_NAME, requestId);
        return chain.filter(exchange);
    }

    public static String requestId(ServerWebExchange exchange) {
        Object requestId = exchange.getAttribute(ATTRIBUTE_NAME);
        return requestId instanceof String value ? value : "unknown";
    }
}
