package ao.creativemode.kixi.training.service;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.training.dto.MatrixItemRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lê uma matriz de um CSV (kixi#729): linhas {@code tema;questoes;cotacao;nivel}, com separador {@code ;}, {@code ,} ou
 * tabulação (decidido linha a linha), cabeçalho opcional e linhas vazias ignoradas. Os níveis aceitam-se em português
 * ou em inglês. Cada erro leva o número da linha (a contar do cabeçalho e das linhas vazias).
 */
public final class MatrixCsvParser {

    static final int MAX_ERRORS_SHOWN = 10;
    static final int MAX_LINES = 500;

    private MatrixCsvParser() {}

    public record Parsed(List<MatrixItemRequest> items, List<String> errors) {}

    /** As linhas lidas; lança 400 com todos os erros (até 10) se alguma for inválida. */
    public static List<MatrixItemRequest> parseOrThrow(String csv) {
        Parsed parsed = parse(csv);
        if (!parsed.errors().isEmpty()) {
            List<String> shown = parsed.errors().subList(0, Math.min(MAX_ERRORS_SHOWN, parsed.errors().size()));
            String more = parsed.errors().size() > shown.size()
                    ? " (e mais " + (parsed.errors().size() - shown.size()) + " erros)" : "";
            throw ApiException.badRequest("CSV inválido: " + String.join("; ", shown) + more);
        }
        return parsed.items();
    }

    public static Parsed parse(String csv) {
        List<MatrixItemRequest> items = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            errors.add("o ficheiro está vazio");
            return new Parsed(items, errors);
        }
        if (csv.indexOf('\uFFFD') >= 0) {
            errors.add("o ficheiro deve estar em UTF-8");
            return new Parsed(items, errors);
        }
        String[] lines = csv.replace("\uFEFF", "").split("\\r?\\n|\\r", -1);
        if (lines.length > MAX_LINES) {
            errors.add("o ficheiro tem demasiadas linhas (máximo " + MAX_LINES + ")");
            return new Parsed(items, errors);
        }
        Map<String, Integer> firstLineOf = new HashMap<>();
        boolean sawFirstContent = false;
        for (int index = 0; index < lines.length; index++) {
            int lineNumber = index + 1;
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            List<String> fields = split(line);
            boolean first = !sawFirstContent;
            sawFirstContent = true;
            if (first && isHeader(fields)) {
                continue;
            }
            if (fields.size() < 2) {
                errors.add("Linha " + lineNumber + ": esperadas pelo menos 2 colunas (tema;questões)");
                continue;
            }
            String topic = fields.get(0);
            if (topic.isBlank()) {
                errors.add("Linha " + lineNumber + ": tema em falta");
                continue;
            }
            Integer count = parseCount(fields.get(1));
            if (count == null) {
                errors.add("Linha " + lineNumber + ": número de questões inválido «" + fields.get(1) + "»");
                continue;
            }
            Double points = null;
            if (fields.size() > 2 && !fields.get(2).isBlank()) {
                points = parsePoints(fields.get(2));
                if (points == null) {
                    errors.add("Linha " + lineNumber + ": cotação inválida «" + fields.get(2) + "»");
                    continue;
                }
            }
            String level = null;
            if (fields.size() > 3 && !fields.get(3).isBlank()) {
                if (CognitiveLevel.parse(fields.get(3)).isEmpty()) {
                    errors.add("Linha " + lineNumber + ": nível cognitivo desconhecido «" + fields.get(3)
                            + "» (use conhecimento, compreensão, aplicação ou análise)");
                    continue;
                }
                level = fields.get(3);
            }
            String slug = TopicText.slug(topic);
            Integer earlier = firstLineOf.putIfAbsent(slug, lineNumber);
            if (earlier != null) {
                errors.add("Linha " + lineNumber + ": o tema «" + topic + "» já apareceu na linha " + earlier);
                continue;
            }
            items.add(new MatrixItemRequest(null, topic, count, points, level, null));
        }
        if (items.isEmpty() && errors.isEmpty()) {
            errors.add("o ficheiro não tem linhas de temas");
        }
        return new Parsed(items, errors);
    }

    /** {@code ;} manda, depois tabulação, por fim vírgula. */
    private static List<String> split(String line) {
        String separator = line.indexOf(';') >= 0 ? ";" : line.indexOf('\t') >= 0 ? "\t" : ",";
        List<String> fields = new ArrayList<>();
        for (String raw : line.split(java.util.regex.Pattern.quote(separator), -1)) {
            String field = raw.trim();
            if (field.length() >= 2 && field.startsWith("\"") && field.endsWith("\"")) {
                field = field.substring(1, field.length() - 1).trim();
            }
            fields.add(field);
        }
        return fields;
    }

    private static final java.util.Set<String> HEADER_LABELS = java.util.Set.of(
            "tema", "temas", "conteudo", "conteudos", "unidade", "unidades", "unidade tematica", "unidades tematicas",
            "assunto", "assuntos", "topic", "topics", "theme", "themes", "unit", "content");

    /**
     * A primeira linha é cabeçalho só quando a 1.ª coluna é exatamente um rótulo («Tema», «Unidade temática», ...) e
     * nenhuma das colunas 2 e 3 é um número. Uma linha de dados («Unidade 1: Funções;3», «Tema;abc;8») nunca se perde
     * em silêncio como cabeçalho: dá erro.
     */
    private static boolean isHeader(List<String> fields) {
        if (!HEADER_LABELS.contains(TopicText.normalize(fields.get(0)))) {
            return false;
        }
        for (int column = 1; column <= 2 && column < fields.size(); column++) {
            String text = fields.get(column);
            if (!text.isBlank() && isNumber(text)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNumber(String text) {
        try {
            new BigDecimal(text.trim().replace(',', '.'));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Integer parseCount(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value >= 1 && value <= MatrixValidator.MAX_QUESTIONS_PER_TOPIC ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parsePoints(String text) {
        try {
            BigDecimal value = new BigDecimal(text.trim().replace(',', '.'));
            return value.signum() < 0 || value.compareTo(MatrixValidator.MAX_POINTS) > 0 ? null : value.doubleValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
