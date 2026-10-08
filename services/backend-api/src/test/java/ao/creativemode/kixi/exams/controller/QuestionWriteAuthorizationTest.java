package ao.creativemode.kixi.exams.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

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
import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.exams.service.QuestionOptionService;
import ao.creativemode.kixi.exams.service.QuestionService;
import ao.creativemode.kixi.exams.service.StatementWriteAccessService;
import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionSubjectRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.institutions.repository.TeachingAssignmentRepository;
import ao.creativemode.kixi.institutions.service.InstitutionAccessService;
import ao.creativemode.kixi.institutions.service.TeachingAssignmentService;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClientConfigurer;
import reactor.core.publisher.Mono;

/**
 * The nested question and option routes with the real services and the real
 * write rule in place.
 *
 * <p>{@link StatementApprovalAuthorizationTest} does this for the statement
 * routes, and it exists because mocking the service hides the very thing that
 * goes wrong: a rule that answers 403 to someone who should have been let in.
 * The same reasoning applies here, and there is more to it. These routes are
 * three ids deep, so a rule left out of one of them is not caught by any
 * service unit test either — it is only visible when a teacher outside the
 * class puts someone else's question id in the path and gets a 403 instead of
 * a 200.
 */
@WebFluxTest(controllers = {QuestionController.class, QuestionOptionController.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class,
        QuestionService.class, QuestionOptionService.class, StatementWriteAccessService.class,
        InstitutionAccessService.class, TeachingAssignmentService.class})
class QuestionWriteAuthorizationTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long TEACHER_ACCOUNT_ID = 7L;
    private static final Long TEACHER_ID = 5L;
    private static final Long INSTITUTION_ID = 4L;
    private static final Long CLASS_ID = 3L;
    private static final Long OTHER_CLASS_ID = 30L;
    private static final Long COURSE_ID = 40L;
    private static final Long SUBJECT_ID = 5L;
    private static final Long SCHOOL_YEAR_ID = 2024L;
    private static final Long STATEMENT_ID = 1L;
    private static final Long QUESTION_ID = 2L;
    private static final Long OPTION_ID = 3L;

    private static final String QUESTION =
            "/api/v1/statements/" + STATEMENT_ID + "/questions/" + QUESTION_ID;
    private static final String OPTIONS = QUESTION + "/options";
    private static final String QUESTIONS = "/api/v1/statements/" + STATEMENT_ID + "/questions";

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private StatementRepository statements;

    @MockBean
    private QuestionRepository questions;

    @MockBean
    private QuestionOptionRepository options;

    @MockBean
    private InstitutionRepository institutions;

    @MockBean
    private InstitutionSubjectRepository institutionSubjects;

    @MockBean
    private InstitutionTeacherRepository institutionTeachers;

    @MockBean
    private TeacherRepository teachers;

    @MockBean
    private TeachingAssignmentRepository assignments;

    @MockBean
    private ClassRepository classes;

    @MockBean
    private SubjectRepository subjects;

    @MockBean
    private CourseRepository courses;

    @MockBean
    private SchoolYearRepository schoolYears;

    @MockBean
    private TermRepository terms;

    private Statement statement;

    @BeforeEach
    void setUp() {
        statement = new Statement("P1", "Prova de Matemática");
        statement.setId(STATEMENT_ID);
        statement.setInstitutionId(INSTITUTION_ID);
        statement.setClassId(CLASS_ID);
        statement.setSubjectId(SUBJECT_ID);
    }

    // ── Who may write ───────────────────────────────────────────────────────

    @Test
    void aTeacherAssignedToTheClassAddsAQuestion() {
        givenTheTeacherTeachesTheClass();
        givenTheQuestionIsWritable();

        client.mutateWith(teacherJwt())
                .post()
                .uri(QUESTIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(questionPayload())
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void aTeacherAssignedToAnotherClassIsForbiddenFromAddingAQuestion() {
        // The statement id is in the path, and the rule weighs that statement.
        // Dropping the call would let any teacher write on any statement.
        givenTheTeacherTeachesAnotherClass();
        givenTheStatementIsThere();

        client.mutateWith(teacherJwt())
                .post()
                .uri(QUESTIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(questionPayload())
                .exchange()
                .expectStatus().isForbidden();

        verify(questions, never()).save(any(Question.class));
    }

    @Test
    void aTeacherAssignedToAnotherClassIsForbiddenFromMarkingAnAnswer() {
        // The one that matters most: it is how the answer key of a statement
        // someone else wrote gets set.
        givenTheTeacherTeachesAnotherClass();
        givenTheStatementIsThere();

        client.mutateWith(teacherJwt())
                .put()
                .uri(QUESTION + "/correct-option")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("optionId", OPTION_ID))
                .exchange()
                .expectStatus().isForbidden();

        verify(options, never()).setCorrectOption(anyLong(), anyLong());
    }

    @Test
    void aTeacherAssignedToAnotherClassIsForbiddenFromEditingAnOption() {
        givenTheTeacherTeachesAnotherClass();
        givenTheStatementIsThere();

        client.mutateWith(teacherJwt())
                .put()
                .uri(OPTIONS + "/" + OPTION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(optionPayload())
                .exchange()
                .expectStatus().isForbidden();

        verify(options, never()).save(any(QuestionOption.class));
    }

    @Test
    void aTeacherAssignedToAnotherClassIsForbiddenFromDeletingAQuestion() {
        givenTheTeacherTeachesAnotherClass();
        givenTheStatementIsThere();

        client.mutateWith(teacherJwt())
                .delete()
                .uri(QUESTION)
                .exchange()
                .expectStatus().isForbidden();

        verify(questions, never()).save(any(Question.class));
    }

    @Test
    void anAdministratorWhoNeverTaughtMarksAnAnswer() {
        // The regression StatementApprovalAuthorizationTest exists for, on these
        // routes: the rule must reach its answer without first resolving a
        // teacher profile, or an administrator gets refused their own school.
        givenTheInstitutionTeachesTheSubject();
        when(teachers.findByAccountIdAndDeletedAtIsNull(ADMIN_ID)).thenReturn(Mono.empty());
        givenTheStatementIsThere();
        givenTheQuestionIsThere();
        givenTheOptionIsThere();

        client.mutateWith(adminJwt())
                .put()
                .uri(QUESTION + "/correct-option")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("optionId", OPTION_ID))
                .exchange()
                .expectStatus().isOk();
    }

    // ── An id from somewhere else ───────────────────────────────────────────

    @Test
    void aQuestionOfAnotherStatementIsNotFound() {
        // Right teacher, wrong statement. Not 403: the caller may well write on
        // their own statement, so this is the answer being out of reach, not the
        // caller.
        givenTheTeacherTeachesTheClass();
        givenTheStatementIsThere();
        Question elsewhere = question();
        elsewhere.setStatementId(99L);
        when(questions.findByIdAndDeletedAtIsNull(QUESTION_ID)).thenReturn(Mono.just(elsewhere));

        client.mutateWith(teacherJwt())
                .delete()
                .uri(QUESTION)
                .exchange()
                .expectStatus().isNotFound();

        verify(questions, never()).save(any(Question.class));
    }

    @Test
    void anOptionOfAnotherQuestionIsNotFound() {
        givenTheTeacherTeachesTheClass();
        givenTheStatementIsThere();
        givenTheQuestionIsThere();
        QuestionOption elsewhere = option();
        elsewhere.setQuestionId(99L);
        when(options.findByIdAndDeletedAtIsNull(OPTION_ID)).thenReturn(Mono.just(elsewhere));

        client.mutateWith(teacherJwt())
                .put()
                .uri(OPTIONS + "/" + OPTION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(optionPayload())
                .exchange()
                .expectStatus().isNotFound();

        verify(options, never()).save(any(QuestionOption.class));
    }

    @Test
    void aStatementThatIsNotThereIsNotFoundRatherThanForbidden() {
        // Answering "forbidden" for an id that does not exist would tell any
        // authenticated caller which statement ids are real.
        givenTheTeacherTeachesTheClass();
        when(statements.findByIdAndDeletedAtIsNull(STATEMENT_ID)).thenReturn(Mono.empty());

        client.mutateWith(teacherJwt())
                .delete()
                .uri(QUESTION)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void aRemovedStatementIsNotReachable() {
        // findByIdAndDeletedAtIsNull, so a statement in the trash is a 404 even
        // for the teacher who owns it.
        givenTheTeacherTeachesTheClass();
        when(statements.findByIdAndDeletedAtIsNull(STATEMENT_ID)).thenReturn(Mono.empty());

        client.mutateWith(teacherJwt())
                .post()
                .uri(QUESTIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(questionPayload())
                .exchange()
                .expectStatus().isNotFound();
    }

    // ── Students ────────────────────────────────────────────────────────────

    @Test
    void aStudentMayReadTheQuestionsOfAPublishedStatement() {
        givenTheStatementIsPublished();
        when(questions.findAllByStatementIdOrderedByOrderIndex(STATEMENT_ID))
                .thenReturn(reactor.core.publisher.Flux.just(question()));

        client.mutateWith(studentJwt())
                .get()
                .uri(QUESTIONS)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void aStudentMayNotSeeTheAnswerKey() {
        // Reading the questions is open to any authenticated caller, so it would
        // be easy to assume the answer travels with them. It does not. The
        // fixture carries a model answer on purpose: with it null the assertion
        // below would pass whether or not the field were being withheld.
        givenTheStatementIsPublished();
        Question answered = question();
        answered.setModelAnswer("x = 1");
        answered.setNeedsReview(true);
        when(questions.findAllByStatementIdOrderedByOrderIndex(STATEMENT_ID))
                .thenReturn(reactor.core.publisher.Flux.just(answered));

        client.mutateWith(studentJwt())
                .get()
                .uri(QUESTIONS)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].text").isEqualTo("Resolva x+1=2")
                .jsonPath("$[0].modelAnswer").doesNotExist()
                .jsonPath("$[0].needsReview").doesNotExist();
    }

    @Test
    void staffDoSeeTheAnswerKey() {
        // The other half of the case above: withheld from a student, present for
        // someone who may correct the paper. Without both, the omission would be
        // indistinguishable from the field simply not existing.
        givenTheStatementIsThere();
        Question answered = question();
        answered.setModelAnswer("x = 1");
        answered.setNeedsReview(true);
        when(questions.findAllByStatementIdOrderedByOrderIndex(STATEMENT_ID))
                .thenReturn(reactor.core.publisher.Flux.just(answered));

        client.mutateWith(teacherJwt())
                .get()
                .uri(QUESTIONS)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].modelAnswer").isEqualTo("x = 1")
                .jsonPath("$[0].needsReview").isEqualTo(true);
    }

    @Test
    void aStudentMayNotSeeWhichOptionIsTheAnswer() {
        givenTheStatementIsPublished();
        givenTheQuestionIsThere();
        QuestionOption answered = option();
        answered.markAsCorrect();
        when(options.findAllByQuestionIdOrderedByOrderIndex(QUESTION_ID))
                .thenReturn(reactor.core.publisher.Flux.just(answered));

        client.mutateWith(studentJwt())
                .get()
                .uri(OPTIONS)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].optionText").isEqualTo("x = 1")
                .jsonPath("$[0].isCorrect").doesNotExist();
    }

    @Test
    void aStudentCannotReachADraftOfAnotherClass() {
        // The statement is not published, so a student is answered with the same
        // 404 as for an id that does not exist. Answering 200 would hand out
        // another class's paper, and answering 403 would confirm it exists.
        when(statements.findByIdAndVisibleTrueAndDeletedAtIsNull(STATEMENT_ID)).thenReturn(Mono.empty());

        client.mutateWith(studentJwt())
                .get()
                .uri(QUESTIONS)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void aStudentCannotListTheTrash() {
        givenTheStatementIsPublished();

        client.mutateWith(studentJwt())
                .get()
                .uri(QUESTIONS + "/trash")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void aStudentCannotAddAnOption() {
        givenTheStatementIsPublished();

        client.mutateWith(studentJwt())
                .post()
                .uri(OPTIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(optionPayload())
                .exchange()
                .expectStatus().isForbidden();

        verify(options, never()).save(any(QuestionOption.class));
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private void givenTheStatementIsThere() {
        when(statements.findByIdAndDeletedAtIsNull(STATEMENT_ID)).thenReturn(Mono.just(statement));
    }

    /**
     * The statement is published, which is what a reader is entitled to.
     *
     * <p>A separate fixture from {@link #givenTheStatementIsThere()} on purpose:
     * the two lookups are different queries, and stubbing only one of them is
     * how a student ends up reading a draft of another class by accident.
     */
    private void givenTheStatementIsPublished() {
        statement.setVisible(true);
        when(statements.findByIdAndVisibleTrueAndDeletedAtIsNull(STATEMENT_ID))
                .thenReturn(Mono.just(statement));
    }

    private void givenTheQuestionIsWritable() {
        givenTheStatementIsThere();
        when(questions.findNextQuestionNumber(STATEMENT_ID)).thenReturn(Mono.just(1));
        when(questions.findNextOrderIndex(STATEMENT_ID)).thenReturn(Mono.just(0));
        when(questions.save(any(Question.class))).thenAnswer(invocation -> {
            Question question = invocation.getArgument(0);
            question.setId(QUESTION_ID);
            return Mono.just(question);
        });
    }

    private void givenTheQuestionIsThere() {
        when(questions.findByIdAndDeletedAtIsNull(QUESTION_ID)).thenReturn(Mono.just(question()));
    }

    private void givenTheOptionIsThere() {
        when(options.findByIdAndDeletedAtIsNull(OPTION_ID)).thenReturn(Mono.just(option()));
        when(options.setCorrectOption(QUESTION_ID, OPTION_ID)).thenReturn(Mono.just(1));
    }

    private void givenTheTeacherTeachesTheClass() {
        givenTheInstitutionTeachesTheSubject();
        givenTheTeacherIsAffiliated();
        givenTheClass(CLASS_ID);
        when(assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.just(true));
    }

    private void givenTheTeacherTeachesAnotherClass() {
        givenTheInstitutionTeachesTheSubject();
        givenTheTeacherIsAffiliated();
        // The class on the statement, not the one the teacher holds: the rule
        // weighs the statement as it stands, and it is the assignment check that
        // has to fail, not the lookup of the class.
        givenTheClass(CLASS_ID);
        when(assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.just(false));
    }

    private void givenTheTeacherIsAffiliated() {
        Teacher teacher = new Teacher();
        teacher.setId(TEACHER_ID);
        teacher.setFirstName("Ana");
        teacher.setLastName("Costa");
        when(teachers.findByAccountIdAndDeletedAtIsNull(TEACHER_ACCOUNT_ID)).thenReturn(Mono.just(teacher));
        when(institutionTeachers.existsByInstitutionIdAndTeacherIdAndDeletedAtIsNull(
                INSTITUTION_ID, TEACHER_ID)).thenReturn(Mono.just(true));
    }

    private void givenTheClass(Long classId) {
        when(classes.findByIdAndDeletedAtIsNull(classId)).thenReturn(Mono.just(classOfYear(classId)));
    }

    private static Class classOfYear(Long classId) {
        Class klass = new Class();
        klass.setId(classId);
        klass.setGrade(12);
        klass.setSchoolYearId(SCHOOL_YEAR_ID);
        // classes.course_id is NOT NULL in V10.
        klass.setCourseId(COURSE_ID);
        return klass;
    }

    private void givenTheInstitutionTeachesTheSubject() {
        when(institutionSubjects.existsByInstitutionIdAndSubjectIdAndDeletedAtIsNull(
                INSTITUTION_ID, SUBJECT_ID)).thenReturn(Mono.just(true));
        // The access service resolves these for real, so the repositories behind
        // it have to answer: a Mockito mock hands back null and the null travels
        // on into the comparison.
        Subject subject = new Subject();
        subject.setId(SUBJECT_ID);
        when(subjects.findByIdAndDeletedAtIsNull(SUBJECT_ID)).thenReturn(Mono.just(subject));
        SchoolYear year = new SchoolYear();
        year.setId(SCHOOL_YEAR_ID);
        when(schoolYears.findByIdAndDeletedAtIsNull(anyLong())).thenReturn(Mono.just(year));
        // Also the school behind the statement: requireCanAuthor resolves it
        // before it weighs the class, so leaving it unstubbed hands back null and
        // the null travels on.
        Institution school = new Institution();
        school.setId(INSTITUTION_ID);
        when(institutions.findByIdAndDeletedAtIsNull(INSTITUTION_ID)).thenReturn(Mono.just(school));
    }

    private Question question() {
        Question question = new Question(STATEMENT_ID, 1, "Resolva x+1=2", "multiple_choice");
        question.setId(QUESTION_ID);
        return question;
    }

    private QuestionOption option() {
        QuestionOption option = new QuestionOption(QUESTION_ID, "B", "x = 1");
        option.setId(OPTION_ID);
        return option;
    }

    private static java.util.Map<String, Object> questionPayload() {
        return java.util.Map.of("text", "Resolva x+1=2", "maxScore", 5.0);
    }

    private static java.util.Map<String, Object> optionPayload() {
        return java.util.Map.of("optionLabel", "B", "optionText", "x = 1");
    }

    private static WebTestClientConfigurer adminJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(ADMIN_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private static WebTestClientConfigurer teacherJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(TEACHER_ACCOUNT_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }

    private static WebTestClientConfigurer studentJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "12",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }
}
