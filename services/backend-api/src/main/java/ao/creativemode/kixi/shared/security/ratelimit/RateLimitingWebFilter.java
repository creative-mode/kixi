package ao.creativemode.kixi.shared.security.ratelimit;

import io.github.bucket4j.Bucket;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
public class RateLimitingWebFilter implements WebFilter {

    private final RateLimiterService rateLimiterService;

    public RateLimitingWebFilter(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();

        // Aplicar rate limit de forma genérica em rotas sensíveis de escrita (POST)
        // Pode ser expandido ou parametrizado conforme necessário para outros módulos
        if (path.contains("/api/v1/feed/posts") && "POST".equalsIgnoreCase(method)) {
            return ReactiveSecurityContextHolder.getContext()
                    .map(securityContext -> securityContext.getAuthentication())
                    .map(Authentication::getPrincipal)
                    .cast(Long.class)
                    .defaultIfEmpty(0L)
                    .flatMap(accountId -> {
                        if (accountId == 0L) {
                            return chain.filter(exchange);
                        }

                        Bucket bucket = rateLimiterService.resolveBucket(accountId);
                        if (bucket.tryConsume(1)) {
                            return chain.filter(exchange);
                        } else {
                            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                            byte[] bytes = "{\"error\": \"Too many requests. Please try again later.\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
                            return exchange.getResponse().writeWith(Mono.just(buffer));
                        }
                    });
        }

        return chain.filter(exchange);
    }
}