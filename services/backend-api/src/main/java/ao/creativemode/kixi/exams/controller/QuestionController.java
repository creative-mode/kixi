package ao.creativemode.kixi.exams.controller;

import ao.creativemode.kixi.exams.dto.question.QuestionReorderRequest;
import ao.creativemode.kixi.exams.dto.question.QuestionRequest;
import ao.creativemode.kixi.exams.dto.question.QuestionResponse;
import ao.creativemode.kixi.exams.dto.questionoption.CorrectOptionRequest;
import ao.creativemode.kixi.exams.dto.questionoption.QuestionOptionResponse;
import ao.creativemode.kixi.exams.service.QuestionOptionService;
import ao.creativemode.kixi.exams.service.QuestionService;
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
 * The questions of a statement, and the answer key on them.
 *
 * <p>Base path: /api/v1/statements/{statementId}/questions
 *
 * <p>The statement id in the path is not decoration: it is what the write rule
 * weighs, so a question reached under another statement is a 404 rather than
 * something that could be edited.
 *
 * <p>Reading is open to any authenticated caller, as the statement routes are.
 * The answer key is not: /correct-option sits behind the same authorization as
 * the other writes, so it cannot become a second way of answering a paper the
 * caller may only read.
 */
@RestController
@RequestMapping("/api/v1/statements/{statementId}/questions")
public class QuestionController {

    private final QuestionService questionService;
    private final QuestionOptionService questionOptionService;
    private final CurrentAccountService currentAccountService;

    public QuestionController(
        QuestionService questionService,
        QuestionOptionService questionOptionService,
        CurrentAccountService currentAccountService
    ) {
        this.questionService = questionService;
        this.questionOptionService = questionOptionService;
        this.currentAccountService = currentAccountService;
    }

    // =========================================================================
    // Reads
    //
    // Open to any authenticated caller, like the statement routes, but a
    // student gets a published statement and its questions without the answer
    // key. The split is the same one StatementController already makes between a
    // staff query and a student query; these routes only had to honour it.
    // =========================================================================

    @GetMapping
    public Mono<ResponseEntity<List<QuestionResponse>>> listAll(
        @PathVariable Long statementId
    ) {
        return isStaff()
            .flatMapMany(staff -> questionService.findAllActive(statementId, staff))
            .collectList()
            .map(ResponseEntity::ok);
    }

    @GetMapping("/trash")
    public Mono<ResponseEntity<List<QuestionResponse>>> listDeleted(
        @PathVariable Long statementId
    ) {
        return isStaff()
            .flatMapMany(staff -> questionService.findAllDeleted(statementId, staff))
            .collectList()
            .map(ResponseEntity::ok);
    }

    @GetMapping("/{questionId}")
    public Mono<ResponseEntity<QuestionResponse>> findById(
        @PathVariable Long statementId,
        @PathVariable Long questionId
    ) {
        return isStaff()
            .flatMap(staff -> questionService.findById(statementId, questionId, staff))
            .map(ResponseEntity::ok);
    }

    // =========================================================================
    // Writes
    // =========================================================================

    @PostMapping
    public Mono<ResponseEntity<QuestionResponse>> create(
        @PathVariable Long statementId,
        @Valid @RequestBody QuestionRequest request,
        UriComponentsBuilder uriBuilder
    ) {
        return currentAuthor()
            .flatMap(author -> questionService.create(statementId, request, author.getT1(), author.getT2()))
            .map(question -> ResponseEntity
                .created(locationOf(uriBuilder, statementId, question.id()))
                .body(question));
    }

    @PutMapping("/{questionId}")
    public Mono<ResponseEntity<QuestionResponse>> update(
        @PathVariable Long statementId,
        @PathVariable Long questionId,
        @Valid @RequestBody QuestionRequest request
    ) {
        return currentAuthor()
            .flatMap(author -> questionService
                .update(statementId, questionId, request, author.getT1(), author.getT2()))
            .map(ResponseEntity::ok);
    }

    /**
     * The display order. The question numbers stay as they were: a number is the
     * stable ordinal, unique per statement, so moving a question must not hand
     * it a different one.
     */
    @PutMapping("/reorder")
    public Mono<ResponseEntity<List<QuestionResponse>>> reorder(
        @PathVariable Long statementId,
        @Valid @RequestBody QuestionReorderRequest request
    ) {
        return currentAuthor()
            .flatMapMany(author -> questionService
                .reorder(statementId, request.questionIds(), author.getT1(), author.getT2()))
            .collectList()
            .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{questionId}")
    public Mono<ResponseEntity<Void>> softDelete(
        @PathVariable Long statementId,
        @PathVariable Long questionId
    ) {
        return currentAuthor()
            .flatMap(author -> questionService
                .softDelete(statementId, questionId, author.getT1(), author.getT2()))
            .thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping("/{questionId}/restore")
    public Mono<ResponseEntity<Void>> restore(
        @PathVariable Long statementId,
        @PathVariable Long questionId
    ) {
        return currentAuthor()
            .flatMap(author -> questionService
                .restore(statementId, questionId, author.getT1(), author.getT2()))
            .thenReturn(ResponseEntity.noContent().build());
    }

    @DeleteMapping("/{questionId}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(
        @PathVariable Long statementId,
        @PathVariable Long questionId
    ) {
        return currentAuthor()
            .flatMap(author -> questionService
                .hardDelete(statementId, questionId, author.getT1(), author.getT2()))
            .thenReturn(ResponseEntity.noContent().build());
    }

    // =========================================================================
    // The answer key
    // =========================================================================

    /**
     * Marks one option of the question as the answer, clearing the others.
     *
     * <p>On the question rather than under /options, because it is the question
     * that is being answered, not an option being edited.
     */
    @PutMapping("/{questionId}/correct-option")
    public Mono<ResponseEntity<QuestionOptionResponse>> setCorrectOption(
        @PathVariable Long statementId,
        @PathVariable Long questionId,
        @Valid @RequestBody CorrectOptionRequest request
    ) {
        return currentAuthor()
            .flatMap(author -> questionOptionService
                .setCorrectOption(statementId, questionId, request.optionId(), author.getT1(), author.getT2()))
            .map(ResponseEntity::ok);
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

    /**
     * Whether the caller may see the answer key and a statement that is not
     * published.
     */
    private Mono<Boolean> isStaff() {
        return currentAccountService.hasAnyRole("ADMIN", "TEACHER");
    }

    private URI locationOf(UriComponentsBuilder uriBuilder, Long statementId, Long questionId) {
        return uriBuilder
            .path("/api/v1/statements/{statementId}/questions/{id}")
            .buildAndExpand(statementId, questionId)
            .toUri();
    }
}
