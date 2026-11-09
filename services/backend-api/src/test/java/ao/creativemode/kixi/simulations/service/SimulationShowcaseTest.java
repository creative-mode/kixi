package ao.creativemode.kixi.simulations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.academic.model.Subject;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.shared.service.SimulationShowcaseReader.Showcase;
import ao.creativemode.kixi.simulations.dto.simulationresult.SimulationResultResponse;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** O cartão de uma prova publicada traz o cabeçalho da folha, lido do enunciado (kixi#587). */
class SimulationShowcaseTest {

    private static final long SIMULATION = 77L;
    private static final long ACCOUNT = 12L;

    private final SimulationResultService results = mock(SimulationResultService.class);
    private final SimulationRepository simulations = mock(SimulationRepository.class);
    private final StatementRepository statements = mock(StatementRepository.class);
    private final SubjectRepository subjects = mock(SubjectRepository.class);
    private final ClassRepository classes = mock(ClassRepository.class);
    private final SchoolYearRepository schoolYears = mock(SchoolYearRepository.class);
    private final SimulationShowcase showcase = new SimulationShowcase(results, simulations, statements, subjects, classes, schoolYears);

    private Statement statement() {
        Statement statement = new Statement("Exame", "Exame de Matemática");
        statement.setId(5L);
        statement.setDurationMinutes(90);
        statement.setVariant("A");
        statement.setTotalMaxScore(20.0);
        statement.setSubjectId(3L);
        statement.setClassId(8L);
        statement.setSchoolYearId(2L);
        return statement;
    }

    private void given(Statement statement) {
        when(results.findResult(SIMULATION, ACCOUNT, false)).thenReturn(Mono.just(new SimulationResultResponse(
                SIMULATION, SimulationStatus.FINISHED, 14.0, 12, 15, 1, 1800, null, List.of())));
        Simulation simulation = new Simulation();
        simulation.setId(SIMULATION);
        simulation.setStatementId(5L);
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(SIMULATION, ACCOUNT)).thenReturn(Mono.just(simulation));
        when(statements.findById(5L)).thenReturn(Mono.just(statement));
    }

    @Test
    void theCardCarriesThePaperHeaderTheServerReadFromTheStatement() {
        given(statement());
        Subject subject = new Subject();
        subject.setName("Matemática");
        when(subjects.findById(3L)).thenReturn(Mono.just(subject));
        ao.creativemode.kixi.academic.model.Class klass = new ao.creativemode.kixi.academic.model.Class();
        klass.setGrade(12);
        when(classes.findById(8L)).thenReturn(Mono.just(klass));
        SchoolYear year = new SchoolYear();
        year.setStartYear(2025);
        year.setEndYear(2026);
        when(schoolYears.findById(2L)).thenReturn(Mono.just(year));

        StepVerifier.create(showcase.showcaseOf(SIMULATION, ACCOUNT)).assertNext(card -> {
            assertThat(card.statementTitle()).isEqualTo("Exame de Matemática");
            assertThat(card.maxScore()).isEqualTo(20.0);
            assertThat(card.paperKind()).isEqualTo("Exame");
            assertThat(card.grade()).isEqualTo("12ª Classe");
            assertThat(card.subject()).isEqualTo("Matemática");
            assertThat(card.durationMinutes()).isEqualTo(90);
            assertThat(card.schoolYear()).isEqualTo("2025/2026");
            assertThat(card.variant()).isEqualTo("A");
        }).verifyComplete();
    }

    @Test
    void aStatementWithoutClassSubjectOrYearGivesNullFieldsNotAnError() {
        Statement bare = statement();
        bare.setSubjectId(null);
        bare.setClassId(null);
        bare.setSchoolYearId(null);
        bare.setVariant(null);
        given(bare);

        StepVerifier.create(showcase.showcaseOf(SIMULATION, ACCOUNT)).assertNext((Showcase card) -> {
            assertThat(card.paperKind()).isEqualTo("Exame");
            assertThat(card.grade()).isNull();
            assertThat(card.subject()).isNull();
            assertThat(card.schoolYear()).isNull();
            assertThat(card.variant()).isNull();
            assertThat(card.durationMinutes()).isEqualTo(90);
        }).verifyComplete();
    }

    @Test
    void aRecordThatHasGoneMissingLeavesOnlyThatFieldBlank() {
        given(statement());
        when(subjects.findById(3L)).thenReturn(Mono.empty());
        when(classes.findById(8L)).thenReturn(Mono.empty());
        SchoolYear open = new SchoolYear();
        open.setStartYear(2025);
        when(schoolYears.findById(2L)).thenReturn(Mono.just(open));

        StepVerifier.create(showcase.showcaseOf(SIMULATION, ACCOUNT)).assertNext(card -> {
            assertThat(card.subject()).isNull();
            assertThat(card.grade()).isNull();
            assertThat(card.schoolYear()).isEqualTo("2025");
        }).verifyComplete();
    }
}
