package ao.creativemode.kixi.plan;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monta o pedido da pauta da turma ({@code POST /v1/pautas-turma} do motor) a partir do que o Kixi guarda no registo
 * escolar (kixi#627): a turma e o curso do plano, os alunos com número, e por disciplina as notas de cada componente.
 *
 * <p>O motor já sabe desenhar a folha; o Kixi só tem de lhe dizer o que lá vai. O registo do Kixi guarda os componentes
 * como o motor os chama nas pautas (NPP, NPT, MAC, MT); o contrato da pauta da turma usa P1, P2, MAC e MD.
 */
public final class PautaDaTurma {

    /** NPP/NPT/MAC/MT (registo do Kixi) → P1/P2/MAC/MD (contrato). */
    static final Map<String, String> COMPONENTES = Map.of("NPP", "P1", "NPT", "P2", "MAC", "MAC", "MT", "MD");
    private static final List<String> ORDEM = List.of("P1", "P2", "MAC", "MD");
    private static final List<String> ESTADOS = List.of("FULLY_GRADED", "PENDING", "PENDING_MANUAL", "ABSENT", "INCOMPLETE");

    private PautaDaTurma() {
    }

    /** Uma linha do registo: a nota de um aluno num componente, e a situação que o motor devolveu. */
    public record Entrada(long accountId, BigDecimal nota, String situacao) {
    }

    /** Uma disciplina da pauta: as entradas por componente, tal como vieram do registo (NPP, NPT, MAC, MT). */
    public record Disciplina(long subjectId, String sigla, String nome, Map<String, List<Entrada>> porComponente) {
    }

    /** Um aluno da turma, com o número que o motor usou na pauta. */
    public record Aluno(long accountId, Integer numero, String nome, String processo) {
    }

    public static Map<String, Object> montar(long institutionId, String institutionName, String institutionCode,
            long classId, String className, int grade, long schoolYearId, String anoLectivo, int trimestre,
            long courseId, String courseCode, String courseName, List<Aluno> alunos, List<Disciplina> disciplinas) {
        Map<String, Object> pedido = new LinkedHashMap<>();
        pedido.put("contratoVersao", "1");
        pedido.put("org", Map.of("sourcedId", String.valueOf(institutionId), "nome", institutionName, "sigla", institutionCode));
        pedido.put("academicSession", Map.of("sourcedId", String.valueOf(schoolYearId), "anoLectivo", anoLectivo, "trimestre", trimestre));
        pedido.put("turma", Map.of("sourcedId", String.valueOf(classId), "nome", className, "classe", grade));
        pedido.put("curso", referencia(courseId, courseCode, courseName));

        List<Map<String, Object>> alunosDoPedido = new ArrayList<>();
        for (Aluno aluno : alunos) {
            if (aluno.numero() == null || aluno.numero() < 1) {
                continue; // sem número não há linha na folha: o contrato exige-o
            }
            Map<String, Object> linha = new LinkedHashMap<>();
            linha.put("sourcedId", String.valueOf(aluno.accountId()));
            linha.put("numero", aluno.numero());
            linha.put("nome", aluno.nome() == null || aluno.nome().isBlank() ? "Aluno " + aluno.numero() : aluno.nome());
            if (aluno.processo() != null && !aluno.processo().isBlank()) {
                linha.put("processo", aluno.processo());
            }
            alunosDoPedido.add(linha);
        }
        pedido.put("alunos", alunosDoPedido);
        pedido.put("disciplinas", disciplinasDoPedido(disciplinas));
        return pedido;
    }

    private static List<Map<String, Object>> disciplinasDoPedido(List<Disciplina> disciplinas) {
        List<Map<String, Object>> lista = new ArrayList<>();
        for (Disciplina disciplina : disciplinas) {
            Map<String, List<Entrada>> porComponente = disciplina.porComponente();
            if (porComponente == null || porComponente.isEmpty()) {
                continue; // disciplina sem notas não entra: a folha imprime o que recebe
            }
            List<Map<String, Object>> resultados = new ArrayList<>();
            for (String componente : ORDEM) {
                List<Entrada> entradas = porComponente.entrySet().stream()
                        .filter(e -> componente.equals(COMPONENTES.get(e.getKey())))
                        .map(Map.Entry::getValue).findFirst().orElse(List.of());
                if (entradas.isEmpty()) {
                    continue;
                }
                List<Map<String, Object>> notas = new ArrayList<>();
                for (Entrada entrada : entradas) {
                    Map<String, Object> nota = new LinkedHashMap<>();
                    nota.put("aluno", String.valueOf(entrada.accountId()));
                    if (entrada.nota() != null) {
                        nota.put("nota", entrada.nota());
                    }
                    nota.put("estado", estado(entrada));
                    notas.add(nota);
                }
                resultados.add(Map.of("componente", componente, "notas", notas));
            }
            if (resultados.isEmpty()) {
                continue;
            }
            Map<String, Object> linha = new LinkedHashMap<>();
            linha.put("disciplina", referencia(disciplina.subjectId(), disciplina.sigla(), disciplina.nome()));
            linha.put("resultados", resultados);
            lista.add(linha);
        }
        return lista;
    }

    /** A situação do motor quando é uma das do contrato; senão, a nota decide (sem nota é INCOMPLETE, não ABSENT). */
    private static String estado(Entrada entrada) {
        String situacao = entrada.situacao() == null ? "" : entrada.situacao().trim().toUpperCase();
        if (ESTADOS.contains(situacao)) {
            return situacao;
        }
        return entrada.nota() == null ? "INCOMPLETE" : "FULLY_GRADED";
    }

    private static Map<String, Object> referencia(long sourcedId, String sigla, String nome) {
        Map<String, Object> referencia = new LinkedHashMap<>();
        referencia.put("sourcedId", String.valueOf(sourcedId));
        referencia.put("sigla", sigla == null || sigla.isBlank() ? "N/D" : sigla);
        referencia.put("nome", nome == null || nome.isBlank() ? "Sem nome" : nome);
        return referencia;
    }
}
