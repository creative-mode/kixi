package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.schoolyears.StatementRequest;
import ao.creativemode.kixi.model.Statement;
import ao.creativemode.kixi.repository.StatementRepository;
import ao.creativemode.kixi.dto.schoolyears.StatementResponse;
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
                        ApiException.badRequest("Erro ao listar enunciados: " + e.getMessage())
                ));
    }

    public Mono<List<StatementResponse>> listTrashed() {
        return repository.findByDeletedAtIsNotNull()
                .map(this::toResponse)
                .collectList()
                .onErrorResume(e -> Mono.error(
                        ApiException.badRequest("Erro ao listar enunciados excluídos: " + e.getMessage())
                ));
    }

    public Mono<StatementResponse> getById(Long id) {
        if (id == null || id <= 0) {
            return Mono.error(ApiException.badRequest("O ID do enunciado é obrigatório e deve ser maior que zero"));
        }

        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(
                        ApiException.notFound("Enunciado com ID " + id + " não encontrado")
                ))
                .map(this::toResponse);
    }

    public Mono<StatementResponse> update(Long id, StatementRequest request) {
        if (id == null || id <= 0) {
            return Mono.error(ApiException.badRequest("O ID do enunciado é obrigatório e deve ser maior que zero"));
        }

        return validateRequest(request)
                .then(repository.findByIdAndDeletedAtIsNull(id))
                .switchIfEmpty(Mono.error(
                        ApiException.notFound("Enunciado com ID " + id + " não encontrado para atualização")
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
                        ApiException.badRequest("Erro ao atualizar enunciado: " + e.getMessage())
                ));
    }

    public Mono<Void> softDelete(Long id) {
        if (id == null || id <= 0) {
            return Mono.error(ApiException.badRequest("O ID do enunciado é obrigatório e deve ser maior que zero"));
        }

        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(
                        ApiException.notFound("Enunciado com ID " + id + " não encontrado para exclusão")
                ))
                .flatMap(statement -> {
                    statement.setDeletedAt(LocalDateTime.now());
                    return repository.save(statement);
                })
                .then()
                .onErrorResume(ApiException.class, Mono::error)
                .onErrorResume(e -> Mono.error(
                        ApiException.badRequest("Erro ao excluir enunciado: " + e.getMessage())
                ));
    }

    public Mono<Void> restore(Long id) {
        if (id == null || id <= 0) {
            return Mono.error(ApiException.badRequest("O ID do enunciado é obrigatório e deve ser maior que zero"));
        }

        return repository.findById(id)
                .switchIfEmpty(Mono.error(
                        ApiException.notFound("Enunciado com ID " + id + " não encontrado")
                ))
                .filter(statement -> statement.getDeletedAt() != null)
                .switchIfEmpty(Mono.error(
                        ApiException.badRequest("O enunciado com ID " + id + " não está excluído e não pode ser restaurado")
                ))
                .flatMap(statement -> {
                    statement.setDeletedAt(null);
                    return repository.save(statement);
                })
                .then()
                .onErrorResume(ApiException.class, Mono::error)
                .onErrorResume(e -> Mono.error(
                        ApiException.badRequest("Erro ao restaurar enunciado: " + e.getMessage())
                ));
    }

    public Mono<Void> hardDelete(Long id) {
        if (id == null || id <= 0) {
            return Mono.error(ApiException.badRequest("O ID do enunciado é obrigatório e deve ser maior que zero"));
        }

        return repository.findById(id)
                .switchIfEmpty(Mono.error(
                        ApiException.notFound("Enunciado com ID " + id + " não encontrado para exclusão permanente")
                ))
                .flatMap(statement -> repository.deleteById(id))
                .onErrorResume(ApiException.class, Mono::error)
                .onErrorResume(e -> Mono.error(
                        ApiException.badRequest("Erro ao excluir permanentemente enunciado: " + e.getMessage())
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
                        ApiException.badRequest("Erro ao criar enunciado: " + e.getMessage())
                ));
    }

    private Mono<Void> validateRequest(StatementRequest request) {
        List<String> errors = new ArrayList<>();

        if (request == null) {
            return Mono.error(ApiException.badRequest("Os dados do enunciado são obrigatórios"));
        }

        if (request.getTitle() == null || request.getTitle().isBlank()) {
            errors.add("O título é obrigatório");
        } else if (request.getTitle().length() < 3) {
            errors.add("O título deve ter pelo menos 3 caracteres");
        } else if (request.getTitle().length() > 255) {
            errors.add("O título deve ter no máximo 255 caracteres");
        }

        if (request.getExamType() == null || request.getExamType().isBlank()) {
            errors.add("O tipo de exame é obrigatório");
        }

        if (request.getDurationMinutes() != null && request.getDurationMinutes() <= 0) {
            errors.add("A duração deve ser maior que zero");
        }

        if (request.getTotalMaxScore() != null && request.getTotalMaxScore() < 0) {
            errors.add("A pontuação máxima não pode ser negativa");
        }

        if (request.getSchoolYearId() == null) {
            errors.add("O ano letivo é obrigatório");
        }

        if (request.getTermId() == null) {
            errors.add("O trimestre é obrigatório");
        }

        if (request.getSubjectId() == null) {
            errors.add("A disciplina é obrigatória");
        }

        if (request.getClassId() == null) {
            errors.add("A turma é obrigatória");
        }

        if (!errors.isEmpty()) {
            String errorMessage = String.join("; ", errors);
            return Mono.error(ApiException.badRequest("Erros de validação: " + errorMessage));
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
