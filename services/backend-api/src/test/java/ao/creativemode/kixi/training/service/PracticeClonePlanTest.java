package ao.creativemode.kixi.training.service;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.exams.model.Question;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A ordem e a numeração das cópias de um treino (kixi#729): grupos, alíneas e questões soltas. */
class PracticeClonePlanTest {

    private static Question question(long id, Long parent, String label, int number, int order, String type) {
        Question q = new Question();
        q.setId(id);
        q.setParentId(parent);
        q.setLabel(label);
        q.setNumber(number);
        q.setOrderIndex(order);
        q.setQuestionType(type);
        return q;
    }

    private static Map<Long, Question> sources(Question... questions) {
        Map<Long, Question> map = new HashMap<>();
        for (Question q : questions) map.put(q.getId(), q);
        return map;
    }

    @Test
    void looseQuestionsAreRenumberedInTheChosenOrder() {
        Map<Long, Question> map = sources(question(50, null, null, 7, 7, "multiple_choice"), question(20, null, null, 2, 2, "development"));
        List<PracticeClonePlan.Step> steps = PracticeClonePlan.build(List.of(50L, 20L), map);
        assertThat(steps).extracting(s -> s.source().getId()).containsExactly(50L, 20L);
        assertThat(steps).extracting(PracticeClonePlan.Step::number).containsExactly(1, 2);
        assertThat(steps).extracting(PracticeClonePlan.Step::orderIndex).containsExactly(1, 2);
        assertThat(steps).allSatisfy(s -> assertThat(s.parentSourceId()).isNull());
    }

    @Test
    void aGroupIsCopiedOnceWithOnlyTheChosenItemsRightAfterTheHeader() {
        Question header = question(100, null, null, 3, 3, "group");
        Question a = question(301, 100L, "a", 301, 4, "short_answer");
        Question b = question(302, 100L, "b", 302, 5, "short_answer");
        Question c = question(303, 100L, "c", 303, 6, "short_answer");
        Question loose = question(9, null, null, 9, 9, "multiple_choice");
        // escolhidas: alínea c, questão solta, alínea a (b nunca é escolhida)
        List<PracticeClonePlan.Step> steps = PracticeClonePlan.build(List.of(303L, 9L, 301L), sources(header, a, b, c, loose));

        assertThat(steps).extracting(s -> s.source().getId()).containsExactly(100L, 301L, 303L, 9L);
        assertThat(steps).extracting(PracticeClonePlan.Step::number).containsExactly(1, 1001, 1002, 2);
        assertThat(steps).extracting(PracticeClonePlan.Step::label).containsExactly(null, "a", "c", null);
        assertThat(steps).extracting(PracticeClonePlan.Step::orderIndex).containsExactly(1, 2, 3, 4);
        assertThat(steps.get(1).parentSourceId()).isEqualTo(100L);
        assertThat(steps.get(2).parentSourceId()).isEqualTo(100L);
    }

    @Test
    void anItemWhoseHeaderIsMissingBecomesALooseQuestion() {
        Question orphan = question(301, 100L, "a", 301, 4, "short_answer");
        List<PracticeClonePlan.Step> steps = PracticeClonePlan.build(List.of(301L), sources(orphan));
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).parentSourceId()).isNull();
        assertThat(steps.get(0).number()).isEqualTo(1);
    }

    @Test
    void itemsWithoutLabelGetLetters() {
        Question header = question(100, null, null, 3, 3, "group");
        Question a = question(301, 100L, null, 301, 4, "short_answer");
        Question b = question(302, 100L, null, 302, 5, "short_answer");
        List<PracticeClonePlan.Step> steps = PracticeClonePlan.build(List.of(301L, 302L), sources(header, a, b));
        assertThat(steps).extracting(PracticeClonePlan.Step::label).containsExactly(null, "a", "b");
    }
}
