package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.courses.CourseRequest;
import ao.creativemode.kixi.dto.courses.CourseResponse;
import ao.creativemode.kixi.service.CourseService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

    private static final Logger LOG = LoggerFactory.getLogger(CourseController.class);

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @GetMapping
    public Flux<CourseResponse> listAllActive() {
        LOG.info("GET /api/v1/courses - Listar todos os cursos ativos");
        return courseService.findAllActive();
    }

    @GetMapping("/trash")
    public Flux<CourseResponse> listAllDeleted() {
        LOG.info("GET /api/v1/courses/trash - Listar todos os cursos deletados");
        return courseService.findAllDeleted();
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<CourseResponse>> getCourseById(@PathVariable Long id) {
        if (id == null || id <= 0) {
            LOG.warn("GET /api/v1/courses/{} - ID inválido fornecido", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("GET /api/v1/courses/{} - Obter curso por ID", id);
        return courseService.findByIdActive(id)
            .map(ResponseEntity::ok)
            .doOnError(error -> LOG.error("Erro ao buscar curso com ID {}: {}", id, error.getMessage()));
    }

    @PostMapping
    public Mono<ResponseEntity<CourseResponse>> createCourse(@Valid @RequestBody CourseRequest request) {
        LOG.info("POST /api/v1/courses - Criar novo curso com código: {}", request.code());

        return courseService.create(request)
            .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
            .doOnSuccess(response -> LOG.info("Curso criado com sucesso: {}", response.getBody().id()))
            .doOnError(error -> LOG.error("Erro ao criar curso: {}", error.getMessage()));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<CourseResponse>> updateCourse(
            @PathVariable Long id,
            @Valid @RequestBody CourseRequest request) {

        if (id == null || id <= 0) {
            LOG.warn("PUT /api/v1/courses/{} - ID inválido fornecido", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("PUT /api/v1/courses/{} - Atualizar curso", id);

        return courseService.update(id, request)
            .map(ResponseEntity::ok)
            .doOnSuccess(response -> LOG.info("Curso {} atualizado com sucesso", id))
            .doOnError(error -> LOG.error("Erro ao atualizar curso {}: {}", id, error.getMessage()));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteCourse(@PathVariable Long id) {
        if (id == null || id <= 0) {
            LOG.warn("DELETE /api/v1/courses/{} - ID inválido fornecido", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("DELETE /api/v1/courses/{} - Deletar curso", id);

        return courseService.softDelete(id)
            .then(Mono.fromCallable(() -> ResponseEntity.noContent().<Void>build()))
            .doOnSuccess(response -> LOG.info("Curso {} deletado com sucesso", id))
            .doOnError(error -> LOG.error("Erro ao deletar curso {}: {}", id, error.getMessage()));
    }

    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<CourseResponse>> restoreCourse(@PathVariable Long id) {
        if (id == null || id <= 0) {
            LOG.warn("POST /api/v1/courses/{}/restore - ID inválido fornecido", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("POST /api/v1/courses/{}/restore - Restaurar curso", id);

        return courseService.restore(id)
            .map(ResponseEntity::ok)
            .doOnSuccess(response -> LOG.info("Curso {} restaurado com sucesso", id))
            .doOnError(error -> LOG.error("Erro ao restaurar curso {}: {}", id, error.getMessage()));
    }
}
