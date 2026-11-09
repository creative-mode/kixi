package ao.creativemode.kixi.training.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionImage;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionImageRepository;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.dto.simulation.SimulationResponse;
import ao.creativemode.kixi.simulations.service.SimulationService;
import ao.creativemode.kixi.training.dto.MatrixPracticeRequest;
import ao.creativemode.kixi.training.dto.MatrixPracticeResponse;
import ao.creativemode.kixi.training.dto.MatrixResponse;
import ao.creativemode.kixi.training.service.TrainingPoolReader.PoolRow;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Montar o treino por temas (kixi#729): o enunciado virtual, as cópias e a simulação. */
class MatrixPracticeServiceTest {

    private static final long STUDENT = 12L;
    private static final long ROOM = 40L;
    private static final long ROOM_STATEMENT = 500L;

    private final TrainingPlanService plans = mock(TrainingPlanService.class);
    private final TrainingPoolReader pool = mock(TrainingPoolReader.class);
    private final StatementRepository statements = mock(StatementRepository.class);
    private final QuestionRepository questions = mock(QuestionRepository.class);
    private final QuestionOptionRepository options = mock(QuestionOptionRepository.class);
    private final QuestionImageRepository images = mock(QuestionImageRepository.class);
    private final SimulationService simulations = mock(SimulationService.class);
    private final MatrixPracticeService service =
            new MatrixPracticeService(plans, pool, statements, questions, options, images, simulations, new Random(1));

    private final List<Question> savedQuestions = new ArrayList<>();
    private final List<QuestionOption> savedOptions = new ArrayList<>();
    private final List<QuestionImage> savedImages = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(9000);

    @BeforeEach
    void repositories() {
        Statement origin = new Statement();
        origin.setId(ROOM_STATEMENT);
        origin.setSubjectId(3L);
        origin.setClassId(8L);
        origin.setSchoolYearId(2L);
        origin.setInstitutionId(4L);
        when(statements.findAnyByIdAndDeletedAtIsNull(ROOM_STATEMENT)).thenReturn(Mono.just(origin));
        when(statements.save(any(Statement.class))).thenAnswer(call -> {
            Statement saved = call.getArgument(0);
            saved.setId(7000L);
            return Mono.just(saved);
        });
        when(questions.save(any(Question.class))).thenAnswer(call -> {
            Question saved = call.getArgument(0);
            saved.setId(ids.incrementAndGet());
            savedQuestions.add(saved);
            return Mono.just(saved);
        });
        when(options.save(any(QuestionOption.class))).thenAnswer(call -> {
            savedOptions.add(call.getArgument(0));
            return Mono.just(call.getArgument(0));
        });
        when(images.save(any(QuestionImage.class))).thenAnswer(call -> {
            savedImages.add(call.getArgument(0));
            return Mono.just(call.getArgument(0));
        });
        when(pool.openPractices(eq(STUDENT), eq(ROOM))).thenReturn(Mono.just(0L));
        when(options.findAllByQuestionIdAndDeletedAtIsNull(anyLong())).thenReturn(Flux.empty());
        when(images.findByQuestionIdAndDeletedAtIsNullOrderByOrderIndexAsc(anyLong())).thenReturn(Flux.empty());
        when(simulations.createForPractice(eq(STUDENT), eq(7000L), anyString()))
                .thenReturn(Mono.just(new SimulationResponse(90L, null, null, null, null, null, null, null, null, null,
                        null, null, null, null)));
    }

    private static Question source(long id, long statementId, int number, String type, Double score) {
        Question q = new Question();
        q.setId(id);
        q.setStatementId(statementId);
        q.setNumber(number);
        q.setOrderIndex(number);
        q.setText("Enunciado " + id);
        q.setQuestionType(type);
        q.setMaxScore(score);
        q.setModelAnswer("resposta " + id);
        return q;
    }

    private void context(String matrixTitle, List<PoolRow> pool, MatrixResponse.Item... items) {
        TrainingRoomReader.RoomInfo room = new TrainingRoomReader.RoomInfo(ROOM, 7L, ROOM_STATEMENT, 3L, "Matemática",
                "P1 2026", LocalDateTime.of(2026, 12, 1, 8, 0), 90, true);
        MatrixResponse matrix = new MatrixResponse(ROOM, matrixTitle, 20.0, List.of(items));
        when(plans.context(eq(ROOM), eq(STUDENT), any())).thenReturn(Mono.just(new TrainingPlanService.Context(room, matrix, pool)));
    }

    private static MatrixResponse.Item matrixItem(long topic, int count) {
        return new MatrixResponse.Item(topic, topic, "Tema " + topic, count, null, null, List.of());
    }

    private static PoolRow row(long topic, long question, long statement, int year) {
        return new PoolRow(topic, question, statement, "Prova " + statement, year, false, false);
    }

    private static MatrixPracticeRequest request(Integer duration, MatrixPracticeRequest.Item... items) {
        return new MatrixPracticeRequest(ROOM, List.of(items), duration);
    }

    @Test
    void createsAPrivateVirtualStatementWithCopiesAndTheSimulationOnIt() {
        context("Matriz do 1.º trimestre", List.of(row(5, 101, 77, 2024), row(5, 102, 78, 2023), row(6, 201, 77, 2024)),
                matrixItem(5, 4), matrixItem(6, 2));
        Question q101 = source(101, 77, 1, "multiple_choice", 2.0);
        Question q102 = source(102, 78, 4, "development", 3.0);
        Question q201 = source(201, 77, 2, "short_answer", 1.5);
        when(questions.findAllById(any(Iterable.class))).thenReturn(Flux.just(q101, q102, q201));
        QuestionOption right = new QuestionOption(101L, "A", "certa", true);
        right.setOrderIndex(0);
        QuestionOption wrong = new QuestionOption(101L, "B", "errada", false);
        wrong.setOrderIndex(1);
        when(options.findAllByQuestionIdAndDeletedAtIsNull(101L)).thenReturn(Flux.just(right, wrong));
        QuestionImage image = new QuestionImage();
        image.setImageUrl("/uploads/questions/a/b.png");
        image.setStorageKey("questions/a/b.png");
        image.setOrderIndex(0);
        when(images.findByQuestionIdAndDeletedAtIsNullOrderByOrderIndexAsc(101L)).thenReturn(Flux.just(image));

        StepVerifier.create(service.start(STUDENT, request(null, new MatrixPracticeRequest.Item(5L, 4), new MatrixPracticeRequest.Item(6L, 2))))
                .assertNext(response -> {
                    assertThat(response.simulation().id()).isEqualTo(90L);
                    assertThat(response.allocation()).containsExactly(
                            new MatrixPracticeResponse.Allocation(5L, 4, 2), new MatrixPracticeResponse.Allocation(6L, 2, 1));
                })
                .verifyComplete();

        ArgumentCaptor<Statement> statement = ArgumentCaptor.forClass(Statement.class);
        verify(statements).save(statement.capture());
        Statement virtual = statement.getValue();
        assertThat(virtual.getPracticeOwnerAccountId()).isEqualTo(STUDENT);
        assertThat(virtual.getPracticeExamRoomId()).isEqualTo(ROOM);
        assertThat(virtual.getCreatedBy()).isEqualTo(STUDENT);
        assertThat(virtual.getVisible()).isFalse();
        assertThat(virtual.getSource()).isEqualTo("practice");
        assertThat(virtual.getTitle()).isEqualTo("Treino · Matriz do 1.º trimestre");
        assertThat(virtual.getSubjectId()).isEqualTo(3L);
        assertThat(virtual.getClassId()).isEqualTo(8L);
        assertThat(virtual.getSchoolYearId()).isEqualTo(2L);
        assertThat(virtual.getInstitutionId()).isEqualTo(4L);
        // 3 questões de 4 na matriz de 6: 90 * 3 / 6 = 45 minutos
        assertThat(virtual.getDurationMinutes()).isEqualTo(45);
        assertThat(virtual.getTotalMaxScore()).isEqualTo(6.5);

        assertThat(savedQuestions).hasSize(3);
        assertThat(savedQuestions).extracting(Question::getStatementId).containsOnly(7000L);
        assertThat(savedQuestions).extracting(Question::getSourceQuestionId).containsExactlyInAnyOrder(101L, 102L, 201L);
        assertThat(savedQuestions).extracting(Question::getNumber).containsExactly(1, 2, 3);
        assertThat(savedQuestions).extracting(Question::getModelAnswer)
                .allSatisfy(answer -> assertThat(answer).startsWith("resposta"));

        // as opções vão com o gabarito, e a imagem aponta para o mesmo ficheiro
        assertThat(savedOptions).hasSize(2);
        assertThat(savedOptions).extracting(QuestionOption::getIsCorrect).containsExactly(true, false);
        assertThat(savedOptions).extracting(QuestionOption::getQuestionId).doesNotContain(101L);
        assertThat(savedImages).hasSize(1);
        assertThat(savedImages.get(0).getStorageKey()).isEqualTo("questions/a/b.png");
        assertThat(savedImages.get(0).getImageUrl()).isEqualTo("/uploads/questions/a/b.png");
        assertThat(savedImages.get(0).getQuestionId()).isNotEqualTo(101L);

        verify(simulations).createForPractice(STUDENT, 7000L, "Treino · Matriz do 1.º trimestre");
    }

    @Test
    void theTitleFallsBackToTheExamAndAnExplicitDurationWins() {
        context(null, List.of(row(5, 101, 77, 2024)), matrixItem(5, 1));
        when(questions.findAllById(any(Iterable.class))).thenReturn(Flux.just(source(101, 77, 1, "short_answer", 1.0)));

        StepVerifier.create(service.start(STUDENT, request(30, new MatrixPracticeRequest.Item(5L, 1))))
                .expectNextCount(1).verifyComplete();

        ArgumentCaptor<Statement> statement = ArgumentCaptor.forClass(Statement.class);
        verify(statements).save(statement.capture());
        assertThat(statement.getValue().getTitle()).isEqualTo("Treino · P1 2026");
        assertThat(statement.getValue().getDurationMinutes()).isEqualTo(30);
    }

    @Test
    void whenNothingIsAvailableItIs400AndNothingIsWritten() {
        context("M", List.of(), matrixItem(5, 4));

        StepVerifier.create(service.start(STUDENT, request(null, new MatrixPracticeRequest.Item(5L, 4))))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(400);
                    assertThat(error.getMessage()).contains("Não há questões disponíveis");
                })
                .verify();

        verify(statements, never()).save(any());
        verify(simulations, never()).createForPractice(anyLong(), anyLong(), anyString());
    }

    @Test
    void aTopicOutsideTheMatrixIs400() {
        context("M", List.of(row(5, 101, 77, 2024)), matrixItem(5, 4));

        StepVerifier.create(service.start(STUDENT, request(null, new MatrixPracticeRequest.Item(99L, 2))))
                .expectErrorSatisfies(error -> assertThat(error.getMessage()).contains("não faz parte da matriz"))
                .verify();
        verify(statements, never()).save(any());
    }

    @Test
    void aRepeatedTopicInTheRequestIs400() {
        StepVerifier.create(service.start(STUDENT, request(null, new MatrixPracticeRequest.Item(5L, 2), new MatrixPracticeRequest.Item(5L, 1))))
                .expectErrorSatisfies(error -> assertThat(error.getMessage()).contains("repetido"))
                .verify();
        verify(plans, never()).context(anyLong(), anyLong(), any());
    }

    @Test
    void aGroupIsCopiedOnceWithItsHeaderAndOnlyTheChosenItems() {
        context("M", List.of(row(5, 301, 77, 2024)), matrixItem(5, 1));
        Question header = source(300, 77, 3, "group", null);
        Question item = source(301, 77, 301, "short_answer", 2.0);
        item.setParentId(300L);
        item.setLabel("a");
        when(questions.findAllById(any(Iterable.class))).thenAnswer(call -> {
            Iterable<?> asked = call.getArgument(0);
            return asked.iterator().next().equals(300L) ? Flux.just(header) : Flux.just(item);
        });

        StepVerifier.create(service.start(STUDENT, request(null, new MatrixPracticeRequest.Item(5L, 1)))).expectNextCount(1).verifyComplete();

        assertThat(savedQuestions).hasSize(2);
        Question headerClone = savedQuestions.get(0);
        Question itemClone = savedQuestions.get(1);
        assertThat(headerClone.isGroup()).isTrue();
        assertThat(headerClone.getSourceQuestionId()).isEqualTo(300L);
        assertThat(itemClone.getParentId()).isEqualTo(headerClone.getId());
        assertThat(itemClone.getLabel()).isEqualTo("a");
        assertThat(itemClone.getSourceQuestionId()).isEqualTo(301L);
        assertThat(itemClone.getNumber()).isEqualTo(1001);
    }

    @Test
    void moreThanAHundredQuestionsInTotalIs400BeforeAnythingIsLoaded() {
        StepVerifier.create(service.start(STUDENT, request(null, new MatrixPracticeRequest.Item(5L, 60), new MatrixPracticeRequest.Item(6L, 41))))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(400);
                    assertThat(error.getMessage()).contains("no máximo 100 questões").contains("101");
                })
                .verify();
        verify(plans, never()).context(anyLong(), anyLong(), any());
        verify(statements, never()).save(any());
    }

    @Test
    void exactlyAHundredIsAccepted() {
        context("M", List.of(row(5, 101, 77, 2024)), matrixItem(5, 100));
        when(questions.findAllById(any(Iterable.class))).thenReturn(Flux.just(source(101, 77, 1, "short_answer", 1.0)));
        StepVerifier.create(service.start(STUDENT, request(null, new MatrixPracticeRequest.Item(5L, 100)))).expectNextCount(1).verifyComplete();
    }

    @Test
    void aStudentWithTwentyOpenPracticesOfThisMatrixIsToldToFinishOne() {
        context("M", List.of(row(5, 101, 77, 2024)), matrixItem(5, 4));
        when(pool.openPractices(STUDENT, ROOM)).thenReturn(Mono.just(20L));

        StepVerifier.create(service.start(STUDENT, request(null, new MatrixPracticeRequest.Item(5L, 2))))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(409);
                    assertThat(error.getMessage()).isEqualTo("Termina ou cancela um treino antes de começar outro.");
                })
                .verify();
        verify(statements, never()).save(any());
        verify(simulations, never()).createForPractice(anyLong(), anyLong(), anyString());
    }

    @Test
    void nineteenOpenPracticesStillAllowsAnother() {
        context("M", List.of(row(5, 101, 77, 2024)), matrixItem(5, 4));
        when(pool.openPractices(STUDENT, ROOM)).thenReturn(Mono.just(19L));
        when(questions.findAllById(any(Iterable.class))).thenReturn(Flux.just(source(101, 77, 1, "short_answer", 1.0)));
        StepVerifier.create(service.start(STUDENT, request(null, new MatrixPracticeRequest.Item(5L, 2)))).expectNextCount(1).verifyComplete();
    }

    @Test
    void proportionalDuration() {
        assertThat(MatrixPracticeService.proportionalDuration(90, 3, 6)).isEqualTo(45);
        assertThat(MatrixPracticeService.proportionalDuration(90, 20, 6)).isEqualTo(90);
        assertThat(MatrixPracticeService.proportionalDuration(90, 1, 100)).isEqualTo(1);
        assertThat(MatrixPracticeService.proportionalDuration(90, 3, 0)).isEqualTo(90);
        assertThat(MatrixPracticeService.proportionalDuration(null, 3, 6)).isNull();
    }
}
