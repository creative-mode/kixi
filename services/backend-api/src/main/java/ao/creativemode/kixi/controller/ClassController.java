package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.dto.classe.ClassRequest;
import ao.creativemode.kixi.dto.classe.ClassResponse;
import ao.creativemode.kixi.service.ClassService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
@RequestMapping("api/v1/classes")
public class ClassController {

    private final ClassService service;

    public ClassController(ClassService service){this.service = service;}


    /**
     * Retrieves all active (non-deleted) class.
     */
    @GetMapping
    public Mono<ResponseEntity<List<ClassResponse>>> listAllActive(){
        return service.findAllActive()
                .collectList()
                .map(ResponseEntity::ok);
    }


    /**
     * Retrieves all soft-deleted (trashed) class.
     */
    @GetMapping("/trash")
    public Mono<ResponseEntity<List<ClassResponse>>> listTrashed(){
        return service.findAllDeteted()
                .collectList().map(ResponseEntity::ok);
    }


    /**
     * Retrieves a single active class by ID.
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<ClassResponse>> getById(@PathVariable Long id){
        return service.findByIdActive(id)
                .map(ResponseEntity::ok);
    }

    /**
     * Creates a new class.
     */
    @PostMapping
    public Mono<ResponseEntity<ClassResponse>> create(
            @Valid @RequestBody ClassRequest request,
            UriComponentsBuilder uriBuilder
    ){
        return service.create(request)
                .map(created -> {
                    URI location = uriBuilder
                            .path("/api/v1/class/{id}")
                            .buildAndExpand(created.code())
                            .toUri();

                    return ResponseEntity.created(location).body(created);
                });
    }


    /**
     * Soft-deletes a class (moves it to trash).
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id){

        return service.softDelete(id)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }

    /**
     * Restores a soft-deleted class from trash.
     */
    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id){
        return service.restore(id)
                .thenReturn(ResponseEntity.ok().build());
    }

    /**
     * Permanently deletes a class (only if already soft-deleted).
     */
    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id){
        return service.hardDelete(id)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }
}
