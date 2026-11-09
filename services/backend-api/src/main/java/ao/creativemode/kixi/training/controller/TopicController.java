package ao.creativemode.kixi.training.controller;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.training.dto.AutoTagResponse;
import ao.creativemode.kixi.training.dto.QuestionTopicsRequest;
import ao.creativemode.kixi.training.dto.TopicRequest;
import ao.creativemode.kixi.training.dto.TopicResponse;
import ao.creativemode.kixi.training.service.TopicService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
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

/** Temas de uma disciplina e a sua ligação às questões (kixi#729). Ler é para qualquer conta; escrever, para professores e administradores. */
@RestController
@RequestMapping("/api/v1")
public class TopicController {

    private final TopicService topics;
    private final CurrentAccountService current;

    public TopicController(TopicService topics, CurrentAccountService current) {
        this.topics = topics;
        this.current = current;
    }

    @GetMapping("/subjects/{subjectId}/topics")
    public Mono<ResponseEntity<List<TopicResponse>>> list(@PathVariable Long subjectId,
            @RequestParam(required = false) Integer grade) {
        return topics.list(subjectId, grade).collectList().map(ResponseEntity::ok);
    }

    @PostMapping("/subjects/{subjectId}/topics")
    public Mono<ResponseEntity<TopicResponse>> create(@PathVariable Long subjectId, @Valid @RequestBody TopicRequest request) {
        return requireStaff().flatMap(admin -> current.requiredAccountId()
                        .flatMap(account -> topics.create(subjectId, request, account)))
                .map(topic -> ResponseEntity.status(HttpStatus.CREATED).body(topic));
    }

    @PutMapping("/topics/{id}")
    public Mono<ResponseEntity<TopicResponse>> update(@PathVariable Long id, @Valid @RequestBody TopicRequest request) {
        return requireStaff().flatMap(admin -> current.requiredAccountId()
                        .flatMap(account -> topics.update(id, request, account, admin)))
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/topics/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable Long id) {
        return requireStaff().flatMap(admin -> current.requiredAccountId()
                        .flatMap(account -> topics.delete(id, account, admin)))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @PutMapping("/questions/{questionId}/topics")
    public Mono<ResponseEntity<List<TopicResponse>>> setQuestionTopics(@PathVariable Long questionId,
            @Valid @RequestBody QuestionTopicsRequest request) {
        return requireStaff().flatMap(admin -> current.requiredAccountId()
                        .flatMap(account -> topics.setQuestionTopics(questionId, request.topicIds(), account, admin)))
                .map(ResponseEntity::ok);
    }

    @PostMapping("/statements/{statementId}/topics/auto-tag")
    public Mono<ResponseEntity<AutoTagResponse>> autoTag(@PathVariable Long statementId) {
        return requireStaff().flatMap(admin -> current.requiredAccountId()
                        .flatMap(account -> topics.autoTag(statementId, account, admin)))
                .map(ResponseEntity::ok);
    }

    /** Devolve se é administrador; recusa quem não é professor nem administrador. */
    private Mono<Boolean> requireStaff() {
        return Mono.zip(current.hasAnyRole("ADMIN"), current.hasAnyRole("TEACHER"))
                .flatMap(roles -> roles.getT1() || roles.getT2()
                        ? Mono.just(roles.getT1())
                        : Mono.error(ApiException.forbidden("Só professores ou administradores gerem os temas.")));
    }
}
