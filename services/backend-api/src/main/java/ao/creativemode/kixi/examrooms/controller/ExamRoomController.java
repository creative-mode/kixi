package ao.creativemode.kixi.examrooms.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import ao.creativemode.kixi.examrooms.dto.ExamRoomParticipantRequest;
import ao.creativemode.kixi.examrooms.dto.ExamRoomParticipantResponse;
import ao.creativemode.kixi.examrooms.dto.ExamRoomRequest;
import ao.creativemode.kixi.examrooms.dto.ExamRoomResponse;
import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;
import ao.creativemode.kixi.examrooms.service.ExamRoomService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping({"/api/v1/exam-rooms", "/api/exam-rooms"})
public class ExamRoomController {
    private final ExamRoomService service;
    private final CurrentAccountService current;

    public ExamRoomController(ExamRoomService service, CurrentAccountService current) {
        this.service = service;
        this.current = current;
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
    public Mono<ResponseEntity<List<ExamRoomParticipantResponse>>> mine() {
        return current.requiredAccountId().flatMap(id -> service.mineAsStudent(id).collectList())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/owned")
    public Mono<ResponseEntity<List<ExamRoomResponse>>> owned() {
        return current.requiredAccountId().zipWith(current.hasAnyRole("ADMIN", "TEACHER"))
                .flatMapMany(tuple -> tuple.getT2() ? service.findMine(tuple.getT1()) : reactor.core.publisher.Flux.empty())
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
