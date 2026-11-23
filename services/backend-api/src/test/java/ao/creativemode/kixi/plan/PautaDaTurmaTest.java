package ao.creativemode.kixi.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** O pedido da pauta da turma (kixi#627): o que o Kixi manda ao motor, montado a partir do registo escolar. */
class PautaDaTurmaTest {

    private static PautaDaTurma.Aluno aluno(long conta, Integer numero, String nome) {
        return new PautaDaTurma.Aluno(conta, numero, nome, null);
    }

    private static PautaDaTurma.Entrada nota(long conta, String valor, String situacao) {
        return new PautaDaTurma.Entrada(conta, valor == null ? null : new BigDecimal(valor), situacao);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pedido(List<PautaDaTurma.Aluno> alunos, List<PautaDaTurma.Disciplina> disciplinas) {
        return PautaDaTurma.montar(9001L, "Escola A", "EA", 9101L, "M", 12, 9201L, "2025/2026", 2, 9301L, "TLP", "Técnicas",
                alunos, disciplinas);
    }

    @Test
    void montaOCabecalhoEOsAlunosComNumero() {
        Map<String, Object> pedido = pedido(List.of(aluno(1, 1, "Ana"), aluno(2, 2, "Bruno"), aluno(3, null, "Sem número")), List.of());

        assertThat(pedido.get("contratoVersao")).isEqualTo("1");
        assertThat((Map<String, Object>) pedido.get("org")).containsEntry("sourcedId", "9001").containsEntry("sigla", "EA");
        assertThat((Map<String, Object>) pedido.get("academicSession")).containsEntry("anoLectivo", "2025/2026").containsEntry("trimestre", 2);
        assertThat((Map<String, Object>) pedido.get("turma")).containsEntry("sourcedId", "9101").containsEntry("classe", 12);
        assertThat((Map<String, Object>) pedido.get("curso")).containsEntry("sigla", "TLP");
        assertThat((List<Map<String, Object>>) pedido.get("alunos")).extracting(a -> a.get("numero")).containsExactly(1, 2);
    }

    @Test
    void osComponentesDoRegistoViramOsDoContratoNaOrdemDaFolha() {
        PautaDaTurma.Disciplina tlp = new PautaDaTurma.Disciplina(9401L, "TLP", "Técnicas", Map.of(
                "NPP", List.of(nota(1, "12.5", "FULLY_GRADED")),
                "NPT", List.of(nota(1, "14", null)),
                "MT", List.of(nota(1, "13.25", null))));
        Map<String, Object> pedido = pedido(List.of(aluno(1, 1, "Ana")), List.of(tlp));

        List<Map<String, Object>> disciplinas = (List<Map<String, Object>>) pedido.get("disciplinas");
        assertThat(disciplinas).hasSize(1);
        List<Map<String, Object>> resultados = (List<Map<String, Object>>) disciplinas.get(0).get("resultados");
        assertThat(resultados).extracting(r -> r.get("componente")).containsExactly("P1", "P2", "MD");
        List<Map<String, Object>> p1 = (List<Map<String, Object>>) resultados.get(0).get("notas");
        assertThat(p1).hasSize(1);
        assertThat(p1.get(0)).containsEntry("aluno", "1").containsEntry("estado", "FULLY_GRADED");
        assertThat(p1.get(0).get("nota")).isEqualTo(new BigDecimal("12.5"));
    }

    @Test
    void semSituacaoDoMotorAConversaoNaoInventaUmaNota() {
        PautaDaTurma.Disciplina tlp = new PautaDaTurma.Disciplina(9401L, "TLP", "Técnicas", Map.of(
                "NPP", List.of(nota(1, "10", null), nota(2, null, null))));
        Map<String, Object> pedido = pedido(List.of(aluno(1, 1, "Ana"), aluno(2, 2, "Bruno")), List.of(tlp));

        List<Map<String, Object>> notas = (List<Map<String, Object>>)
                ((List<Map<String, Object>>) ((List<Map<String, Object>>) pedido.get("disciplinas")).get(0).get("resultados")).get(0).get("notas");
        assertThat(notas.get(0)).containsEntry("estado", "FULLY_GRADED");
        assertThat(notas.get(1)).containsEntry("estado", "INCOMPLETE").doesNotContainKey("nota");
    }

    @Test
    void umaSituacaoDesconhecidaCaiNaNotaEMaisNada() {
        PautaDaTurma.Disciplina tlp = new PautaDaTurma.Disciplina(9401L, "TLP", "Técnicas", Map.of(
                "NPP", List.of(nota(1, null, "QUALQUER_COISA"))));
        Map<String, Object> pedido = pedido(List.of(aluno(1, 1, "Ana")), List.of(tlp));

        List<Map<String, Object>> notas = (List<Map<String, Object>>)
                ((List<Map<String, Object>>) ((List<Map<String, Object>>) pedido.get("disciplinas")).get(0).get("resultados")).get(0).get("notas");
        assertThat(notas.get(0)).containsEntry("estado", "INCOMPLETE");
    }

    @Test
    void umaDisciplinaSemNotasNaoEntraNaFolha() {
        PautaDaTurma.Disciplina vazia = new PautaDaTurma.Disciplina(9402L, "MAT", "Matemática", Map.of());
        Map<String, Object> pedido = pedido(List.of(aluno(1, 1, "Ana")), List.of(vazia));

        assertThat((List<?>) pedido.get("disciplinas")).isEmpty();
    }
}
