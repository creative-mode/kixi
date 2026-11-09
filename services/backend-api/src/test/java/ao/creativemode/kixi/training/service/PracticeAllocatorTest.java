package ao.creativemode.kixi.training.service;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.training.service.PracticeAllocator.Allocation;
import ao.creativemode.kixi.training.service.PracticeAllocator.Candidate;
import ao.creativemode.kixi.training.service.PracticeAllocator.Request;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** A repartição das questões do treino por tema (kixi#729). */
class PracticeAllocatorTest {

    private static Candidate fresh(long question, long statement, int year) {
        return new Candidate(question, statement, year, false, false);
    }

    private static Candidate wrong(long question, long statement, int year) {
        return new Candidate(question, statement, year, true, false);
    }

    private static Candidate right(long question, long statement, int year) {
        return new Candidate(question, statement, year, true, true);
    }

    private static List<Allocation> run(List<Request> requests, Map<Long, List<Candidate>> pool) {
        return PracticeAllocator.allocate(requests, pool, new Random(7));
    }

    @Test
    void neverTriedFirstThenWrongThenRight() {
        Map<Long, List<Candidate>> pool = Map.of(1L, List.of(
                right(1, 10, 2023), wrong(2, 10, 2023), fresh(3, 10, 2023), fresh(4, 11, 2022), wrong(5, 11, 2022)));

        assertThat(run(List.of(new Request(1, 2)), pool).get(0).questionIds()).containsExactlyInAnyOrder(3L, 4L);
        assertThat(run(List.of(new Request(1, 4)), pool).get(0).questionIds())
                .containsExactlyInAnyOrder(3L, 4L, 2L, 5L);
        // as 4 primeiras são as não tentadas e as erradas; as acertadas só no fim
        List<Long> all = run(List.of(new Request(1, 5)), pool).get(0).questionIds();
        assertThat(all).hasSize(5).last().isEqualTo(1L);
        assertThat(all.subList(0, 2)).containsExactlyInAnyOrder(3L, 4L);
    }

    @Test
    void diversifiesAcrossStatementsRoundRobin() {
        // o enunciado 10 tem 5 questões, o 11 e o 12 têm uma cada: 3 questões vêm uma de cada enunciado
        Map<Long, List<Candidate>> pool = Map.of(1L, List.of(
                fresh(1, 10, 2024), fresh(2, 10, 2024), fresh(3, 10, 2024), fresh(4, 10, 2024), fresh(5, 10, 2024),
                fresh(6, 11, 2023), fresh(7, 12, 2022)));

        List<Long> chosen = run(List.of(new Request(1, 3)), pool).get(0).questionIds();

        assertThat(chosen).contains(6L, 7L);
        assertThat(chosen.stream().filter(id -> id <= 5).count()).isEqualTo(1);
    }

    @Test
    void roundRobinStartsWithTheMostRecentYear() {
        Map<Long, List<Candidate>> pool = Map.of(1L, List.of(fresh(1, 10, 2019), fresh(2, 11, 2024), fresh(3, 12, 2021)));
        assertThat(run(List.of(new Request(1, 1)), pool).get(0).questionIds()).containsExactly(2L);
        assertThat(run(List.of(new Request(1, 2)), pool).get(0).questionIds()).containsExactly(2L, 3L);
    }

    @Test
    void shortfallGivesWhatThereIsAndNeverFails() {
        Map<Long, List<Candidate>> pool = Map.of(1L, List.of(fresh(1, 10, 2024), fresh(2, 10, 2024)));
        Allocation allocation = run(List.of(new Request(1, 5), new Request(2, 3)), pool).get(0);
        assertThat(allocation.requested()).isEqualTo(5);
        assertThat(allocation.provided()).isEqualTo(2);

        Allocation noPool = run(List.of(new Request(1, 5), new Request(2, 3)), pool).get(1);
        assertThat(noPool.provided()).isZero();
        assertThat(noPool.questionIds()).isEmpty();
    }

    @Test
    void aQuestionWithTwoTopicsIsNotRepeatedAcrossTopics() {
        // a questão 1 serve os temas 1 e 2; o tema 1 só tem a 1, o tema 2 tem a 1 e a 2
        Map<Long, List<Candidate>> pool = Map.of(
                1L, List.of(fresh(1, 10, 2024)),
                2L, List.of(fresh(1, 10, 2024), fresh(2, 10, 2024)));

        List<Allocation> result = run(List.of(new Request(2, 2), new Request(1, 1)), pool);

        List<Long> all = result.stream().flatMap(a -> a.questionIds().stream()).toList();
        assertThat(all).doesNotHaveDuplicates().containsExactlyInAnyOrder(1L, 2L);
        // o resultado vem pela ordem dos pedidos, não pela ordem de escolha
        assertThat(result.get(0).topicId()).isEqualTo(2L);
        assertThat(result.get(0).questionIds()).containsExactly(2L);
        assertThat(result.get(1).questionIds()).containsExactly(1L);
    }

    @Test
    void noQuestionIsRepeatedWhateverTheOverlap() {
        Map<Long, List<Candidate>> pool = Map.of(
                1L, List.of(fresh(1, 10, 2024), fresh(2, 10, 2024), fresh(3, 11, 2023)),
                2L, List.of(fresh(2, 10, 2024), fresh(3, 11, 2023), fresh(4, 12, 2022)),
                3L, List.of(fresh(1, 10, 2024), fresh(4, 12, 2022)));
        List<Allocation> result = run(List.of(new Request(1, 3), new Request(2, 3), new Request(3, 3)), pool);
        List<Long> all = result.stream().flatMap(a -> a.questionIds().stream()).toList();
        Set<Long> distinct = all.stream().collect(Collectors.toSet());
        assertThat(all).hasSameSizeAs(distinct);
        assertThat(distinct).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
    }

    @Test
    void sameSeedSameResult() {
        Map<Long, List<Candidate>> pool = Map.of(1L, List.of(
                fresh(1, 10, 2024), fresh(2, 10, 2024), fresh(3, 10, 2024), fresh(4, 11, 2023), fresh(5, 11, 2023)));
        assertThat(PracticeAllocator.allocate(List.of(new Request(1, 3)), pool, new Random(3)))
                .isEqualTo(PracticeAllocator.allocate(List.of(new Request(1, 3)), pool, new Random(3)));
    }
}
