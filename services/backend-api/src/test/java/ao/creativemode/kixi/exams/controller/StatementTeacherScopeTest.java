package ao.creativemode.kixi.exams.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.service.ManualStatementService;
import ao.creativemode.kixi.exams.service.StatementReadAccessService;
import ao.creativemode.kixi.exams.service.StatementService;
import ao.creativemode.kixi.exams.service.StatementWithQuestions;
import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.institutions.dto.assignment.TeachingAssignmentResponse;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClientConfigurer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * #174: a teacher reads the statements of their classes and subjects, and only
 * those. Teacher A teaches class 1 / subject 10, teacher B class 2 / subject 20.
 */
@WebFluxTest(controllers = StatementController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class, CurrentAccountService.class,
        JwtAuthenticationFilter.class, RequestIdWebFilter.class, StatementReadAccessService.class})
class StatementTeacherScopeTest {

    private static final long TEACHER_A = 7L;
    private static final long TEACHER_B = 8L;

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private StatementService statementService;

    @MockBean
    private ManualStatementService manualStatementService;

    @MockBean
    private TeachingAssignmentService teachingAssignments;

    private Statement ofA;
    private Statement ofB;
    private Statement legacy;
    private Statement schoolWide;

    @BeforeEach
    void setUp() {
        ofA = statement(1L, 100L, 1L, 10L);
        ofB = statement(2L, 100L, 2L, 20L);
        legacy = statement(3L, null, null, null);
        schoolWide = statement(4L, 100L, null, 10L);
        when(teachingAssignments.findMineForAccount(TEACHER_A)).thenReturn(Flux.just(assignment(1L, 10L)));
        when(teachingAssignments.findMineForAccount(TEACHER_B)).thenReturn(Flux.just(assignment(2L, 20L)));
        when(statementService.findAllActive()).thenReturn(Flux.just(ofA, ofB, legacy, schoolWide));
        when(statementService.findNeedingReview()).thenReturn(Flux.just(ofA, ofB));
    }

    @Test
    void teacherAOnlyListsTheirOwnStatements() {
        assertThat(idsOf("/api/v1/statements", teacher(TEACHER_A))).containsExactly(1, 3);
        assertThat(idsOf("/api/v1/statements", teacher(TEACHER_B))).containsExactly(2, 3);
    }

    @Test
    void theReviewQueueIsFilteredTheSameWay() {
        assertThat(idsOf("/api/v1/statements/review", teacher(TEACHER_A))).containsExactly(1);
        assertThat(idsOf("/api/v1/statements/review", teacher(TEACHER_B))).containsExactly(2);
    }

    @Test
    void anAdministratorStillSeesEverything() {
        assertThat(idsOf("/api/v1/statements", admin())).containsExactly(1, 2, 3, 4);
        assertThat(idsOf("/api/v1/statements/review", admin())).containsExactly(1, 2);
    }

    @Test
    void aTeacherWithoutAssignmentsOnlySeesTheStatementsNoClassOwns() {
        when(teachingAssignments.findMineForAccount(TEACHER_A)).thenReturn(Flux.empty());

        assertThat(idsOf("/api/v1/statements", teacher(TEACHER_A))).containsExactly(3);
    }

    @Test
    void anotherTeachersStatementIsForbiddenNotMissing() {
        when(statementService.findById(2L)).thenReturn(Mono.just(ofB));

        client.mutateWith(teacher(TEACHER_A)).get().uri("/api/v1/statements/2")
                .exchange()
                .expectStatus().isForbidden();
        client.mutateWith(teacher(TEACHER_B)).get().uri("/api/v1/statements/2")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void theFullStatementIsForbiddenToo() {
        when(statementService.findByIdWithQuestions(2L))
                .thenReturn(Mono.just(new StatementWithQuestions(ofB, List.of(), List.of())));

        client.mutateWith(teacher(TEACHER_A)).get().uri("/api/v1/statements/2/full")
                .exchange()
                .expectStatus().isForbidden();
    }

    private List<Integer> idsOf(String uri, WebTestClientConfigurer who) {
        return client.mutateWith(who).get().uri(uri)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(java.util.Map.class)
                .returnResult()
                .getResponseBody()
                .stream()
                .map(row -> ((Number) row.get("id")).intValue())
                .toList();
    }

    private static Statement statement(Long id, Long institutionId, Long classId, Long subjectId) {
        Statement statement = new Statement("P" + id, "Prova " + id);
        statement.setId(id);
        statement.setInstitutionId(institutionId);
        statement.setClassId(classId);
        statement.setSubjectId(subjectId);
        return statement;
    }

    private static TeachingAssignmentResponse assignment(Long classId, Long subjectId) {
        return new TeachingAssignmentResponse(1L, 1L, classId, subjectId, 1L, null, null, null);
    }

    private static WebTestClientConfigurer teacher(long accountId) {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(accountId), null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }

    private static WebTestClientConfigurer admin() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "1", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
}
