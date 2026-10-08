package ao.creativemode.kixi.exams.controller;

import ao.creativemode.kixi.exams.dto.questionoption.QuestionOptionReorderRequest;
import ao.creativemode.kixi.exams.dto.questionoption.QuestionOptionRequest;
import ao.creativemode.kixi.exams.dto.questionoption.QuestionOptionResponse;
import ao.creativemode.kixi.exams.service.QuestionOptionService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

/**
 * The options of a question.
 *
 * <p>Base path: /api/v1/statements/{statementId}/questions/{questionId}/options
 *
 * <p>Three ids deep on purpose. Every one of them is checked on the way through,
 * so an option id from another question and a question id from another
 * statement both come back as 404 rather than as something that could be
 * edited.
 *
 * <p>Marking the answer is not here: it belongs to the question, and lives on
 * {@link QuestionController#setCorrectOption}.
 */
@RestController
@RequestMapping("/api/v1/statements/{statementId}/questions/{questionId}/options")
public class QuestionOptionController {

    private final QuestionOptionService questionOptionService;
    private final CurrentAccountService currentAccountService;

    public QuestionOptionController(
        QuestionOptionService questionOptionService,
        CurrentAccountService currentAccountService
    ) {
        this.questionOptionService = questionOptionService;
        this.currentAccountService = currentAccountService;
    }

    // =========================================================================
    // Reads
    // =========================================================================

    @GetMapping
    public Mono<ResponseEntity<List<QuestionOptionResponse>>> listAll(
        @PathVariable Long statementId,
        @PathVariable Long questionId
    ) {
        return questionOptionService.findAll(statementId, questionId)
            .collectList()
            .map(ResponseEntity::ok);
    }

    @GetMapping("/trash")
    public Mono<ResponseEntity<List<QuestionOptionResponse>>> listDeleted(
        @PathVariable Long statementId,
        @PathVariable Long questionId
    ) {
        return questionOptionService.findAllDeleted(statementId, questionId)
            .collectList()
            .map(ResponseEntity::ok);
    }

    @GetMapping("/{optionId}")
    public Mono<ResponseEntity<QuestionOptionResponse>> findById(
        @PathVariable Long statementId,
        @PathVariable Long questionId,
        @PathVariable Long optionId
    ) {
        return questionOptionService.findById(statementId, questionId, optionId)
            .map(ResponseEntity::ok);
    }

    // =========================================================================
    // Writes
    // =========================================================================

    /**
     * Adds an option, or brings a removed one back if the label is the same.
     * The label is unique per question and stays taken once used, so reusing
     * one restores the option that had it.
     */
    @PostMapping
    public Mono<ResponseEntity<QuestionOptionResponse>> create(
        @PathVariable Long statementId,
        @PathVariable Long questionId,
        @Valid @RequestBody QuestionOptionRequest request,
        UriComponentsBuilder uriBuilder
    ) {
        return currentAuthor()
            .flatMap(author -> questionOptionService
                .create(statementId, questionId, request, author.getT1(), author.getT2()))
            .map(option -> ResponseEntity
                .created(locationOf(uriBuilder, statementId, questionId, option.id()))
                .body(option));
    }

    /** Changes the text. The label is the option's identity, so it stays. */
    @PutMapping("/{optionId}")
    public Mono<ResponseEntity<QuestionOptionResponse>> update(
        @PathVariable Long statementId,
        @PathVariable Long questionId,
        @PathVariable Long optionId,
        @Valid @RequestBody QuestionOptionRequest request
    ) {
        return currentAuthor()
            .flatMap(author -> questionOptionService
                .update(statementId, questionId, optionId, request, author.getT1(), author.getT2()))
            .map(ResponseEntity::ok);
    }

    @PutMapping("/reorder")
    public Mono<ResponseEntity<List<QuestionOptionResponse>>> reorder(
        @PathVariable Long statementId,
        @PathVariable Long questionId,
        @Valid @RequestBody QuestionOptionReorderRequest request
    ) {
        return currentAuthor()
            .flatMapMany(author -> questionOptionService
                .reorder(statementId, questionId, request.optionIds(), author.getT1(), author.getT2()))
            .collectList()
            .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{optionId}")
    public Mono<ResponseEntity<Void>> softDelete(
        @PathVariable Long statementId,
        @PathVariable Long questionId,
        @PathVariable Long optionId
    ) {
        return currentAuthor()
            .flatMap(author -> questionOptionService
                .softDelete(statementId, questionId, optionId, author.getT1(), author.getT2()))
            .thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping("/{optionId}/restore")
    public Mono<ResponseEntity<Void>> restore(
        @PathVariable Long statementId,
        @PathVariable Long questionId,
        @PathVariable Long optionId
    ) {
        return currentAuthor()
            .flatMap(author -> questionOptionService
                .restore(statementId, questionId, optionId, author.getT1(), author.getT2()))
            .thenReturn(ResponseEntity.noContent().build());
    }

    @DeleteMapping("/{optionId}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(
        @PathVariable Long statementId,
        @PathVariable Long questionId,
        @PathVariable Long optionId
    ) {
        return currentAuthor()
            .flatMap(author -> questionOptionService
                .hardDelete(statementId, questionId, optionId, author.getT1(), author.getT2()))
            .thenReturn(ResponseEntity.noContent().build());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** The signed-in account and whether it is an administrator. */
    private Mono<Tuple2<Long, Boolean>> currentAuthor() {
        return Mono.zip(
            currentAccountService.requiredAccountId(),
            currentAccountService.hasAnyRole("ADMIN")
        );
    }

    private URI locationOf(
        UriComponentsBuilder uriBuilder,
        Long statementId,
        Long questionId,
        Long optionId
    ) {
        return uriBuilder
            .path("/api/v1/statements/{statementId}/questions/{questionId}/options/{id}")
            .buildAndExpand(statementId, questionId, optionId)
            .toUri();
    }
}
