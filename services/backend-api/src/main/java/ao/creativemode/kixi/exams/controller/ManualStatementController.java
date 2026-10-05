package ao.creativemode.kixi.exams.controller;

import ao.creativemode.kixi.exams.controller.StatementController.StatementSummary;
import ao.creativemode.kixi.exams.dto.statement.ManualStatementRequest;
import ao.creativemode.kixi.exams.service.ManualStatementService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;

/** Statements written by hand in the exam builder (ADMIN and TEACHER, enforced in the security config). */
@RestController
@RequestMapping("/api/v1/statements/manual")
public class ManualStatementController {

    private final ManualStatementService service;
    private final CurrentAccountService currentAccountService;

    public ManualStatementController(ManualStatementService service, CurrentAccountService currentAccountService) {
        this.service = service;
        this.currentAccountService = currentAccountService;
    }

    @PostMapping
    public Mono<ResponseEntity<StatementSummary>> create(
        @Valid @RequestBody ManualStatementRequest request,
        UriComponentsBuilder uriBuilder
    ) {
        return Mono.zip(currentAccountService.requiredAccountId(), currentAccountService.hasAnyRole("ADMIN"))
            .flatMap(account -> service.create(request, account.getT1(), account.getT2()))
            .map(statement -> {
                URI location = uriBuilder
                    .path("/api/v1/statements/{id}")
                    .buildAndExpand(statement.getId())
                    .toUri();
                return ResponseEntity.created(location).body(StatementSummary.from(statement));
            });
    }
}
