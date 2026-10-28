package ao.creativemode.kixi.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.identity.config.JwtProperties;
import ao.creativemode.kixi.identity.security.AccountSessionGate.Kind;
import ao.creativemode.kixi.identity.service.JwtService;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** O filtro só autentica quem o gate deixa, e só com os papéis que a conta ainda tem (kixi#450). */
class JwtAuthenticationFilterTest {

    private final JwtProperties properties = new JwtProperties();
    private final JwtService jwt;
    private final AccountSessionGate gate = mock(AccountSessionGate.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AccountSessionGate> provider = mock(ObjectProvider.class);

    JwtAuthenticationFilterTest() {
        properties.setSecret("segredo-de-teste-com-pelo-menos-32-caracteres!");
        jwt = new JwtService(properties);
        when(provider.getIfAvailable()).thenReturn(gate);
    }

    /** Corre o filtro com um token e devolve a autenticação que a cadeia viu, ou null. */
    private Authentication correr(JwtAuthenticationFilter filtro, String token) {
        MockServerHttpRequest.BaseBuilder<?> pedido = MockServerHttpRequest.get("/api/v1/x");
        if (token != null) pedido.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        MockServerWebExchange exchange = MockServerWebExchange.from(pedido);
        AtomicReference<Authentication> vista = new AtomicReference<>();
        WebFilterChain cadeia = ex -> ReactiveSecurityContextHolder.getContext()
                .doOnNext(ctx -> vista.set(ctx.getAuthentication()))
                .then();
        filtro.filter(exchange, cadeia).block();
        return vista.get();
    }

    private static AccountSessionGate.Verdict veredicto(Kind tipo, String... papeis) {
        return switch (tipo) {
            case VALID -> AccountSessionGate.Verdict.valid(Set.of(papeis));
            case REVOKED -> AccountSessionGate.Verdict.REVOKED;
            case UNKNOWN -> AccountSessionGate.Verdict.UNKNOWN;
        };
    }

    private String tokenDe(String... papeis) {
        return jwt.generateToken(7L, List.of(papeis));
    }

    @Test
    void umaContaValidaAutenticaComOsPapeisDoToken() {
        when(gate.verdict(7L)).thenReturn(Mono.just(veredicto(Kind.VALID, "STUDENT")));

        Authentication auth = correr(new JwtAuthenticationFilter(jwt, provider), tokenDe("STUDENT"));

        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("7");
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_STUDENT");
    }

    @Test
    void umaContaDesactivadaOuApagadaNaoAutenticaMesmoComUmTokenValido() {
        when(gate.verdict(7L)).thenReturn(Mono.just(veredicto(Kind.REVOKED)));

        assertThat(correr(new JwtAuthenticationFilter(jwt, provider), tokenDe("ADMIN"))).isNull();
    }

    @Test
    void umPapelRetiradoDeixaDeValerMasOsQueSobramContinuam() {
        when(gate.verdict(7L)).thenReturn(Mono.just(veredicto(Kind.VALID, "TEACHER")));

        Authentication auth = correr(new JwtAuthenticationFilter(jwt, provider), tokenDe("ADMIN", "TEACHER"));

        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_TEACHER");
    }

    @Test
    void seAContaJaNaoTemNenhumDosPapeisDoTokenNaoAutentica() {
        when(gate.verdict(7L)).thenReturn(Mono.just(veredicto(Kind.VALID, "STUDENT")));

        assertThat(correr(new JwtAuthenticationFilter(jwt, provider), tokenDe("ADMIN"))).isNull();
    }

    @Test
    void umPapelNovoNaBaseNaoEntraNumTokenJaEmitido() {
        when(gate.verdict(7L)).thenReturn(Mono.just(veredicto(Kind.VALID, "STUDENT", "ADMIN")));

        Authentication auth = correr(new JwtAuthenticationFilter(jwt, provider), tokenDe("STUDENT"));

        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_STUDENT");
    }

    @Test
    void comABaseSemResponderConfiaNoTokenComoAntes() {
        when(gate.verdict(7L)).thenReturn(Mono.just(veredicto(Kind.UNKNOWN)));

        Authentication auth = correr(new JwtAuthenticationFilter(jwt, provider), tokenDe("STUDENT"));

        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_STUDENT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void semGateNoContextoOFiltroConfiaNoToken() {
        ObjectProvider<AccountSessionGate> vazio = mock(ObjectProvider.class);
        when(vazio.getIfAvailable()).thenReturn(null);

        Authentication auth = correr(new JwtAuthenticationFilter(jwt, vazio), tokenDe("STUDENT"));

        assertThat(auth).isNotNull();
    }

    @Test
    void tokensInvalidosOuSemTokenNaoAutenticam() {
        JwtAuthenticationFilter filtro = new JwtAuthenticationFilter(jwt, provider);

        assertThat(correr(filtro, null)).isNull();
        assertThat(correr(filtro, "isto.nao.e-um-jwt")).isNull();
        assertThat(correr(filtro, tokenDe("STUDENT") + "x")).isNull(); // assinatura adulterada
    }
}
