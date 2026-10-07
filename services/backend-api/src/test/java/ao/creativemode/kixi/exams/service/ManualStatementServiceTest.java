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
    private ManualStatementRequest requestWithoutClass() {
        return new ManualStatementRequest(
            1L, 2L, "Prova de Matemática", "Teste", 90, null, null,
            null, null, null, null, true,
            List.of(new ManualStatementRequest.Question("Resolva x+1=2", 5.0, null))
        );
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
}
