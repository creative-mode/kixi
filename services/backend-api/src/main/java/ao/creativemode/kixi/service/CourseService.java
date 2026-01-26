package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.courses.CourseRequest;
import ao.creativemode.kixi.dto.courses.CourseResponse;
import ao.creativemode.kixi.model.Course;
import ao.creativemode.kixi.repository.CourseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class CourseService {

    private static final Logger LOG = LoggerFactory.getLogger(CourseService.class);
    private static final int RETRY_ATTEMPTS = 3;

    private static final String MSG_COURSE_NOT_FOUND = "Curso não encontrado";
    private static final String MSG_CODE_EXISTS = "Código já existe";

    private final CourseRepository courseRepository;

    public CourseService(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    public Flux<CourseResponse> findAllActive() {
        LOG.info("Buscando todos os cursos ativos");
        return courseRepository.findAllByDeletedAtIsNull()
            .map(this::convertToResponse)
            .doOnNext(course -> LOG.debug("Curso encontrado: {}", course.id()))
            .doOnError(error -> LOG.error("Erro ao buscar cursos ativos", error))
            .retry(RETRY_ATTEMPTS);
    }

    public Flux<CourseResponse> findAllDeleted() {
        LOG.info("Buscando todos os cursos deletados");
        return courseRepository.findAllByDeletedAtIsNotNull()
            .map(this::convertToResponse)
            .doOnNext(course -> LOG.debug("Curso deletado encontrado: {}", course.id()))
            .doOnError(error -> LOG.error("Erro ao buscar cursos deletados", error))
            .retry(RETRY_ATTEMPTS);
    }

    public Mono<CourseResponse> findByIdActive(Long id) {
        if (id == null || id <= 0) {
            LOG.warn("ID inválido fornecido: {}", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("Buscando curso com ID: {}", id);
        return courseRepository.findByIdAndDeletedAtIsNull(id)
            .map(this::convertToResponse)
            .switchIfEmpty(Mono.error(new ApiException(MSG_COURSE_NOT_FOUND, HttpStatus.NOT_FOUND)))
            .doOnError(error -> LOG.warn("Curso {} não encontrado", id))
            .retry(RETRY_ATTEMPTS);
    }

    public Mono<CourseResponse> create(CourseRequest request) {
        LOG.info("Criando novo curso com código: {}", request.code());

        String code = request.code().trim().toUpperCase();
        String name = request.name().trim();
        String description = request.description() != null ? request.description().trim() : null;

        return courseRepository.findByCodeAndDeletedAtIsNull(code)
            .flatMap(existing -> Mono.error(new ApiException(MSG_CODE_EXISTS, HttpStatus.CONFLICT)))
            .then(Mono.defer(() -> {
                Course course = new Course(null, code, name, description, null, null, null);
                return courseRepository.save(course);
            }))
            .map(this::convertToResponse)
            .doOnSuccess(saved -> LOG.info("Curso criado com sucesso: {}", saved.id()))
            .doOnError(error -> LOG.error("Erro ao criar curso", error))
            .retry(RETRY_ATTEMPTS);
    }

    public Mono<CourseResponse> update(Long id, CourseRequest request) {
        if (id == null || id <= 0) {
            LOG.warn("ID inválido para atualização: {}", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("Atualizando curso: {}", id);

        String code = request.code().trim().toUpperCase();
        String name = request.name().trim();
        String description = request.description() != null ? request.description().trim() : null;

        return courseRepository.findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(Mono.error(new ApiException(MSG_COURSE_NOT_FOUND, HttpStatus.NOT_FOUND)))
            .flatMap(existingCourse -> 
                courseRepository.findByCodeAndIdNotAndDeletedAtIsNull(code, id)
                    .flatMap(duplicate -> Mono.error(new ApiException(MSG_CODE_EXISTS, HttpStatus.CONFLICT)))
                    .then(Mono.just(existingCourse))
            )
            .flatMap(existingCourse -> {
                existingCourse.setCode(code);
                existingCourse.setName(name);
                existingCourse.setDescription(description);
                return courseRepository.save(existingCourse);
            })
            .map(this::convertToResponse)
            .doOnSuccess(updated -> LOG.info("Curso {} atualizado com sucesso", id))
            .doOnError(error -> LOG.error("Erro ao atualizar curso {}", id, error))
            .retry(RETRY_ATTEMPTS);
    }

    public Mono<Void> softDelete(Long id) {
        if (id == null || id <= 0) {
            LOG.warn("ID inválido para deleção: {}", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("Deletando curso: {}", id);

        return courseRepository.findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(Mono.error(new ApiException(MSG_COURSE_NOT_FOUND, HttpStatus.NOT_FOUND)))
            .flatMap(course -> {
                course.markAsDeleted();
                return courseRepository.save(course);
            })
            .doOnSuccess(deleted -> LOG.info("Curso {} deletado com sucesso", id))
            .doOnError(error -> LOG.error("Erro ao deletar curso {}", id, error))
            .then()
            .retry(RETRY_ATTEMPTS);
    }

    public Mono<CourseResponse> restore(Long id) {
        if (id == null || id <= 0) {
            LOG.warn("ID inválido para restauração: {}", id);
            return Mono.error(new ApiException("ID inválido", HttpStatus.BAD_REQUEST));
        }

        LOG.info("Restaurando curso: {}", id);

        return courseRepository.findById(id)
            .filterWhen(course -> Mono.fromCallable(course::isDeleted))
            .switchIfEmpty(Mono.error(new ApiException("Curso não foi deletado", HttpStatus.BAD_REQUEST)))
            .flatMap(course -> {
                course.restore();
                return courseRepository.save(course);
            })
            .map(this::convertToResponse)
            .doOnSuccess(restored -> LOG.info("Curso {} restaurado com sucesso", id))
            .doOnError(error -> LOG.error("Erro ao restaurar curso {}", id, error))
            .retry(RETRY_ATTEMPTS);
    }

    private CourseResponse convertToResponse(Course course) {
        return new CourseResponse(
            course.getId(),
            course.getCode(),
            course.getName(),
            course.getDescription(),
            course.getCreatedAt(),
            course.getUpdatedAt(),
            course.getDeletedAt()
        );
    }
}
