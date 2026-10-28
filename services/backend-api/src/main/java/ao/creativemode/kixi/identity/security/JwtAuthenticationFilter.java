package ao.creativemode.kixi.identity.security;

import ao.creativemode.kixi.identity.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class JwtAuthenticationFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final ObjectProvider<AccountSessionGate> gate;

    /**
     * O {@code gate} é opcional de propósito: os testes de controlador montam só uma fatia do contexto, sem repositórios, e aí o filtro
     * comporta-se como antes (confia no token). Em produção o gate existe e confirma a conta (kixi#450).
     */
    @Autowired
    public JwtAuthenticationFilter(JwtService jwtService, ObjectProvider<AccountSessionGate> gate) {
        this.jwtService = jwtService;
        this.gate = gate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return chain.filter(exchange);
        }
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return chain.filter(exchange);
        }
        Long accountId;
        List<String> tokenRoles;
        try {
            Claims claims = jwtService.parseToken(token);
            accountId = jwtService.getAccountId(claims);
            tokenRoles = jwtService.getRoles(claims);
        } catch (JwtException | IllegalArgumentException e) {
            return chain.filter(exchange); // assinatura inválida, expirado ou assunto que não é um número: sem autenticação
        }
        AccountSessionGate sessionGate = gate.getIfAvailable();
        Mono<AccountSessionGate.Verdict> verdict = sessionGate == null
                ? Mono.just(AccountSessionGate.Verdict.UNKNOWN)
                : sessionGate.verdict(accountId);
        return verdict.flatMap(v -> switch (v.kind()) {
            // Conta desactivada ou apagada: o token ainda é válido, mas já não vale nada.
            case REVOKED -> chain.filter(exchange);
            case VALID -> {
                // Só valem os papéis do token que a conta ainda tem; um papel retirado deixa de valer em segundos.
                List<String> kept = tokenRoles.stream().filter(r -> v.roles().contains(AccountSessionGate.normalize(r))).toList();
                yield kept.isEmpty() ? chain.filter(exchange) : authenticate(exchange, chain, accountId, kept);
            }
            case UNKNOWN -> authenticate(exchange, chain, accountId, tokenRoles);
        });
    }

    private Mono<Void> authenticate(ServerWebExchange exchange, WebFilterChain chain, Long accountId, List<String> roles) {
        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .collect(Collectors.toList());
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(accountId.toString(), null, authorities);
        return chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }
}
