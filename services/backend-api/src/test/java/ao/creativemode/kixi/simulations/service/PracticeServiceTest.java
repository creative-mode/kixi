package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** «Montar treino» (kixi#729): que questões entram, e como a seleção restringe a correção. */
class PracticeServiceTest {

    private final PracticeService service = new PracticeService(
            mock(SimulationService.class), mock(SimulationRepository.class), mock(QuestionRepository.class), new Random(1));

    private static Question question(long id, int order, boolean group) {
        Question q = new Question();
        q.setId(id);
        q.setOrderIndex(order);
        if (group) q.setQuestionType("group");
        return q;
    }

    private List<Question> paper() {
        List<Question> all = new ArrayList<>();
        for (long i = 1; i <= 6; i++) all.add(question(i, (int) i, false));
        return all;
    }

    @Test
    void picksExactlyNQuestionsInPaperOrder() {
        List<Long> chosen = service.select(paper(), 3).orElseThrow();
        assertThat(chosen).hasSize(3).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void asMuchAsThePaperMeansNoSelection() {
        assertThat(service.select(paper(), 6)).isEmpty();
        assertThat(service.select(paper(), 50)).isEmpty();
    }

    @Test
    void selectionNarrowsWhatIsGraded() {
        Simulation simulation = new Simulation();
        simulation.setSelectedQuestionIds("2,4");
        assertThat(QuestionSelection.answerable(simulation, paper())).extracting(Question::getId).containsExactly(2L, 4L);
        assertThat(QuestionSelection.answerable(new Simulation(), paper())).hasSize(6);
    }
}
