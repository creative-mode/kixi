package ao.creativemode.kixi.training.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Reparte as questões de um treino por tema (kixi#729), sem base de dados.
 *
 * <ul>
 *   <li>Preferem-se as questões que o aluno nunca tentou, depois as que errou, por fim as que já acertou.</li>
 *   <li>Dentro de cada grupo diversifica-se por enunciado (round-robin entre enunciados, os mais recentes primeiro),
 *       para o treino não vir todo da mesma prova.</li>
 *   <li>Uma questão não se repete entre temas (pode ter vários temas): os temas com menos escolha escolhem primeiro.</li>
 *   <li>Se faltarem questões dá as que há: {@code provided < requested}, nunca um erro.</li>
 * </ul>
 */
public final class PracticeAllocator {

    private PracticeAllocator() {}

    /** Uma questão que se pode dar ao aluno, com o enunciado e o ano de onde vem e o que o aluno já fez dela. */
    public record Candidate(long questionId, long statementId, int year, boolean attempted, boolean correct) {
        int tier() {
            return !attempted ? 0 : correct ? 2 : 1;
        }
    }

    public record Request(long topicId, int count) {}

    /** As questões dadas num tema, por ordem de escolha. */
    public record Allocation(long topicId, int requested, List<Long> questionIds) {
        public int provided() {
            return questionIds.size();
        }
    }

    /** O resultado por tema, na ordem dos pedidos. */
    public static List<Allocation> allocate(List<Request> requests, Map<Long, List<Candidate>> pool, Random random) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            order.add(i);
        }
        // Quem tem menos escolha escolhe primeiro; a ordem do pedido desempata.
        order.sort(Comparator.comparingInt((Integer i) -> distinctSize(pool.get(requests.get(i).topicId()))).thenComparingInt(i -> i));

        Set<Long> used = new HashSet<>();
        Allocation[] result = new Allocation[requests.size()];
        for (int index : order) {
            Request request = requests.get(index);
            List<Candidate> available = pool.getOrDefault(request.topicId(), List.of()).stream()
                    .filter(candidate -> !used.contains(candidate.questionId()))
                    .toList();
            List<Long> chosen = pick(available, request.count(), random);
            used.addAll(chosen);
            result[index] = new Allocation(request.topicId(), request.count(), List.copyOf(chosen));
        }
        return List.of(result);
    }

    private static int distinctSize(List<Candidate> candidates) {
        return candidates == null ? 0 : (int) candidates.stream().map(Candidate::questionId).distinct().count();
    }

    private static List<Long> pick(List<Candidate> available, int count, Random random) {
        List<Long> chosen = new ArrayList<>();
        for (int tier = 0; tier <= 2 && chosen.size() < count; tier++) {
            final int wanted = tier;
            List<Candidate> inTier = available.stream().filter(candidate -> candidate.tier() == wanted).toList();
            chosen.addAll(roundRobin(inTier, count - chosen.size(), random));
        }
        return chosen;
    }

    /** Uma questão de cada enunciado à vez, os enunciados mais recentes primeiro. */
    private static List<Long> roundRobin(List<Candidate> candidates, int need, Random random) {
        Map<Long, List<Candidate>> byStatement = new LinkedHashMap<>();
        candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::year).reversed()
                        .thenComparingLong(Candidate::statementId).thenComparingLong(Candidate::questionId))
                .forEach(candidate -> byStatement.computeIfAbsent(candidate.statementId(), id -> new ArrayList<>()).add(candidate));
        byStatement.values().forEach(list -> java.util.Collections.shuffle(list, random));
        List<Long> chosen = new ArrayList<>();
        boolean progressed = true;
        while (chosen.size() < need && progressed) {
            progressed = false;
            for (List<Candidate> queue : byStatement.values()) {
                if (chosen.size() >= need) {
                    break;
                }
                if (!queue.isEmpty()) {
                    chosen.add(queue.remove(0).questionId());
                    progressed = true;
                }
            }
        }
        return chosen;
    }
}
