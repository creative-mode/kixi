package ao.creativemode.kixi.training.controller;

import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.training.dto.MatrixPracticeRequest;
import ao.creativemode.kixi.training.dto.MatrixPracticeResponse;
import ao.creativemode.kixi.training.dto.TrainingPlanResponse;
import ao.creativemode.kixi.training.service.MatrixPracticeService;
import ao.creativemode.kixi.training.service.TrainingPlanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** O treino por temas do aluno para uma prova agendada (kixi#729): o plano e a simulação montada a partir dele. */
@RestController
@RequestMapping("/api/v1")
public class TrainingController {

    private final TrainingPlanService plans;
    private final MatrixPracticeService practice;
    private final CurrentAccountService current;

    public TrainingController(TrainingPlanService plans, MatrixPracticeService practice, CurrentAccountService current) {
        this.plans = plans;
        this.practice = practice;
        this.current = current;
    }

    @GetMapping("/training/rooms/{roomId}/plan")
    public Mono<ResponseEntity<TrainingPlanResponse>> plan(@PathVariable Long roomId) {
        return current.requiredAccountId().flatMap(account -> plans.plan(roomId, account)).map(ResponseEntity::ok);
    }

    @PostMapping("/simulations/practice/matrix")
    public Mono<ResponseEntity<MatrixPracticeResponse>> start(@Valid @RequestBody MatrixPracticeRequest request) {
        return current.requiredAccountId().flatMap(account -> practice.start(account, request))
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }
}
