package ao.creativemode.kixi.shared.config;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * As rotas sem {@code /v1} ({@code /api/simulations} e {@code /api/exam-rooms}) são cópias das de {@code /api/v1}.
 * Respondem como sempre, mas dizem que vão sair (RFC 9745 {@code Deprecation} e RFC 8594 {@code Sunset}) e deixam
 * uma linha no log por caminho e método, para se ver quem ainda as chama antes de as retirar.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LegacyApiDeprecationFilter implements WebFilter {

    /** Data a partir da qual as rotas legadas podem ser retiradas. */
    static final String SUNSET = "Sun, 31 Jan 2027 23:59:59 GMT";
    private static final Logger log = LoggerFactory.getLogger(LegacyApiDeprecationFilter.class);
    private final Set<String> alreadyLogged = ConcurrentHashMap.newKeySet();

    static boolean isLegacy(String path) {
        return path.equals("/api/simulations") || path.startsWith("/api/simulations/")
                || path.equals("/api/exam-rooms") || path.startsWith("/api/exam-rooms/");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (isLegacy(path)) {
            var headers = exchange.getResponse().getHeaders();
            headers.set("Deprecation", "true");
            headers.set("Sunset", SUNSET);
            headers.set("Link", "<" + path.replaceFirst("^/api/", "/api/v1/") + ">; rel=\"successor-version\"");
            String generic = path.replaceAll("/\\d+", "/{id}");
            if (alreadyLogged.add(exchange.getRequest().getMethod() + " " + generic)) {
                log.warn("Legacy API route in use: {} {} (use /api/v1)", exchange.getRequest().getMethod(), generic);
            }
        }
        return chain.filter(exchange);
    }
}
