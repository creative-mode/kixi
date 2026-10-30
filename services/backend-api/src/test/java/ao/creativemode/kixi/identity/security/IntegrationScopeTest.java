package ao.creativemode.kixi.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** O que o motor pode chamar, e o que fica de fora mesmo que um administrador o pudesse. */
class IntegrationScopeTest {

    @Test
    void permiteAsLeiturasEAsEscritasDoMotor() {
        assertThat(IntegrationScope.allows(HttpMethod.GET, "/api/v1/simulations")).isTrue();
        assertThat(IntegrationScope.allows(HttpMethod.GET, "/api/v1/simulations/42/result")).isTrue();
        assertThat(IntegrationScope.allows(HttpMethod.GET, "/api/v1/statements/9/full")).isTrue();
        assertThat(IntegrationScope.allows(HttpMethod.PATCH, "/api/v1/simulation-answers/7/grade")).isTrue();
        assertThat(IntegrationScope.allows(HttpMethod.POST, "/api/v1/exam-rooms/3/close")).isTrue();
        assertThat(IntegrationScope.allows(HttpMethod.POST, "/api/v1/ocr/answer-sheets")).isTrue();
        assertThat(IntegrationScope.allows(HttpMethod.POST, "/api/v1/ocr/extract")).isFalse();
    }

    @Test
    void recusaContasInstituicoesApagarEOutrosMetodos() {
        assertThat(IntegrationScope.allows(HttpMethod.GET, "/api/v1/accounts")).isFalse();
        assertThat(IntegrationScope.allows(HttpMethod.POST, "/api/v1/accounts")).isFalse();
        assertThat(IntegrationScope.allows(HttpMethod.DELETE, "/api/v1/simulations/42")).isFalse();
        assertThat(IntegrationScope.allows(HttpMethod.DELETE, "/api/v1/simulations/42/permanent")).isFalse();
        assertThat(IntegrationScope.allows(HttpMethod.PUT, "/api/v1/statements/9")).isFalse();
        assertThat(IntegrationScope.allows(HttpMethod.GET, "/api/v1/simulations/trash")).isFalse();
        assertThat(IntegrationScope.allows(HttpMethod.GET, "/actuator/env")).isFalse();
    }

    @Test
    void naoSeEnganaComCaminhosParecidos() {
        assertThat(IntegrationScope.allows(HttpMethod.GET, "/api/v1/simulations/../accounts")).isFalse();
        assertThat(IntegrationScope.allows(HttpMethod.GET, "/api/v1/simulations/42/result/extra")).isFalse();
        assertThat(IntegrationScope.allows(HttpMethod.GET, "/api/v1/simulations/abc")).isFalse();
    }
}
