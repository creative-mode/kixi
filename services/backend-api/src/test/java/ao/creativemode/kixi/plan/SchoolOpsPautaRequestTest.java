package ao.creativemode.kixi.plan;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.plan.TermPlanService.Assessment;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** As provas que seguem no pedido da pauta: só as não dispensadas, pelo menos uma (kixi#635, schoolops#46). */
class SchoolOpsPautaRequestTest {

    private static Assessment exam(long id, String kind, Long statementId, String status) {
        return new Assessment(id, kind, 1, kind, LocalDate.parse("2026-02-01"), statementId, null, status, null, 0);
    }

    private static List<String> kinds(List<Assessment> exams) {
        return exams.stream().map(Assessment::kind).toList();
    }

    @Test
    void comAsDuasProvasSegueMasDuas() {
        List<Assessment> exams = SchoolOpsPautaOpener.examsToSend(List.of(
                exam(1, "P1", 11L, "STATEMENT_READY"), exam(2, "P2", 12L, "STATEMENT_READY")));

        assertThat(kinds(exams)).containsExactly("P1", "P2");
    }

    @Test
    void comAP2DispensadaSegueSoAP1() {
        List<Assessment> exams = SchoolOpsPautaOpener.examsToSend(List.of(
                exam(1, "P1", 11L, "STATEMENT_READY"), exam(2, "P2", null, "DISPENSED")));

        assertThat(kinds(exams)).containsExactly("P1");
    }

    @Test
    void comAP1DispensadaSegueSoAP2() {
        List<Assessment> exams = SchoolOpsPautaOpener.examsToSend(List.of(
                exam(1, "P1", null, "DISPENSED"), exam(2, "P2", 12L, "STATEMENT_READY")));

        assertThat(kinds(exams)).containsExactly("P2");
    }

    @Test
    void comAsDuasDispensadasNaoHaPautaParaAbrir() {
        assertThat(SchoolOpsPautaOpener.examsToSend(List.of(
                exam(1, "P1", null, "DISPENSED"), exam(2, "P2", null, "DISPENSED")))).isEmpty();
    }

    @Test
    void provaPorDispensarSemEnunciadoFazAPautaEsperar() {
        assertThat(SchoolOpsPautaOpener.examsToSend(List.of(
                exam(1, "P1", 11L, "STATEMENT_READY"), exam(2, "P2", null, "PLANNED")))).isEmpty();
        assertThat(SchoolOpsPautaOpener.examsToSend(List.of(exam(1, "P1", 11L, "STATEMENT_READY")))).isEmpty();
    }

    @Test
    void asAvaliacoesDeMacNaoSaoProvasDaPauta() {
        List<Assessment> exams = SchoolOpsPautaOpener.examsToSend(List.of(
                exam(1, "P1", 11L, "STATEMENT_READY"), exam(2, "P2", 12L, "STATEMENT_READY"), exam(3, "MAC", 13L, "STATEMENT_READY")));

        assertThat(kinds(exams)).containsExactly("P1", "P2");
    }
}
