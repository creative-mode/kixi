package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.academic.model.Subject;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.shared.service.SimulationShowcaseReader;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class SimulationShowcase implements SimulationShowcaseReader {

    private final SimulationResultService results;
    private final SimulationRepository simulations;
    private final StatementRepository statements;
    private final SubjectRepository subjects;
    private final ClassRepository classes;
    private final SchoolYearRepository schoolYears;

    public SimulationShowcase(
            SimulationResultService results, SimulationRepository simulations, StatementRepository statements,
            SubjectRepository subjects, ClassRepository classes, SchoolYearRepository schoolYears) {
        this.results = results;
        this.simulations = simulations;
        this.statements = statements;
        this.subjects = subjects;
        this.classes = classes;
        this.schoolYears = schoolYears;
    }

    /** {@code findResult} already answers 404 for another account's simulation and 409 for an unfinished one. */
    @Override
    public Mono<Showcase> showcaseOf(Long simulationId, Long accountId) {
        return results.findResult(simulationId, accountId, false).flatMap(result ->
                simulations.findByIdAndAccountIdAndDeletedAtIsNull(simulationId, accountId).flatMap(simulation ->
                        statements.findById(simulation.getStatementId()).flatMap(statement ->
                                paperHeader(statement).map(header -> new Showcase(
                                        simulationId, statement.getTitle(), result.finalScore(), statement.getTotalMaxScore(),
                                        result.correctAnswers(), result.totalQuestions(), result.timeSpentSeconds(),
                                        result.pendingReviewCount(), statement.getExamType(), header.grade(),
                                        header.subject(), statement.getDurationMinutes(), header.schoolYear(),
                                        statement.getVariant())))));
    }

    private record Header(String grade, String subject, String schoolYear) { }

    /**
     * Os nomes por trás dos ids do enunciado, escritos como o cabeçalho da folha os mostra («12ª Classe», «2025/2026»).
     * Um campo que o enunciado não tem, ou cujo registo desapareceu, fica nulo: o cartão degrada, a publicação não falha.
     */
    private Mono<Header> paperHeader(Statement statement) {
        Mono<String> grade = statement.getClassId() == null ? Mono.just("")
                : classes.findById(statement.getClassId()).mapNotNull(Class::getGrade)
                        .map(value -> value + "ª Classe").defaultIfEmpty("");
        Mono<String> subject = statement.getSubjectId() == null ? Mono.just("")
                : subjects.findById(statement.getSubjectId()).mapNotNull(Subject::getName).defaultIfEmpty("");
        Mono<String> year = statement.getSchoolYearId() == null ? Mono.just("")
                : schoolYears.findById(statement.getSchoolYearId()).map(SimulationShowcase::yearLabel).defaultIfEmpty("");
        return Mono.zip(grade, subject, year).map(tuple -> new Header(
                blankToNull(tuple.getT1()), blankToNull(tuple.getT2()), blankToNull(tuple.getT3())));
    }

    /** «2025/2026»; só o ano de início se o de fim faltar. */
    static String yearLabel(SchoolYear year) {
        if (year.getStartYear() == null) {
            return "";
        }
        return year.getEndYear() == null ? String.valueOf(year.getStartYear()) : year.getStartYear() + "/" + year.getEndYear();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
