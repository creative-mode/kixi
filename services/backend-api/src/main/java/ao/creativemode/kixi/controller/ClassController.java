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
@RequestMapping("api/v1/class")
public class ClassController {

    private final ClassService service;

    public ClassController(ClassService service){this.service = service;}

    @GetMapping
    public Mono<ResponseEntity<List<ClassResponse>>> listAllActive(){
        return service.findAllActive()
                .collectList()
                .map(ResponseEntity::ok);
    }

    @GetMapping("/trash")
    public Mono<ResponseEntity<List<ClassResponse>>> listTrashed(){
        return service.findAllDeteted()
                .collectList().map(ResponseEntity::ok);
    }

    @GetMapping("/{code}")
    public Mono<ResponseEntity<ClassResponse>> getById(@PathVariable String code){
        return service.findByCodeActive(code)
                .map(ResponseEntity::ok);
    }

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

    @DeleteMapping("/{code}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable String code){

        return service.softDelete(code)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }

    @PostMapping("/{code}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable String code){
        return service.restore(code)
                .thenReturn(ResponseEntity.ok().build());
    }

    @DeleteMapping("/{code}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable String code){
        return service.hardDelete(code)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }
}
