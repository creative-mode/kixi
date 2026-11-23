package ao.creativemode.kixi.plan;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.plan.TermPlanService.Assessment;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** As avaliações de MAC do plano vão ao motor como provas quando têm enunciado (kixi#623, schoolops#65). */
class SchoolOpsMacRequestTest {

    private static Assessment mac(long id, int seq, String title, String date, Long statementId, Long roomId, String status) {
        return new Assessment(id, "MAC", seq, title, LocalDate.parse(date), statementId, roomId, status, null, 0);
    }

    private static Assessment exam(long id, String kind, Long statementId) {
        return new Assessment(id, kind, 1, kind, LocalDate.parse("2026-02-01"), statementId, null, "PLANNED", null, 0);
    }

    @Test
    void avaliacaoComSalaEDigitalComEnunciadoSemSalaEPapel() {
        List<Map<String, Object>> pedido = SchoolOpsPautaOpener.macAssessments(List.of(
                mac(1, 1, "Trabalho 1", "2026-02-20", 21L, 101L, "PLANNED"),
                mac(2, 2, "Ficha 2", "2026-03-06", 22L, null, "PLANNED")));

        assertThat(pedido).hasSize(2);
        assertThat(pedido.get(0)).containsEntry("titulo", "Trabalho 1").containsEntry("data", "2026-02-20")
                .containsEntry("sourcedId", "21").containsEntry("modalidade", "DIGITAL");
        assertThat(pedido.get(1)).containsEntry("sourcedId", "22").containsEntry("modalidade", "PAPEL");
    }

    @Test
    void avaliacaoSemEnunciadoSegueOCaminhoAntigoSoComTituloEData() {
        List<Map<String, Object>> pedido = SchoolOpsPautaOpener.macAssessments(List.of(mac(1, 1, "Participação", "2026-02-20", null, null, "PLANNED")));

        assertThat(pedido).singleElement().satisfies(item -> {
            assertThat(item).containsOnlyKeys("titulo", "data");
            assertThat(item).containsEntry("titulo", "Participação");
        });
    }

    @Test
    void avaliacaoDispensadaNaoVaiAoMotorEAsProvasP1P2NaoEntramNaMac() {
        List<Map<String, Object>> pedido = SchoolOpsPautaOpener.macAssessments(List.of(
                exam(10, "P1", 1L), exam(11, "P2", 2L),
                mac(1, 1, "Trabalho 1", "2026-02-20", 21L, 101L, "DISPENSED"),
                mac(2, 2, "Ficha 2", "2026-03-06", 22L, 102L, "PLANNED")));

        assertThat(pedido).singleElement().satisfies(item -> assertThat(item).containsEntry("sourcedId", "22"));
    }

    @Test
    void semAvaliacoesDeMacNaoHaNada() {
        assertThat(SchoolOpsPautaOpener.macAssessments(List.of(exam(10, "P1", 1L), exam(11, "P2", 2L)))).isEmpty();
    }
}
