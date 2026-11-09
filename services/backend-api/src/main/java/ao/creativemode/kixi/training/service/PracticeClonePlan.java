package ao.creativemode.kixi.training.service;

import ao.creativemode.kixi.exams.model.Question;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A ordem e a numeração das questões copiadas para o enunciado virtual de um treino (kixi#729), sem base de dados.
 *
 * <p>As questões chegam pela ordem dos temas. Uma questão solta fica no seu lugar; as alíneas de um grupo vêm todas juntas
 * a seguir ao cabeçalho do grupo (copiado uma só vez, no sítio da primeira alínea escolhida), com o enunciado comum.
 * Renumera-se: 1, 2, 3... para as questões e grupos, e {@code grupo * 1000 + k} para as alíneas. Uma alínea cujo cabeçalho não se encontra passa a questão solta.</p>
 */
public final class PracticeClonePlan {

    private PracticeClonePlan() {}

    /** Uma cópia a fazer. {@code parentSourceId} é a questão de origem do cabeçalho do grupo, ou nulo. */
    public record Step(Question source, Long parentSourceId, int number, int orderIndex, String label) {}

    public static List<Step> build(List<Long> chosenInOrder, Map<Long, Question> sources) {
        // Um bloco por questão solta ou por grupo, pela ordem do primeiro aparecimento.
        Map<Long, List<Question>> blocks = new LinkedHashMap<>();
        Map<Long, Question> headers = new LinkedHashMap<>();
        for (Long id : chosenInOrder) {
            Question question = sources.get(id);
            if (question == null) {
                continue;
            }
            Question parent = question.getParentId() == null ? null : sources.get(question.getParentId());
            if (parent == null) {
                blocks.computeIfAbsent(question.getId(), key -> new ArrayList<>());
            } else {
                headers.putIfAbsent(parent.getId(), parent);
                blocks.computeIfAbsent(parent.getId(), key -> new ArrayList<>()).add(question);
            }
        }
        List<Step> steps = new ArrayList<>();
        int number = 0;
        int order = 0;
        for (Map.Entry<Long, List<Question>> block : blocks.entrySet()) {
            number++;
            Question header = headers.get(block.getKey());
            if (header == null) {
                Question alone = sources.get(block.getKey());
                steps.add(new Step(alone, null, number, ++order, null));
                continue;
            }
            steps.add(new Step(header, null, number, ++order, null));
            List<Question> items = new ArrayList<>(block.getValue());
            items.sort(Comparator.comparing((Question q) -> q.getOrderIndex() == null ? Integer.MAX_VALUE : q.getOrderIndex())
                    .thenComparing(q -> q.getNumber() == null ? Integer.MAX_VALUE : q.getNumber()));
            for (int k = 0; k < items.size(); k++) {
                Question item = items.get(k);
                String label = item.getLabel() != null && !item.getLabel().isBlank() ? item.getLabel() : letter(k);
                steps.add(new Step(item, header.getId(), number * 1000 + k + 1, ++order, label));
            }
        }
        return steps;
    }

    private static String letter(int index) {
        return String.valueOf((char) ('a' + Math.min(index, 25)));
    }
}
