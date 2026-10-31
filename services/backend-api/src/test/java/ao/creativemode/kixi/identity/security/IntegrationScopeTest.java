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

    /** Tudo o que o motor SchoolOps chama no Kixi (adaptadores PlataformaProvasKixi, KixiContexto, GestaoEscolarKixi e OcrFolhasKixi). */
    @Test
    void cobreTodosOsCaminhosQueOMotorUsa() {
        String[][] caminhos = {
            {"GET", "/api/v1/institutions/3"}, {"GET", "/api/v1/school-years/2"}, {"GET", "/api/v1/terms/1"}, {"GET", "/api/v1/classes/5"},
            {"GET", "/api/v1/subjects"}, {"GET", "/api/v1/teachers/me"}, {"GET", "/api/v1/exam-rooms/owned"},
            {"GET", "/api/v1/exam-rooms/3/grades"}, {"POST", "/api/v1/exam-rooms/3/close"},
            {"GET", "/api/v1/analytics/classes/5/students"}, {"GET", "/api/v1/statements/9/full"}, {"GET", "/api/v1/statements/9/questions"},
            {"GET", "/api/v1/simulations"}, {"GET", "/api/v1/simulations/4"}, {"GET", "/api/v1/simulations/4/result"},
            {"POST", "/api/v1/simulations"}, {"PUT", "/api/v1/simulations/4"}, {"POST", "/api/v1/simulations/4/submit"},
            {"GET", "/api/v1/simulation-answers"}, {"POST", "/api/v1/simulation-answers"}, {"PATCH", "/api/v1/simulation-answers/7/grade"},
            {"PUT", "/api/v1/simulation-answers/7/ai-grade"},
            {"POST", "/api/v1/ocr/answer-sheets"},
            {"PUT", "/api/v1/school-register/exams/9/grades"}, {"GET", "/api/v1/school-register/exams/9/grades"},
            {"POST", "/api/v1/school-register/exams/9/appeals/R-1"}, {"GET", "/api/v1/school-register/exams/9/appeals/pending-count"},
            {"POST", "/api/v1/school-register/exams/9/appeals/R-1/resolve"}, {"POST", "/api/v1/school-register/exams/9/appeals/R-1/rectify"},
            {"PUT", "/api/v1/school-register/term-sheets/PT-1"}, {"PUT", "/api/v1/school-register/term-sheets/PT-1/components/MAC"},
            {"POST", "/api/v1/school-register/term-sheets/PT-1/close"}, {"POST", "/api/v1/school-register/term-sheets/PT-1/annul"},
            {"GET", "/api/v1/school-register/term-sheets/PT-1"},
        };
        for (String[] caminho : caminhos) {
            assertThat(IntegrationScope.allows(HttpMethod.valueOf(caminho[0]), caminho[1]))
                .as("o motor usa %s %s", caminho[0], caminho[1]).isTrue();
        }
    }

    @Test
    void recusaAsLeiturasDeEstruturaQueNaoSaoDoMotor() {
        assertThat(IntegrationScope.allows(HttpMethod.GET, "/api/v1/institutions")).isFalse();
        assertThat(IntegrationScope.allows(HttpMethod.POST, "/api/v1/institutions/3")).isFalse();
        assertThat(IntegrationScope.allows(HttpMethod.DELETE, "/api/v1/classes/5")).isFalse();
        assertThat(IntegrationScope.allows(HttpMethod.GET, "/api/v1/teachers")).isFalse();
        assertThat(IntegrationScope.allows(HttpMethod.GET, "/api/v1/teachers/me/accounts")).isFalse();
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
