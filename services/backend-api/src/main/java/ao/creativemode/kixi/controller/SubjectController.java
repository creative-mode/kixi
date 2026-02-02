package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.dto.subject.SubjectRequest;
import ao.creativemode.kixi.dto.subject.SubjectResponse;
import ao.creativemode.kixi.service.SubjectService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/subjects")
public class SubjectController {

    private final SubjectService service;

    public SubjectController(SubjectService service){
        this.service = service;
    }

    @GetMapping
    public Mono<ResponseEntity<List<SubjectResponse>>> listAllActive(){
        return service.findAllActive()
                .collectList()
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<SubjectResponse>> getByCode(@PathVariable Long id){
        return service.findByCodeActive(id)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/trash")
    public Mono<ResponseEntity<List<SubjectResponse>>> listTrashed(){
        return service.findAllDeleted()
                .collectList()
                .map(ResponseEntity::ok);
    }

    @PostMapping
    public Mono<ResponseEntity<SubjectResponse>> create(
            @Valid @RequestBody SubjectRequest request,
            UriComponentsBuilder uriBuilder
    ){
        return service.create(request)
                .map(subject->{
                    URI uriLocal = uriBuilder
                            .path("/api/v1/subjects/{id}")
                            .buildAndExpand(subject.id())
                            .toUri();
                    return ResponseEntity.created(uriLocal).body(subject);
                });

    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<SubjectResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody SubjectRequest data
    ){
        return service.update(id,data)
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id){
        return service.softDelete(id)
                .map(v->ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id){
        return service.restore(id)
                .map(v->ResponseEntity.noContent().build());
    }

    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id){
        return service.hardDelete(id)
                .map(v->ResponseEntity.noContent().build());
    }
}
