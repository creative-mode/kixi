package ao.creativemode.kixi.exams.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.exams.dto.statement.ManualStatementRequest;
import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.academic.model.Subject;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.CourseRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import ao.creativemode.kixi.academic.repository.TermRepository;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.institutions.service.InstitutionAccessService;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ManualStatementServiceTest {

    private StatementRepository statements;
    private QuestionRepository questions;
    private QuestionOptionRepository options;
    private InstitutionAccessService access;
    private StatementLinkValidationService validator;
    private SubjectRepository subjects;
    private ClassRepository classes;
    private SchoolYearRepository schoolYears;
    private ManualStatementService service;

    private static final Long CLASS_ID = 8L;
    private static final Long SUBJECT_ID = 2L;
    private static final Long SCHOOL_YEAR_ID = 2024L;
    private static final Long COURSE_ID = 9L;

    @BeforeEach
    void setUp() {
        statements = mock(StatementRepository.class);
        questions = mock(QuestionRepository.class);
        options = mock(QuestionOptionRepository.class);
        access = mock(InstitutionAccessService.class);
        subjects = mock(SubjectRepository.class);
        classes = mock(ClassRepository.class);
        schoolYears = mock(SchoolYearRepository.class);
        // The real validator: the rule that a teacher must name a class is the
        // point of several tests here and would say nothing if stubbed out.
        validator = new StatementLinkValidationService(
                schoolYears, mock(TermRepository.class),
                subjects, mock(CourseRepository.class), classes);
        service = new ManualStatementService(statements, questions, options, access, validator);
        givenTheAcademicReferencesExist();
    }

    private void givenTheAcademicReferencesExist() {
        Subject subject = new Subject();
        subject.setId(SUBJECT_ID);
        when(subjects.findByIdAndDeletedAtIsNull(SUBJECT_ID)).thenReturn(Mono.just(subject));

        SchoolYear schoolYear = new SchoolYear();
        schoolYear.setId(SCHOOL_YEAR_ID);
        // Any school year exists; the fixture is about the class disagreeing
        // with the one on the statement, not about a missing year.
        when(schoolYears.findByIdAndDeletedAtIsNull(anyLong())).thenReturn(Mono.just(schoolYear));

        Class klass = new Class();
        klass.setId(CLASS_ID);
        klass.setGrade(12);
        klass.setSchoolYearId(SCHOOL_YEAR_ID);
        // classes.course_id is NOT NULL in V10, so a fixture without it is a trap
        // for the validator's course branch.
        klass.setCourseId(COURSE_ID);
        when(classes.findByIdAndDeletedAtIsNull(CLASS_ID)).thenReturn(Mono.just(klass));
    }

    /** A statement from a teacher: it names the class being taught. */
    private ManualStatementRequest request() {
        return requestOfSchoolYear(null);
    }

    private ManualStatementRequest requestOfSchoolYear(Long schoolYearId) {
        return new ManualStatementRequest(
            1L, 2L, "Prova de Matemática", "Teste", 90, null, null,
            schoolYearId, null, CLASS_ID, null, true,
            List.of(
                new ManualStatementRequest.Question("Resolva x+1=2", 5.0, null),
                new ManualStatementRequest.Question("Escolha", 3.5, List.of(
                    new ManualStatementRequest.Option("A", "um", true),
                    new ManualStatementRequest.Option("B", "dois", false)))
            )
        );
    }

    /** A statement that names no class, which only an administrator may build. */
    private ManualStatementRequest requestVisible(boolean visible) {
        return new ManualStatementRequest(
            1L, 2L, "Prova de Matemática", "Teste", 90, "  ", "  ", null, null, CLASS_ID, null,
            visible,
            List.of(new ManualStatementRequest.Question("Resolva x+1=2", 5.0, null))
        );
    }

    private void givenSaves() {
        when(statements.save(any(Statement.class))).thenAnswer(invocation -> {
            Statement s = invocation.getArgument(0);
            s.setId(10L);
            return Mono.just(s);
        });
        when(questions.save(any(Question.class))).thenAnswer(invocation -> {
            Question q = invocation.getArgument(0);
            q.setId(100L + q.getNumber());
            return Mono.just(q);
        });
        when(options.save(any(QuestionOption.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    private ManualStatementRequest requestWithoutClass() {        return new ManualStatementRequest(
            1L, 2L, "Prova de Matemática", "Teste", 90, null, null,
            null, null, null, null, true,
            List.of(new ManualStatementRequest.Question("Resolva x+1=2", 5.0, null))
        );
    }

    @Test
    void aCreatedStatementIsVisibleOnlyIfTheTeacherAsksAndIsNeverUpForReview() {
        // Creation honours `visible` because the author is the teacher who wrote
        // the paper; the review gate exists for what the OCR guessed. Pinned so
        // the asymmetry with the edit path stays deliberate: on an edit the
        // statement may be OCR-derived, so publishing is a separate act.
        when(access.requireCanAuthor(9L, false, 1L, SUBJECT_ID, CLASS_ID)).thenReturn(Mono.empty());
        givenSaves();

        StepVerifier.create(service.create(requestVisible(true), 9L, false))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<Statement> saved = ArgumentCaptor.forClass(Statement.class);
        verify(statements).save(saved.capture());
        assertThat(saved.getValue().getVisible()).isTrue();
        assertThat(saved.getValue().getNeedsReview()).isFalse();
        assertThat(saved.getValue().getSource()).isEqualTo("manual");
    }

    @Test
    void aCreatedStatementIsInvisibleByDefaultAndDropsABlankVariant() {
        when(access.requireCanAuthor(9L, false, 1L, SUBJECT_ID, CLASS_ID)).thenReturn(Mono.empty());
        givenSaves();

        StepVerifier.create(service.create(requestVisible(false), 9L, false))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<Statement> saved = ArgumentCaptor.forClass(Statement.class);
        verify(statements).save(saved.capture());
        assertThat(saved.getValue().getVisible()).isFalse();
        assertThat(saved.getValue().getVariant()).isNull();
        assertThat(saved.getValue().getInstructions()).isNull();
    }

    @Test
    void createsStatementWithNumberedQuestionsAndOptions() {
        when(access.requireCanAuthor(9L, false, 1L, 2L, CLASS_ID)).thenReturn(Mono.empty());
        when(statements.save(any(Statement.class))).thenAnswer(invocation -> {
            Statement s = invocation.getArgument(0);
            s.setId(10L);
            return Mono.just(s);
        });
        when(questions.save(any(Question.class))).thenAnswer(invocation -> {
            Question q = invocation.getArgument(0);
            q.setId(100L + q.getNumber());
            return Mono.just(q);
        });
        when(options.save(any(QuestionOption.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.create(request(), 9L, false))
                .assertNext(saved -> assertThat(saved.getId()).isEqualTo(10L))
                .verifyComplete();

        ArgumentCaptor<Statement> statement = ArgumentCaptor.forClass(Statement.class);
        verify(statements).save(statement.capture());
        assertThat(statement.getValue().getInstitutionId()).isEqualTo(1L);
        assertThat(statement.getValue().getSubjectId()).isEqualTo(2L);
        assertThat(statement.getValue().getClassId()).isEqualTo(CLASS_ID);
        assertThat(statement.getValue().getCreatedBy()).isEqualTo(9L);
        assertThat(statement.getValue().getSource()).isEqualTo("manual");
        assertThat(statement.getValue().getTotalMaxScore()).isEqualTo(8.5);
        verify(questions, times(2)).save(any(Question.class));
        verify(options, times(2)).save(any(QuestionOption.class));
        // The class is what puts the teaching assignment in play.
        verify(access).requireCanAuthor(9L, false, 1L, 2L, CLASS_ID);
    }

    @Test
    void doesNotSaveAnythingWhenAccessIsDenied() {
        when(access.requireCanAuthor(9L, false, 1L, 2L, CLASS_ID))
                .thenReturn(Mono.error(ApiException.forbidden("Teacher is not affiliated with this institution")));

        StepVerifier.create(service.create(request(), 9L, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        verify(statements, never()).save(any(Statement.class));
        verify(questions, never()).save(any(Question.class));
    }

    @Test
    void aTeacherMayNotOmitTheClassToSkipTheAssignmentCheck() {
        // The assignment is keyed on the class. A teacher who names no class
        // used to walk straight past the check and could build a statement for
        // any subject of the institution.
        StepVerifier.create(service.create(requestWithoutClass(), 9L, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .verify();

        verifyNoInteractions(access);
        verify(statements, never()).save(any(Statement.class));
        verify(questions, never()).save(any(Question.class));
    }

    @Test
    void anAdministratorMayBuildAStatementWithoutAClass() {
        when(access.requireCanAuthor(9L, true, 1L, 2L, null)).thenReturn(Mono.empty());
        when(statements.save(any(Statement.class))).thenAnswer(invocation -> {
            Statement s = invocation.getArgument(0);
            s.setId(10L);
            return Mono.just(s);
        });
        when(questions.save(any(Question.class))).thenAnswer(invocation -> {
            Question q = invocation.getArgument(0);
            q.setId(100L + q.getNumber());
            return Mono.just(q);
        });
        when(options.save(any(QuestionOption.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.create(requestWithoutClass(), 9L, true)).expectNextCount(1).verifyComplete();

        verify(access).requireCanAuthor(9L, true, 1L, 2L, null);
    }

    @Test
    void doesNotSaveAnythingWhenTheClassBelongsToAnotherSchoolYear() {
        // The statement claims 2026/2027 while the class is from 2024/2025. The
        // foreign key would have accepted it, and the teaching assignment would
        // then be checked against the wrong year. The school year has to be on
        // the request for this rule to be reachable at all: with it null the
        // validator has nothing to compare the class against.
        when(access.requireCanAuthor(9L, false, 1L, SUBJECT_ID, CLASS_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.create(requestOfSchoolYear(SCHOOL_YEAR_ID + 2), 9L, false))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatus())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getMessage()).contains("school year must be the one of the class");
                })
                .verify();

        verify(statements, never()).save(any(Statement.class));
    }

    @Test
    void doesNotSaveAnythingWhenTheSubjectDoesNotExist() {
        when(access.requireCanAuthor(9L, false, 1L, SUBJECT_ID, CLASS_ID)).thenReturn(Mono.empty());
        when(subjects.findByIdAndDeletedAtIsNull(SUBJECT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.create(request(), 9L, false))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatus())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getMessage()).contains("subject does not exist");
                })
                .verify();

        verify(statements, never()).save(any(Statement.class));
    }

    @Test
    void doesNotSaveAnythingWhenTheTeacherIsNotAssignedToTheClass() {
        when(access.requireCanAuthor(9L, false, 1L, 2L, CLASS_ID))
                .thenReturn(Mono.error(ApiException.forbidden(
                        "Teacher is not assigned to this class and subject")));

        StepVerifier.create(service.create(request(), 9L, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        verify(statements, never()).save(any(Statement.class));
    }

    // ── Questões com alíneas e tipos (issue #247) ───────────────────────────

    private ManualStatementRequest paperOf(ManualStatementRequest.Question... questions) {
        return new ManualStatementRequest(
            1L, 2L, "Prova de Exame de OGI", "Exame", 90, "B", null,
            null, null, CLASS_ID, null, false, List.of(questions));
    }

    private ManualStatementRequest.Item item(String text, String type, Double score) {
        return new ManualStatementRequest.Item(null, text, type, score, null);
    }

    private List<Question> savedQuestions() {
        ArgumentCaptor<Question> captor = ArgumentCaptor.forClass(Question.class);
        verify(questions, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void aGroupIsOnlyTheCommonStatementAndItsItemsAreTheQuestionsThatGetAnswered() {
        when(access.requireCanAuthor(9L, false, 1L, SUBJECT_ID, CLASS_ID)).thenReturn(Mono.empty());
        givenSaves();

        // The real exam: Q1 open, Q3 true/false with items a-d, Q5 composed with items a-b.
        ManualStatementRequest paper = paperOf(
            new ManualStatementRequest.Question("O que é aprovisionamento?", null, null, null, null),
            new ManualStatementRequest.Question("Diga quais são verdadeiras (V) ou falsas (F)", null, null, "group", List.of(
                item("Deve ser preocupação comprar ao melhor preço.", "true_false", 1.0),
                item("A escolha das matérias só depois das aplicações.", "true_false", 1.0),
                item("A escolha tem em conta o tratamento térmico.", "true_false", 1.0),
                item("O tipo adquirido deve estar adaptado ao processo.", "true_false", 1.0))),
            new ManualStatementRequest.Question("Uma empresa usa o Lote Económico...", null, null, null, List.of(
                item("Demonstre a expressão do custo de manutenção.", "open", 2.0),
                item("Calcule o custo anual e mensal.", "open", 3.0))));

        StepVerifier.create(service.create(paper, 9L, false)).expectNextCount(1).verifyComplete();

        List<Question> saved = savedQuestions();
        // 1 open + (1 header + 4 items) + (1 header + 2 items)
        assertThat(saved).hasSize(9);
        // The order of the paper is order_index, flattened: number alone cannot say it (3 < 4 < 301).
        assertThat(saved).extracting(Question::getOrderIndex).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);

        Question header = saved.stream().filter(q -> q.getNumber() == 2).findFirst().orElseThrow();
        assertThat(header.getQuestionType()).isEqualTo("group");
        assertThat(header.getMaxScore()).as("a group header has no score of its own").isNull();
        assertThat(header.getParentId()).isNull();

        // The items hang under their group, numbered 201.. so the order and the printed numbers survive.
        List<Question> items = saved.stream().filter(q -> q.getParentId() != null && q.getParentId() == header.getId()).toList();
        assertThat(items).extracting(Question::getNumber).containsExactly(201, 202, 203, 204);
        assertThat(items).extracting(Question::getLabel).containsExactly("a", "b", "c", "d");
        assertThat(items).extracting(Question::getQuestionType).containsOnly("true_false");

        Question composed = saved.stream().filter(q -> q.getNumber() == 3).findFirst().orElseThrow();
        assertThat(composed.getQuestionType()).as("items without a type on the group: derived from the items").isEqualTo("group");
        assertThat(saved.stream().filter(q -> q.getNumber() >= 301)).extracting(Question::getLabel).containsExactly("a", "b");

        ArgumentCaptor<Statement> statement = ArgumentCaptor.forClass(Statement.class);
        verify(statements).save(statement.capture());
        assertThat(statement.getValue().getTotalMaxScore()).as("only the items score: 4 + 5").isEqualTo(9.0);
        assertThat(statement.getValue().getDurationMinutes()).isEqualTo(90);
    }

    @Test
    void trueFalseGetsItsTwoOptionsAndTheTeacherMarksWhichOneIsRight() {
        when(access.requireCanAuthor(9L, false, 1L, SUBJECT_ID, CLASS_ID)).thenReturn(Mono.empty());
        givenSaves();

        ManualStatementRequest paper = paperOf(new ManualStatementRequest.Question(
            "O Sol é uma estrela.", 1.0, null, "true_false", null));

        StepVerifier.create(service.create(paper, 9L, false)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<QuestionOption> captor = ArgumentCaptor.forClass(QuestionOption.class);
        verify(options, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(QuestionOption::getOptionText).containsExactly("Verdadeiro", "Falso");
        assertThat(captor.getAllValues()).extracting(QuestionOption::getIsCorrect)
            .as("no answer is invented: the approval gate asks the teacher to mark one").containsOnly(false);
        assertThat(savedQuestions().get(0).getQuestionType()).isEqualTo("true_false");
    }

    @Test
    void aTrueFalseQuestionKeepsTheAnswerTheTeacherMarked() {
        when(access.requireCanAuthor(9L, false, 1L, SUBJECT_ID, CLASS_ID)).thenReturn(Mono.empty());
        givenSaves();

        ManualStatementRequest paper = paperOf(new ManualStatementRequest.Question(
            "O Sol é uma estrela.", 1.0, List.of(
                new ManualStatementRequest.Option("A", "Verdadeiro", true),
                new ManualStatementRequest.Option("B", "Falso", false)), "true_false", null));

        StepVerifier.create(service.create(paper, 9L, false)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<QuestionOption> captor = ArgumentCaptor.forClass(QuestionOption.class);
        verify(options, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(QuestionOption::getIsCorrect).containsExactly(true, false);
    }

    @Test
    void theOldRequestWithoutTypeStillDerivesItFromTheOptions() {
        when(access.requireCanAuthor(9L, false, 1L, 2L, CLASS_ID)).thenReturn(Mono.empty());
        givenSaves();

        StepVerifier.create(service.create(request(), 9L, false)).expectNextCount(1).verifyComplete();

        assertThat(savedQuestions()).extracting(Question::getQuestionType).containsExactly("open", "multiple_choice");
        assertThat(savedQuestions()).extracting(Question::getParentId).containsOnlyNulls();
    }

    @Test
    void aBadlyShapedPaperIsA400BeforeAnythingIsWritten() {
        List<ManualStatementRequest> bad = List.of(
            paperOf(new ManualStatementRequest.Question("g", null, null, "group", null)),
            paperOf(new ManualStatementRequest.Question("g", null, null, "group", List.of())),
            paperOf(new ManualStatementRequest.Question("q", null, null, "open", List.of(item("a", "open", 1.0)))),
            paperOf(new ManualStatementRequest.Question("g", null, List.of(
                new ManualStatementRequest.Option("A", "x", true)), "group", List.of(item("a", "open", 1.0)))),
            paperOf(new ManualStatementRequest.Question("g", null, null, "group", List.of(item("a", "group", 1.0)))),
            paperOf(new ManualStatementRequest.Question("g", null, null, "group", List.of(
                new ManualStatementRequest.Item("a", "x", "open", 1.0, null),
                new ManualStatementRequest.Item("a", "y", "open", 1.0, null)))),
            paperOf(new ManualStatementRequest.Question("q", null, List.of(
                new ManualStatementRequest.Option("A", "x", true)), "open", null)),
            paperOf(new ManualStatementRequest.Question("q", null, null, "multiple_choice", null)),
            paperOf(new ManualStatementRequest.Question("q", null, List.of(
                new ManualStatementRequest.Option("A", "x", true)), "true_false", null)),
            paperOf(new ManualStatementRequest.Question("q", null, null, "essay", null)));

        for (ManualStatementRequest paper : bad) {
            StepVerifier.create(service.create(paper, 9L, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
        }
        verifyNoInteractions(statements, questions, options);
    }
}
