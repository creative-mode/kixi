package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * S8: os enunciados são legíveis por todos (catálogo), mas as perguntas e opções de um enunciado usado numa prova
 * que ainda não terminou não podem ser lidas por quem não é staff do enunciado. Aplica-se só aos leitores que não são
 * staff (aluno, de qualquer escola): o dono e o administrador seguem pelos caminhos de staff, o coordenador da instituição do
 * enunciado está dispensado do embargo (lê como não-staff: sem rascunhos nem gabarito), e o aluno que faz a prova lê-a pelo
 * fluxo da sala/simulação, que não passa por aqui.
 */
public final class StatementEmbargo {

    public static final String MESSAGE =
            "Este enunciado faz parte de uma prova que ainda não terminou. As perguntas só ficam disponíveis depois de a prova terminar.";

    private StatementEmbargo() { }

    /** Como {@link #guard(StatementRepository, Statement)}, mas o coordenador da instituição do enunciado lê sempre. */
    public static Mono<Statement> guard(StatementRepository statements, Statement statement, Set<Long> coordinatedInstitutions) {
        if (coordinatedInstitutions == null || coordinatedInstitutions.isEmpty()) {
            return guard(statements, statement);
        }
        Mono<Long> institution = statements.findEffectiveInstitutionId(statement.getId());
        if (institution == null) {
            return guard(statements, statement);
        }
        return institution.map(coordinatedInstitutions::contains).defaultIfEmpty(false)
                .flatMap(coordinates -> coordinates ? Mono.just(statement) : guard(statements, statement));
    }

    /** O enunciado, ou 403 em português se estiver numa prova por terminar. */
    public static Mono<Statement> guard(StatementRepository statements, Statement statement) {
        Mono<Boolean> embargoed = statements.inUnfinishedExam(statement.getId());
        if (embargoed == null) {
            return Mono.just(statement);
        }
        return embargoed.defaultIfEmpty(false).flatMap(blocked -> blocked
                ? Mono.<Statement>error(ApiException.forbidden(MESSAGE))
                : Mono.just(statement));
    }
}
