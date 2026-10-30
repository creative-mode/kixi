package ao.creativemode.kixi.identity.security;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;

/**
 * O que uma conta com o papel {@code INTEGRATION} (o motor SchoolOps) pode fazer, e nada mais. Tudo o que não
 * está aqui responde 403, mesmo que outra regra o permitisse a quem é {@code ADMIN}: é isto que torna a conta
 * do motor mais fraca do que um administrador. Ver {@code docs/authorization-matrix.md}.
 */
public final class IntegrationScope {

    public static final String ROLE = "INTEGRATION";

    private record Rule(HttpMethod method, Pattern path) {
        boolean matches(HttpMethod candidate, String candidatePath) {
            return method.equals(candidate) && path.matcher(candidatePath).matches();
        }
    }

    private static Rule rule(HttpMethod method, String regex) {
        return new Rule(method, Pattern.compile(regex));
    }

    private static final List<Rule> ALLOWED = List.of(
            // Ler: simulações, respostas, enunciados com gabarito e a estrutura académica.
            rule(HttpMethod.GET, "/api/v1/simulations"),
            rule(HttpMethod.GET, "/api/v1/simulations/\\d+"),
            rule(HttpMethod.GET, "/api/v1/simulations/\\d+/result"),
            rule(HttpMethod.GET, "/api/v1/simulation-answers"),
            rule(HttpMethod.GET, "/api/v1/simulation-answers/\\d+"),
            rule(HttpMethod.GET, "/api/v1/statements/\\d+/full"),
            rule(HttpMethod.GET, "/api/v1/statements/\\d+/questions"),
            rule(HttpMethod.GET, "/api/v1/school-years/\\d+"),
            rule(HttpMethod.GET, "/api/v1/subjects"),
            rule(HttpMethod.GET, "/api/v1/exam-rooms/owned"),
            rule(HttpMethod.GET, "/api/v1/exam-rooms/\\d+/grades"),
            rule(HttpMethod.GET, "/api/v1/analytics/classes/\\d+/students"),
            // Escrever: a pontuação das abertas e o que os fluxos de pauta precisam de criar ou fechar.
            rule(HttpMethod.PATCH, "/api/v1/simulation-answers/\\d+/grade"),
            rule(HttpMethod.POST, "/api/v1/simulations"),
            rule(HttpMethod.PUT, "/api/v1/simulations/\\d+"),
            rule(HttpMethod.POST, "/api/v1/simulations/\\d+/submit"),
            rule(HttpMethod.POST, "/api/v1/simulation-answers"),
            rule(HttpMethod.POST, "/api/v1/exam-rooms/\\d+/close"),
            // O registo escolar (kixi#501): notas por prova, reclamações e pautas.
            rule(HttpMethod.PUT, "/api/v1/school-register/exams/\\d+/grades"),
            rule(HttpMethod.GET, "/api/v1/school-register/exams/\\d+/grades"),
            rule(HttpMethod.POST, "/api/v1/school-register/exams/\\d+/appeals/[\\w.-]+"),
            rule(HttpMethod.GET, "/api/v1/school-register/exams/\\d+/appeals/pending-count"),
            rule(HttpMethod.POST, "/api/v1/school-register/exams/\\d+/appeals/[\\w.-]+/(resolve|rectify)"),
            rule(HttpMethod.PUT, "/api/v1/school-register/term-sheets/[\\w.-]+"),
            rule(HttpMethod.PUT, "/api/v1/school-register/term-sheets/[\\w.-]+/components/[A-Za-z]+"),
            rule(HttpMethod.POST, "/api/v1/school-register/term-sheets/[\\w.-]+/(close|annul)"),
            rule(HttpMethod.GET, "/api/v1/school-register/term-sheets/[\\w.-]+"));

    private IntegrationScope() {
    }

    public static boolean allows(HttpMethod method, String path) {
        return ALLOWED.stream().anyMatch(rule -> rule.matches(method, path));
    }
}
