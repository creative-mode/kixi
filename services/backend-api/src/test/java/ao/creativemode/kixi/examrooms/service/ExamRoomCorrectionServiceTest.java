package ao.creativemode.kixi.examrooms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ao.creativemode.kixi.examrooms.dto.ExamRoomBulkGradeRequest;
import ao.creativemode.kixi.examrooms.dto.ExamRoomGradeResponse;
import ao.creativemode.kixi.examrooms.model.ExamRoom;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import ao.creativemode.kixi.simulations.model.SimulationAnswerStatus;
import ao.creativemode.kixi.simulations.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.simulations.service.SimulationSubmissionService;
import ao.creativemode.kixi.simulations.dto.simulationanswer.SimulationAnswerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ExamRoomCorrectionServiceTest {
    private ExamRoomMonitoringService monitoring;
    private SimulationAnswerRepository answers;
    private QuestionRepository questions;
    private SimulationSubmissionService submission;
    private ExamRoomCorrectionService service;
    private final SimulationAnswerResponse saved = new SimulationAnswerResponse(1L, 100L, 11L, null, null, 4f, true, SimulationAnswerStatus.GRADED, null, null, null, null);

    @BeforeEach
    void setUp() {
        monitoring = mock(ExamRoomMonitoringService.class);
        answers = mock(SimulationAnswerRepository.class);
        questions = mock(QuestionRepository.class);
        submission = mock(SimulationSubmissionService.class);
        service = new ExamRoomCorrectionService(monitoring, answers, questions, submission);

        ExamRoom room = new ExamRoom();
        room.setId(5L);
        room.setStatementId(9L);
        when(monitoring.authorize(5L, 7L, false)).thenReturn(Mono.just(room));
        when(monitoring.grades(5L, 7L, false)).thenReturn(Mono.just(List.of(
                new ExamRoomGradeResponse(1L, "Ana", 100L, "FINISHED", 2.0, 1, 3, 1, null),
                new ExamRoomGradeResponse(2L, "Bruno", 200L, "FINISHED", 0.0, 0, 3, 1, null),
                new ExamRoomGradeResponse(3L, "Carla", null, "NOT_STARTED", null, 0, 0, 0, null))));
        Question open = question(11L, 1, 4.0, "O ciclo de vida do produto");
        Question objective = question(12L, 2, 2.0, null);
        when(questions.findAllByStatementIdAndDeletedAtIsNull(9L)).thenReturn(Flux.just(open, objective));
        when(answers.findAllBySimulationIdInAndDeletedAtIsNull(any())).thenReturn(Flux.just(
                answer(1L, 100L, 11L, "o ciclo de vida do produto.", SimulationAnswerStatus.PENDING_REVIEW),
                answer(2L, 200L, 11L, "outra coisa", SimulationAnswerStatus.PENDING_REVIEW),
                answer(3L, 100L, 12L, null, SimulationAnswerStatus.AUTO_GRADED)));
    }

    private static Question question(Long id, int number, double max, String key) {
        Question q = new Question();
        q.setId(id);
        q.setNumber(number);
        q.setMaxScore(max);
        q.setModelAnswer(key);
        q.setText("Questão " + number);
        return q;
    }

    private static SimulationAnswer answer(Long id, Long simulationId, Long questionId, String text, SimulationAnswerStatus status) {
        SimulationAnswer a = new SimulationAnswer();
        a.setId(id);
        a.setSimulationId(simulationId);
        a.setQuestionId(questionId);
        a.setAnswerText(text);
        a.setReviewStatus(status);
        return a;
    }

    @Test
    void reviewGroupsThePendingOpenAnswersByQuestionWithTheKey() {
        var review = service.review(5L, 7L, false).block();

        assertThat(review.pending()).isEqualTo(2);
        assertThat(review.questions()).hasSize(1);
        var question = review.questions().get(0);
        assertThat(question.modelAnswer()).isEqualTo("O ciclo de vida do produto");
        assertThat(question.answers()).extracting(a -> a.displayName()).containsExactly("Ana", "Bruno");
    }

    @Test
    void byKeyGivesFullMarksOnlyToTheAnswersThatMatchTheKey() {
        when(submission.grade(anyLong(), anyDouble(), anyLong(), anyBoolean())).thenReturn(Mono.just(saved));

        var result = service.grade(5L, 7L, false, new ExamRoomBulkGradeRequest(null, true)).block();

        assertThat(result.graded()).isEqualTo(1);
        assertThat(result.byKey()).isEqualTo(1);
        verify(submission).grade(eq(1L), eq(4.0), eq(7L), eq(false));
        verify(submission, never()).grade(eq(2L), anyDouble(), anyLong(), anyBoolean());
    }

    @Test
    void aGivenScoreBeatsTheKeyAndAnAnswerOfAnotherRoomIsRefused() {
        when(submission.grade(anyLong(), anyDouble(), anyLong(), anyBoolean())).thenReturn(Mono.just(saved));

        var result = service.grade(5L, 7L, false, new ExamRoomBulkGradeRequest(List.of(
                new ExamRoomBulkGradeRequest.Grade(1L, 1.5), new ExamRoomBulkGradeRequest.Grade(99L, 2.0)), true)).block();

        verify(submission).grade(eq(1L), eq(1.5), eq(7L), eq(false));
        assertThat(result.byKey()).isZero();
        assertThat(result.failed()).extracting(f -> f.answerId()).containsExactly(99L);
    }

    @Test
    void aRejectedGradeIsReportedWithoutStoppingTheOthers() {
        when(submission.grade(eq(1L), anyDouble(), anyLong(), anyBoolean())).thenReturn(Mono.error(ApiException.badRequest("Score cannot exceed the question maximum")));
        when(submission.grade(eq(2L), anyDouble(), anyLong(), anyBoolean())).thenReturn(Mono.just(saved));

        var result = service.grade(5L, 7L, false, new ExamRoomBulkGradeRequest(List.of(
                new ExamRoomBulkGradeRequest.Grade(1L, 9.0), new ExamRoomBulkGradeRequest.Grade(2L, 1.0)), false)).block();

        assertThat(result.graded()).isEqualTo(1);
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).message()).contains("exceed");
    }

    @Test
    void theKeyComparisonIgnoresCaseAccentsAndPunctuationButNeverMatchesBlank() {
        assertThat(ExamRoomCorrectionService.matchesKey("  Aprovisionamento! ", "aprovisionamento")).isTrue();
        assertThat(ExamRoomCorrectionService.matchesKey("Previsão", "previsao")).isTrue();
        assertThat(ExamRoomCorrectionService.matchesKey("", "")).isFalse();
        assertThat(ExamRoomCorrectionService.matchesKey("x", null)).isFalse();
    }
}
