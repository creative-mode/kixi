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

    @GetMapping("/{code}")
    public Mono<ResponseEntity<SubjectResponse>> getByCode(@PathVariable String code){
        return service.findByCodeActive(code)
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
                            .path("/api/v1/subjects/{code}")
                            .buildAndExpand(subject.code())
                            .toUri();
                    return ResponseEntity.created(uriLocal).body(subject);
                });

    }

    @PutMapping("/{code}")
    public Mono<ResponseEntity<SubjectResponse>> update(
            @PathVariable String code,
            @Valid @RequestBody SubjectRequest data
    ){
        return service.update(code,data)
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{code}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable String code){
        return service.softDelete(code)
                .map(v->ResponseEntity.noContent().build());
    }

    @PostMapping("/{code}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable String code){
        return service.restore(code)
                .map(v->ResponseEntity.noContent().build());
    }

    @DeleteMapping("/{code}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable String code){
        return service.hardDelete(code)
                .map(v->ResponseEntity.noContent().build());
    }
}
