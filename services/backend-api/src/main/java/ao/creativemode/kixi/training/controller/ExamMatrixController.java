package ao.creativemode.kixi.training.controller;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.training.dto.MatrixRequest;
import ao.creativemode.kixi.training.dto.MatrixResponse;
import ao.creativemode.kixi.training.service.MatrixService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** A matriz de especificação de uma prova (kixi#729). Escreve o dono da sala; lê também o aluno convidado. */
@RestController
@RequestMapping("/api/v1/exam-rooms/{roomId}/matrix")
public class ExamMatrixController {

    private final MatrixService matrices;
    private final CurrentAccountService current;

    public ExamMatrixController(MatrixService matrices, CurrentAccountService current) {
        this.matrices = matrices;
        this.current = current;
    }

    @PutMapping
    public Mono<ResponseEntity<MatrixResponse>> put(@PathVariable Long roomId, @Valid @RequestBody MatrixRequest request) {
        return staff().flatMap(admin -> current.requiredAccountId()
                        .flatMap(account -> matrices.put(roomId, request, account, admin)))
                .map(ResponseEntity::ok);
    }

    /** Importa de um CSV (ou texto): linhas {@code tema;questoes;cotacao;nivel}. */
    @PostMapping(value = "/import", consumes = {"text/csv", "text/plain"})
    public Mono<ResponseEntity<MatrixResponse>> importCsv(@PathVariable Long roomId, @RequestBody String csv,
            @RequestParam(required = false) String title, @RequestParam(required = false) Double totalPoints) {
        return staff().flatMap(admin -> current.requiredAccountId()
                        .flatMap(account -> matrices.importCsv(roomId, csv, title, totalPoints, account, admin)))
                .map(ResponseEntity::ok);
    }

    @GetMapping
    public Mono<ResponseEntity<MatrixResponse>> get(@PathVariable Long roomId) {
        return Mono.zip(current.requiredAccountId(), current.hasAnyRole("ADMIN"))
                .flatMap(who -> matrices.get(roomId, who.getT1(), who.getT2()))
                .map(ResponseEntity::ok);
    }

    @DeleteMapping
    public Mono<ResponseEntity<Void>> delete(@PathVariable Long roomId) {
        return staff().flatMap(admin -> current.requiredAccountId()
                        .flatMap(account -> matrices.delete(roomId, account, admin)))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    /** Devolve se é administrador; só professores e administradores escrevem a matriz. */
    private Mono<Boolean> staff() {
        return Mono.zip(current.hasAnyRole("ADMIN"), current.hasAnyRole("TEACHER"))
                .flatMap(roles -> roles.getT1() || roles.getT2()
                        ? Mono.just(roles.getT1())
                        : Mono.error(ApiException.forbidden("Só o professor dono da sala pode alterar a sua matriz.")));
    }
}
