package ao.creativemode.kixi.exams.controller;

import ao.creativemode.kixi.exams.dto.questionoption.CorrectOptionRequest;
import ao.creativemode.kixi.exams.dto.questionoption.QuestionOptionResponse;
import ao.creativemode.kixi.exams.service.QuestionOptionService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

/**
 * The answer key, on the route the issue spells out.
 *
 * <p>Base path: /api/v1/questions/{questionId}
 *
 * <p>Only this one route. Everything else about questions and options is under
 * the statement that holds them, on {@link QuestionController} and
 * {@link QuestionOptionController}, and stays there: the statement id in the path
 * is what the write rule weighs, and a resource whose parent is not named in the
 * request has to look its parent up before it can check anything.
 *
 * <p>It is here as well because the issue asks for this path, and a client
 * working from the spec gets a 404 otherwise. Both call the same service method
 * with the same authorization, so the two routes cannot drift apart in what they
 * allow.
 */
@RestController
@RequestMapping("/api/v1/questions/{questionId}")
public class QuestionAnswerKeyController {

    private final QuestionOptionService questionOptionService;
    private final CurrentAccountService currentAccountService;

    public QuestionAnswerKeyController(
        QuestionOptionService questionOptionService,
        CurrentAccountService currentAccountService
    ) {
        this.questionOptionService = questionOptionService;
        this.currentAccountService = currentAccountService;
    }

    /**
     * Marks one option of the question as the answer, clearing the others.
     */
    @PutMapping("/correct-option")
    public Mono<ResponseEntity<QuestionOptionResponse>> setCorrectOption(
        @PathVariable Long questionId,
        @Valid @RequestBody CorrectOptionRequest request
    ) {
        return Mono.zip(
            currentAccountService.requiredAccountId(),
            currentAccountService.hasAnyRole("ADMIN")
        )
            .flatMap(author -> questionOptionService
                .setCorrectOptionOfQuestion(questionId, request.optionId(), author.getT1(), author.getT2()))
            .map(ResponseEntity::ok);
    }
}
