package ao.creativemode.kixi.identity.config;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * O 403 da árvore {@code /api/v1/moderation/**} (kixi#591) leva o motivo no corpo, no mesmo formato dos outros erros
 * (title, status, detail), para o cliente o poder mostrar em vez de um «sem permissão» genérico. Fora dessa árvore
 * devolve o mesmo 403 sem corpo de sempre.
 */
final class ModerationAccessDeniedHandler implements ServerAccessDeniedHandler {

    static final String REASON = "Só o administrador gere a moderação: a fila de denúncias, a decisão e a auditoria.";

    private final ServerAccessDeniedHandler fallback;

    ModerationAccessDeniedHandler(ServerAccessDeniedHandler fallback) {
        this.fallback = fallback;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        String path = exchange.getRequest().getPath().value();
        if (!path.equals("/api/v1/moderation") && !path.startsWith("/api/v1/moderation/")) {
            return fallback.handle(exchange, denied);
        }
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String body = "{\"title\":\"Forbidden\",\"status\":403,\"detail\":\"" + REASON + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
