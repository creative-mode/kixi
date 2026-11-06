package ao.creativemode.kixi.simulations.controller;

import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.simulations.dto.simulation.SimulationResponse;
import ao.creativemode.kixi.simulations.service.PracticeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** «Montar treino» (kixi#729): começa uma simulação livre com N questões de um enunciado. */
@RestController
@RequestMapping("/api/v1/simulations/practice")
public class PracticeController {

    public record PracticeRequest(@NotNull Long statementId, @NotNull @Min(1) Integer questionCount) {}

    private final PracticeService practice;
    private final CurrentAccountService currentAccount;

    public PracticeController(PracticeService practice, CurrentAccountService currentAccount) {
        this.practice = practice;
        this.currentAccount = currentAccount;
    }

    @PostMapping
    public Mono<ResponseEntity<SimulationResponse>> start(@Valid @RequestBody PracticeRequest request) {
        return currentAccount.requiredAccountId()
                .flatMap(accountId -> practice.start(accountId, request.statementId(), request.questionCount()))
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }
}
