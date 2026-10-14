package ao.creativemode.kixi.examrooms.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.examrooms.dto.ExamRoomParticipantResponse;
import ao.creativemode.kixi.examrooms.dto.ExamRoomResponse;
import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;
import ao.creativemode.kixi.examrooms.service.ExamRoomMonitoringService;
import ao.creativemode.kixi.examrooms.service.ExamRoomService;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = ExamRoomController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class ExamRoomControllerHttpTest {
    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;
    @MockBean
    private ExamRoomService service;
    @MockBean
    private ExamRoomMonitoringService monitoring;

    @Test
    void protectsEveryRoomOperationFromAnonymousUsers() {
        client.post().uri("/api/v1/exam-rooms").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/exam-rooms/1/participants").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/exam-rooms/1/join").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/exam-rooms/1/open").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void teacherCreatesInvitesAndTransitionsOwnedRoom() {
        ExamRoomResponse room = room(7L, ExamRoomStatus.DRAFT);
        when(service.create(any(), eq(42L), eq(false))).thenReturn(Mono.just(room));
        when(service.addParticipant(eq(7L), any(), eq(42L), eq(false)))
                .thenReturn(Mono.just(new ExamRoomParticipantResponse(7L, 99L, null, null)));
        when(service.transition(eq(7L), eq(42L), eq(false), any(ExamRoomStatus.class)))
                .thenAnswer(invocation -> Mono.just(room(7L, invocation.getArgument(3))));

        client.mutateWith(teacherJwt()).post().uri("/api/v1/exam-rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("statementId", 5, "classId", 3,
                        "startsAt", "2026-10-13T10:00:00", "endsAt", "2026-10-13T11:00:00",
                        "durationMinutes", 60))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.id").isEqualTo(7).jsonPath("$.status").isEqualTo("DRAFT");

        client.mutateWith(teacherJwt()).post().uri("/api/v1/exam-rooms/7/participants")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("accountId", 99))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.accountId").isEqualTo(99);
        client.mutateWith(teacherJwt()).post().uri("/api/v1/exam-rooms/7/open")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("OPEN");
        client.mutateWith(teacherJwt()).post().uri("/api/v1/exam-rooms/7/start")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("RUNNING");
        client.mutateWith(teacherJwt()).post().uri("/api/v1/exam-rooms/7/close")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("CLOSED");
    }

    @Test
    void studentCanJoinButCannotManageOrInvite() {
        when(service.join(42L, 99L)).thenReturn(Mono.just(new ExamRoomParticipantResponse(42L, 99L, 123L, null)));

        client.mutateWith(studentJwt()).post().uri("/api/v1/exam-rooms/42/join")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.simulationId").isEqualTo(123);
        client.mutateWith(studentJwt()).post().uri("/api/v1/exam-rooms/42/open")
                .exchange().expectStatus().isForbidden();
        client.mutateWith(studentJwt()).post().uri("/api/v1/exam-rooms/42/participants")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("accountId", 100))
                .exchange().expectStatus().isForbidden();
        verify(service).join(42L, 99L);
    }

    @Test
    void adminMayManageRoomAndStudentReadsOwnInvitations() {
        when(service.transition(7L, 1L, true, ExamRoomStatus.CLOSED)).thenReturn(Mono.just(room(7L, ExamRoomStatus.CLOSED)));
        when(service.mineAsStudent(99L)).thenReturn(Flux.empty());

        client.mutateWith(adminJwt()).post().uri("/api/v1/exam-rooms/7/close")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("CLOSED");
        client.mutateWith(studentJwt()).get().uri("/api/v1/exam-rooms/mine")
                .exchange().expectStatus().isOk().expectBody().json("[]");
    }

    private static ExamRoomResponse room(Long id, ExamRoomStatus status) {
        return new ExamRoomResponse(id, 5L, 3L, 42L,
                LocalDateTime.of(2026, 10, 13, 10, 0), LocalDateTime.of(2026, 10, 13, 11, 0), 60, status);
    }

    private static WebTestClientConfigurer studentJwt() {
        return jwt("99", "ROLE_STUDENT");
    }
    private static WebTestClientConfigurer teacherJwt() {
        return jwt("42", "ROLE_TEACHER");
    }
    private static WebTestClientConfigurer adminJwt() {
        return jwt("1", "ROLE_ADMIN");
    }
    private static WebTestClientConfigurer jwt(String account, String role) {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(account, null,
                List.of(new SimpleGrantedAuthority(role))));
    }
}
