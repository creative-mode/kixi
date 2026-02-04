package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.statement.StatementRequest;
import ao.creativemode.kixi.dto.statement.StatementResponse;
import ao.creativemode.kixi.model.Statement;
import ao.creativemode.kixi.repository.StatementRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class StatementService {
    private final StatementRepository repository;

    public StatementService(StatementRepository repository) {
        this.repository = repository;
    }

    public Mono<List<StatementResponse>> listAllActive() {
        return repository.findByDeletedAtIsNull()
                .map(this::toResponse)
                .collectList()
                .onErrorResume(e -> Mono.error(
                        ApiException.badRequest("Error listing statements: " + e.getMessage())
                ));
    }

    public Mono<List<StatementResponse>> listTrashed() {
        return repository.findByDeletedAtIsNotNull()
                .map(this::toResponse)
                .collectList()
                .onErrorResume(e -> Mono.error(
                        ApiException.badRequest("Error listing deleted statements: " + e.getMessage())
                ));
    }

    public Mono<StatementResponse> getById(Long id) {
        if (id == null || id <= 0) {
            return Mono.error(ApiException.badRequest("Statement ID is required and must be greater than zero"));
        }

        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(
                        ApiException.notFound("Statement with ID " + id + " not found")
                ))
                .map(this::toResponse);
    }

    public Mono<StatementResponse> update(Long id, StatementRequest request) {
        if (id == null || id <= 0) {
            return Mono.error(ApiException.badRequest("Statement ID is required and must be greater than zero"));
        }

        return validateRequest(request)
                .then(repository.findByIdAndDeletedAtIsNull(id))
                .switchIfEmpty(Mono.error(
                        ApiException.notFound("Statement with ID " + id + " not found for update")
                ))
                .flatMap(statement -> {
                    statement.setTitle(request.getTitle());
                    statement.setExamType(request.getExamType());
                    statement.setDurationMinutes(request.getDurationMinutes());
                    statement.setVariant(request.getVariant());
                    statement.setInstructions(request.getInstructions());
                    statement.setTotalMaxScore(request.getTotalMaxScore());
                    statement.setSchoolYearId(request.getSchoolYearId());
                    statement.setTermId(request.getTermId());
                    statement.setSubjectId(request.getSubjectId());
                    statement.setClassId(request.getClassId());
                    statement.setCourseId(request.getCourseId());
                    statement.setVisible(request.getVisible());
                    statement.setUpdatedAt(LocalDateTime.now());
                    return repository.save(statement);
                })
                .map(this::toResponse)
                .onErrorResume(ApiException.class, Mono::error)
                .onErrorResume(e -> Mono.error(
                        ApiException.badRequest("Error updating statement: " + e.getMessage())
                ));
    }

    public Mono<Void> softDelete(Long id) {
        if (id == null || id <= 0) {
            return Mono.error(ApiException.badRequest("Statement ID is required and must be greater than zero"));
        }

        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(
                        ApiException.notFound("Statement with ID " + id + " not found for deletion")
                ))
                .flatMap(statement -> {
                    statement.setDeletedAt(LocalDateTime.now());
                    return repository.save(statement);
                })
                .then()
                .onErrorResume(ApiException.class, Mono::error)
                .onErrorResume(e -> Mono.error(
                        ApiException.badRequest("Error deleting statement: " + e.getMessage())
                ));
    }

    public Mono<Void> restore(Long id) {
        if (id == null || id <= 0) {
            return Mono.error(ApiException.badRequest("Statement ID is required and must be greater than zero"));
        }

        return repository.findById(id)
                .switchIfEmpty(Mono.error(
                        ApiException.notFound("Statement with ID " + id + " not found")
                ))
                .filter(statement -> statement.getDeletedAt() != null)
                .switchIfEmpty(Mono.error(
                        ApiException.badRequest("Statement with ID " + id + " is not deleted and cannot be restored")
                ))
                .flatMap(statement -> {
                    statement.setDeletedAt(null);
                    return repository.save(statement);
                })
                .then()
                .onErrorResume(ApiException.class, Mono::error)
                .onErrorResume(e -> Mono.error(
                        ApiException.badRequest("Error restoring statement: " + e.getMessage())
                ));
    }

    public Mono<Void> hardDelete(Long id) {
        if (id == null || id <= 0) {
            return Mono.error(ApiException.badRequest("Statement ID is required and must be greater than zero"));
        }

        return repository.findById(id)
                .switchIfEmpty(Mono.error(
                        ApiException.notFound("Statement with ID " + id + " not found for permanent deletion")
                ))
                .flatMap(statement -> repository.deleteById(id))
                .onErrorResume(ApiException.class, Mono::error)
                .onErrorResume(e -> Mono.error(
                        ApiException.badRequest("Error permanently deleting statement: " + e.getMessage())
                ));
    }

    public Mono<StatementResponse> create(StatementRequest request) {
        return validateRequest(request)
                .then(Mono.defer(() -> {
                    Statement statement = new Statement();
                    statement.setTitle(request.getTitle());
                    statement.setExamType(request.getExamType());
                    statement.setDurationMinutes(request.getDurationMinutes());
                    statement.setVariant(request.getVariant());
                    statement.setInstructions(request.getInstructions());
                    statement.setTotalMaxScore(request.getTotalMaxScore());
                    statement.setSchoolYearId(request.getSchoolYearId());
                    statement.setTermId(request.getTermId());
                    statement.setSubjectId(request.getSubjectId());
                    statement.setClassId(request.getClassId());
                    statement.setCourseId(request.getCourseId());
                    statement.setVisible(request.getVisible() != null ? request.getVisible() : false);
                    statement.setCreatedAt(LocalDateTime.now());

                    return repository.save(statement);
                }))
                .map(this::toResponse)
                .onErrorResume(ApiException.class, Mono::error)
                .onErrorResume(e -> Mono.error(
                        ApiException.badRequest("Error creating statement: " + e.getMessage())
                ));
    }

    private Mono<Void> validateRequest(StatementRequest request) {
        List<String> errors = new ArrayList<>();

        if (request == null) {
            return Mono.error(ApiException.badRequest("Statement data is required"));
        }

        if (request.getTitle() == null || request.getTitle().isBlank()) {
            errors.add("Title is required");
        } else if (request.getTitle().length() < 3) {
            errors.add("Title must have at least 3 characters");
        } else if (request.getTitle().length() > 255) {
            errors.add("Title must have at most 255 characters");
        }

        if (request.getExamType() == null || request.getExamType().isBlank()) {
            errors.add("Exam type is required");
        }

        if (request.getDurationMinutes() != null && request.getDurationMinutes() <= 0) {
            errors.add("Duration must be greater than zero");
        }

        if (request.getTotalMaxScore() != null && request.getTotalMaxScore() < 0) {
            errors.add("Maximum score cannot be negative");
        }

        if (request.getSchoolYearId() == null) {
            errors.add("School year is required");
        }

        if (request.getTermId() == null) {
            errors.add("Term is required");
        }

        if (request.getSubjectId() == null) {
            errors.add("Subject is required");
        }

        if (request.getClassId() == null) {
            errors.add("Class is required");
        }

        if (!errors.isEmpty()) {
            String errorMessage = String.join("; ", errors);
            return Mono.error(ApiException.badRequest("Validation errors: " + errorMessage));
        }

        return Mono.empty();
    }



    private StatementResponse toResponse(Statement statement) {
        StatementResponse response = new StatementResponse();
        response.setId(statement.getId());
        response.setExamType(statement.getExamType());
        response.setDurationMinutes(statement.getDurationMinutes());
        response.setVariant(statement.getVariant());
        response.setTitle(statement.getTitle());
        response.setInstructions(statement.getInstructions());
        response.setTotalMaxScore(statement.getTotalMaxScore());
        response.setSchoolYearId(statement.getSchoolYearId());
        response.setTermId(statement.getTermId());
        response.setSubjectId(statement.getSubjectId());
        response.setClassId(statement.getClassId());
        response.setCourseId(statement.getCourseId());
        response.setVisible(statement.getVisible());
        response.setCreatedAt(statement.getCreatedAt());
        response.setUpdatedAt(statement.getUpdatedAt());
        return response;
    }
}
