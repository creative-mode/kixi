package ao.creativemode.kixi.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** O pedido de abertura de pauta ao motor leva {@code processo} quando o aluno o tem na escola (kixi#512). */
class SchoolOpsStudentsRequestTest {

    private static Map<String, Object> row(long id, String name, String username, String process) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("username", username);
        row.put("process_number", process);
        return row;
    }

    @Test
    void processoSoViaQuandoOAlunoTemNumeroNaEscola() {
        List<Map<String, Object>> alunos = SchoolOpsPautaOpener.studentsOf(List.of(
                row(1, "Ana Domingos", "ana", "P-2026/0042"),
                row(2, "Bruno Neto", "bruno", null),
                row(3, "Carla Dias", "carla", "   ")));

        assertThat(alunos.get(0)).containsEntry("processo", "P-2026/0042").containsEntry("numero", 1).containsEntry("sourcedId", "1");
        assertThat(alunos.get(1)).as("sem número, sem a chave").doesNotContainKey("processo").containsEntry("numero", 2);
        assertThat(alunos.get(2)).as("número em branco conta como nenhum").doesNotContainKey("processo");
    }

    @Test
    void oNumeroDeOrdemSegueAOrdemDaListaEONomeCaiNoUsernameSemNome() {
        List<Map<String, Object>> alunos = SchoolOpsPautaOpener.studentsOf(List.of(row(9, "", "sem.nome", " 7 "), row(8, "Zé", "ze", null)));

        assertThat(alunos).extracting(a -> a.get("nome")).containsExactly("sem.nome", "Zé");
        assertThat(alunos).extracting(a -> a.get("numero")).containsExactly(1, 2);
        assertThat(alunos.get(0)).containsEntry("processo", "7");
    }

    @Test
    void coordenadorLevaOIdDaContaEONomeCaiNoUsername() {
        assertThat(SchoolOpsPautaOpener.coordinatorOf(row(3, "Maria Fernandes", "coord.maria", null)))
                .containsEntry("sourcedId", "3").containsEntry("nome", "Maria Fernandes");
        assertThat(SchoolOpsPautaOpener.coordinatorOf(row(4, "  ", "coord.sem.nome", null))).containsEntry("nome", "coord.sem.nome");
    }
}
