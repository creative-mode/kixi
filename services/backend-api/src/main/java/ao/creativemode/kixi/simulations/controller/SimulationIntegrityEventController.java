package ao.creativemode.kixi.simulations.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.simulations.dto.integrity.SimulationIntegrityEventRequest;
import ao.creativemode.kixi.simulations.dto.integrity.SimulationIntegrityEventResponse;
import ao.creativemode.kixi.simulations.service.SimulationIntegrityEventService;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping({"/api/v1/simulations", "/api/simulations"})
public class SimulationIntegrityEventController {
    private final SimulationIntegrityEventService service;
    private final CurrentAccountService currentAccount;

    public SimulationIntegrityEventController(SimulationIntegrityEventService service,
            CurrentAccountService currentAccount) {
        this.service = service;
        this.currentAccount = currentAccount;
    }

    @PostMapping("/{id}/integrity-events")
    public Mono<ResponseEntity<SimulationIntegrityEventResponse>> record(
            @PathVariable Long id, @Valid @RequestBody SimulationIntegrityEventRequest request) {
        return currentAccount.requiredAccountId()
                .flatMap(accountId -> service.record(id, accountId, request))
                .map(event -> ResponseEntity.status(HttpStatus.CREATED).body(event));
    }
}
