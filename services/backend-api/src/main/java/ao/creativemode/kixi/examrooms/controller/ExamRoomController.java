package ao.creativemode.kixi.examrooms.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;

import ao.creativemode.kixi.examrooms.dto.ExamRoomParticipantRequest;
import ao.creativemode.kixi.examrooms.dto.ExamRoomInvitationResponse;
import ao.creativemode.kixi.examrooms.dto.ExamRoomParticipantResponse;
import ao.creativemode.kixi.examrooms.dto.ExamRoomRequest;
import ao.creativemode.kixi.examrooms.dto.ExamRoomResponse;
import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;
import ao.creativemode.kixi.examrooms.service.ExamRoomService;
import ao.creativemode.kixi.examrooms.service.ExamRoomMonitoringService;
import ao.creativemode.kixi.examrooms.dto.ExamRoomGradeResponse;
import ao.creativemode.kixi.examrooms.dto.ExamRoomLiveResponse;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping({"/api/v1/exam-rooms", "/api/exam-rooms"})
public class ExamRoomController {
    private final ExamRoomService service;
    private final CurrentAccountService current;
    private final ExamRoomMonitoringService monitoring;
    private final ao.creativemode.kixi.examrooms.service.ExamRoomCorrectionService correction;

    public ExamRoomController(ExamRoomService service, CurrentAccountService current,
            ExamRoomMonitoringService monitoring) {
        this(service, current, monitoring, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ExamRoomController(ExamRoomService service, CurrentAccountService current,
            ExamRoomMonitoringService monitoring, ao.creativemode.kixi.examrooms.service.ExamRoomCorrectionService correction) {
        this.service = service;
        this.current = current;
        this.monitoring = monitoring;
        this.correction = correction;
    }

    @PostMapping
    public Mono<ResponseEntity<ExamRoomResponse>> create(@Valid @RequestBody ExamRoomRequest request) {
        return current.requiredAccountId().zipWith(current.hasAnyRole("ADMIN"))
                .zipWith(current.hasAnyRole("TEACHER"))
                .flatMap(tuple -> tuple.getT1().getT2() || tuple.getT2()
                        ? service.create(request, tuple.getT1().getT1(), tuple.getT1().getT2())
                        : Mono.error(ao.creativemode.kixi.shared.exception.ApiException.forbidden(
                                "Only teachers or ADMIN can create exam rooms")))
                .map(result -> ResponseEntity.status(HttpStatus.CREATED).body(result));
    }

    @GetMapping("/mine")
    public Mono<ResponseEntity<List<ExamRoomInvitationResponse>>> mine() {
        return current.requiredAccountId().flatMap(id -> service.mineAsStudent(id).collectList())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/owned")
    public Mono<ResponseEntity<List<ExamRoomResponse>>> owned(
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long institutionId) {
        return current.requiredAccountId().zipWith(current.hasAnyRole("ADMIN", "TEACHER"))
                .flatMapMany(tuple -> tuple.getT2() ? service.findMine(tuple.getT1(), institutionId) : reactor.core.publisher.Flux.empty())
                .collectList().map(ResponseEntity::ok);
    }

    @PostMapping("/{id}/participants")
    public Mono<ResponseEntity<ExamRoomParticipantResponse>> addParticipant(@PathVariable Long id,
            @Valid @RequestBody ExamRoomParticipantRequest request) {
        return current.requiredAccountId().zipWith(current.hasAnyRole("ADMIN"))
                .zipWith(current.hasAnyRole("TEACHER"))
                .flatMap(tuple -> tuple.getT1().getT2() || tuple.getT2()
                        ? service.addParticipant(id, request, tuple.getT1().getT1(), tuple.getT1().getT2())
                        : Mono.error(ao.creativemode.kixi.shared.exception.ApiException.forbidden(
                                "Only teachers or ADMIN can manage exam rooms")))
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{id}/open")
    public Mono<ResponseEntity<ExamRoomResponse>> open(@PathVariable Long id) {
        return transition(id, ExamRoomStatus.OPEN);
    }

    @PostMapping("/{id}/start")
    public Mono<ResponseEntity<ExamRoomResponse>> start(@PathVariable Long id) {
        return transition(id, ExamRoomStatus.RUNNING);
    }

    @PostMapping("/{id}/close")
    public Mono<ResponseEntity<ExamRoomResponse>> close(@PathVariable Long id) {
        return transition(id, ExamRoomStatus.CLOSED);
    }

    @PostMapping("/{id}/join")
    public Mono<ResponseEntity<ExamRoomParticipantResponse>> join(@PathVariable Long id) {
        return current.requiredAccountId().flatMap(accountId -> service.join(id, accountId))
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}/student")
    public Mono<ResponseEntity<ao.creativemode.kixi.examrooms.dto.ExamRoomStudentResponse>> studentView(
            @PathVariable Long id) {
        return current.requiredAccountId().flatMap(accountId -> service.studentView(id, accountId))
                .map(ResponseEntity::ok);
    }

    @GetMapping(value = "/{id}/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Mono<ResponseEntity<Flux<ServerSentEvent<ExamRoomLiveResponse>>>> live(@PathVariable Long id) {
        // #188: authorize with the room alone; the stream builds the first
        // snapshot, instead of one here only to throw it away.
        return staffContext().flatMap(context -> monitoring.authorize(id, context.accountId(), context.admin())
                .map(room -> ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                        .body(monitoring.stream(room, context.accountId(), context.admin())
                                .map(snapshot -> ServerSentEvent.<ExamRoomLiveResponse>builder()
                                        .event("progress").data(snapshot).build()))));
    }

    @GetMapping("/{id}/grades")
    public Mono<ResponseEntity<List<ExamRoomGradeResponse>>> grades(@PathVariable Long id) {
        return staffContext().flatMap(context -> monitoring.grades(id, context.accountId(), context.admin()))
                .map(ResponseEntity::ok);
    }

    /** As respostas abertas da sala, questão a questão, com a chave ao lado. */
    @GetMapping("/{id}/review")
    public Mono<ResponseEntity<ao.creativemode.kixi.examrooms.dto.ExamRoomReviewResponse>> review(@PathVariable Long id) {
        return staffContext().flatMap(context -> correction.review(id, context.accountId(), context.admin()))
                .map(ResponseEntity::ok);
    }

    /** Grava as notas das respostas abertas de uma vez e, se pedido, corrige pela chave. */
    @PostMapping("/{id}/grade")
    public Mono<ResponseEntity<ao.creativemode.kixi.examrooms.dto.ExamRoomBulkGradeResponse>> gradeInBulk(
            @PathVariable Long id, @Valid @RequestBody ao.creativemode.kixi.examrooms.dto.ExamRoomBulkGradeRequest request) {
        return staffContext().flatMap(context -> correction.grade(id, context.accountId(), context.admin(), request))
                .map(ResponseEntity::ok);
    }

    @GetMapping(value = "/{id}/grades.csv", produces = "text/csv")
    public Mono<ResponseEntity<String>> gradesCsv(@PathVariable Long id) {
        return staffContext().flatMap(context -> monitoring.grades(id, context.accountId(), context.admin()))
                .map(this::toCsv)
                .map(csv -> ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
                        .header("Content-Disposition", "attachment; filename=exam-room-" + id + "-grades.csv")
                        .body(csv));
    }

    private Mono<StaffContext> staffContext() {
        return current.requiredAccountId().zipWith(current.hasAnyRole("ADMIN"))
                .zipWith(current.hasAnyRole("TEACHER"))
                .flatMap(tuple -> tuple.getT1().getT2() || tuple.getT2()
                        ? Mono.just(new StaffContext(tuple.getT1().getT1(), tuple.getT1().getT2()))
                        : Mono.error(ao.creativemode.kixi.shared.exception.ApiException.forbidden(
                                "Only teachers or ADMIN can view exam room progress")));
    }

    private String toCsv(List<ExamRoomGradeResponse> grades) {
        StringBuilder csv = new StringBuilder("accountId,name,simulationId,status,finalScore,correctAnswers,totalQuestions,pendingReviewCount,finishedAt\r\n");
        grades.forEach(grade -> csv.append(value(grade.accountId())).append(',').append(value(grade.displayName())).append(',')
                .append(value(grade.simulationId())).append(',')
                .append(value(grade.status())).append(',')
                .append(value(grade.finalScore())).append(',')
                .append(value(grade.correctAnswers())).append(',')
                .append(value(grade.totalQuestions())).append(',')
                .append(value(grade.pendingReviewCount())).append(',')
                .append(value(grade.finishedAt())).append("\r\n"));
        return csv.toString();
    }

    private String value(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (value instanceof String && !text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\r') >= 0
                || text.indexOf('\n') >= 0
                ? '"' + text.replace("\"", "\"\"") + '"'
                : text;
    }

    private record StaffContext(Long accountId, boolean admin) { }

    private Mono<ResponseEntity<ExamRoomResponse>> transition(Long id, ExamRoomStatus status) {
        return current.requiredAccountId().zipWith(current.hasAnyRole("ADMIN"))
                .zipWith(current.hasAnyRole("TEACHER"))
                .flatMap(tuple -> tuple.getT1().getT2() || tuple.getT2()
                        ? service.transition(id, tuple.getT1().getT1(), tuple.getT1().getT2(), status)
                        : Mono.error(ao.creativemode.kixi.shared.exception.ApiException.forbidden(
                                "Only teachers or ADMIN can manage exam rooms")))
                .map(ResponseEntity::ok);
    }
}
