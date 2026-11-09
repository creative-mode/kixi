package ao.creativemode.kixi.training.service;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.training.dto.MatrixItemRequest;
import ao.creativemode.kixi.training.dto.MatrixRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * As regras de uma matriz de prova (kixi#729), sem base de dados: pelo menos um tema, sem temas repetidos, cada tema com
 * pelo menos uma questão e, se houver cotações, a sua soma tem de igualar o total da prova (tolerância de 0,01).
 */
public final class MatrixValidator {

    static final BigDecimal DEFAULT_TOTAL = BigDecimal.valueOf(20);
    static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private MatrixValidator() {}

    /** Um tema já validado, com o nível lido e os tipos de item limpos. */
    public record Item(Long topicId, String topicName, int questionCount, BigDecimal points,
            CognitiveLevel level, List<String> questionTypes) {
        /** A chave com que se compara a repetição: o id, ou o nome sem acentos. */
        String key() {
            return topicId != null ? "id:" + topicId : "name:" + TopicText.slug(topicName);
        }

        String label() {
            return topicName != null && !topicName.isBlank() ? topicName.trim() : "#" + topicId;
        }
    }

    public record Validated(String title, BigDecimal totalPoints, List<Item> items) {}

    public static Validated validate(MatrixRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw ApiException.badRequest("A matriz precisa de pelo menos um tema.");
        }
        BigDecimal total = request.totalPoints() == null ? DEFAULT_TOTAL : decimal(request.totalPoints());
        if (total.signum() <= 0) {
            throw ApiException.badRequest("O total de pontos da prova tem de ser positivo.");
        }
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MatrixItemRequest raw : request.items()) {
            if (raw == null) {
                throw ApiException.badRequest("A matriz tem um tema vazio.");
            }
            Item item = item(raw);
            if (!seen.add(item.key())) {
                throw ApiException.badRequest("O tema «" + item.label() + "» aparece repetido na matriz.");
            }
            items.add(item);
        }
        checkSum(items, total);
        String title = request.title() == null || request.title().isBlank() ? null : request.title().trim();
        if (title != null && title.length() > 200) {
            throw ApiException.badRequest("O título da matriz não pode passar de 200 caracteres.");
        }
        return new Validated(title, total, List.copyOf(items));
    }

    private static Item item(MatrixItemRequest raw) {
        boolean hasName = raw.topicName() != null && !raw.topicName().isBlank();
        if (raw.topicId() == null && !hasName) {
            throw ApiException.badRequest("Cada tema da matriz precisa de um topicId ou de um topicName.");
        }
        String label = hasName ? raw.topicName().trim() : "#" + raw.topicId();
        if (raw.questionCount() == null || raw.questionCount() < 1) {
            throw ApiException.badRequest("O tema «" + label + "» precisa de pelo menos uma questão.");
        }
        BigDecimal points = null;
        if (raw.points() != null) {
            points = decimal(raw.points());
            if (points.signum() < 0) {
                throw ApiException.badRequest("A cotação do tema «" + label + "» não pode ser negativa.");
            }
        }
        CognitiveLevel level = null;
        if (raw.cognitiveLevel() != null && !raw.cognitiveLevel().isBlank()) {
            level = CognitiveLevel.parse(raw.cognitiveLevel()).orElseThrow(() -> ApiException.badRequest(
                    "Nível cognitivo desconhecido «" + raw.cognitiveLevel().trim() + "» no tema «" + label
                            + "»; use conhecimento, compreensão, aplicação ou análise."));
        }
        List<String> types = raw.questionTypes() == null ? List.of() : raw.questionTypes().stream()
                .filter(type -> type != null && !type.isBlank())
                .map(String::trim)
                .toList();
        return new Item(raw.topicId(), hasName ? raw.topicName().trim() : null, raw.questionCount(), points, level, types);
    }

    /** Se houver cotações, a soma tem de bater com o total (os temas sem cotação contam zero). */
    static void checkSum(List<Item> items, BigDecimal total) {
        if (items.stream().noneMatch(item -> item.points() != null)) {
            return;
        }
        BigDecimal sum = items.stream().map(Item::points).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.subtract(total).abs().compareTo(TOLERANCE) > 0) {
            throw ApiException.badRequest("A soma das cotações (" + format(sum) + ") tem de ser igual ao total da prova ("
                    + format(total) + ").");
        }
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value);
    }

    /** Número com vírgula decimal, como se escreve em português: 17,5. */
    static String format(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',');
    }
}
