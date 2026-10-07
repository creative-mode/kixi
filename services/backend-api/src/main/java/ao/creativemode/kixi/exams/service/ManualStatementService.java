package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.exams.dto.statement.ManualStatementRequest;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.institutions.service.InstitutionAccessService;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Creates statements written in the exam builder. Unlike the OCR flows, the
 * statement is tied to an institution and one of its subjects, and a teacher
 * can only build for an institution they are affiliated with.
 */
@Service
public class ManualStatementService {

    static final String SOURCE = "manual";

    private final StatementRepository statementRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final InstitutionAccessService accessService;
    private final StatementLinkValidationService validator;

    public ManualStatementService(
        StatementRepository statementRepository,
        QuestionRepository questionRepository,
        QuestionOptionRepository optionRepository,
        InstitutionAccessService accessService,
        StatementLinkValidationService validator
    ) {
        this.statementRepository = statementRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.accessService = accessService;
        this.validator = validator;
    }

    @Transactional
    public Mono<Statement> create(ManualStatementRequest request, Long accountId, boolean admin) {
        return validator.requireAClassForTeachers(request.classId(), admin)
            .then(Mono.defer(() -> accessService.requireCanAuthor(
                accountId, admin, request.institutionId(), request.subjectId(), request.classId())))
            .then(Mono.defer(() -> validator.validate(
                request.schoolYearId(),
                request.termId(),
                request.classId(),
                request.subjectId(),
                request.courseId())))
            .then(Mono.defer(() -> statementRepository.save(toStatement(request, accountId))))
            .flatMap(statement ->
                Flux.fromIterable(indexed(request.questions()))
                    .concatMap(entry -> saveQuestion(statement.getId(), entry.number(), entry.question()))
                    .then(Mono.just(statement))
            );
    }

    private Mono<Question> saveQuestion(Long statementId, int number, ManualStatementRequest.Question data) {
        List<ManualStatementRequest.Option> options = data.options() == null ? List.of() : data.options();

        Question question = new Question(
            statementId,
            number,
            data.text().trim(),
            options.isEmpty() ? "open" : "multiple_choice"
        );
        question.setMaxScore(data.maxScore());

        return questionRepository
            .save(question)
            .flatMap(saved ->
                Flux.fromIterable(withOrder(options))
                    .concatMap(option -> {
                        QuestionOption entity = new QuestionOption(
                            saved.getId(),
                            option.option().label().trim(),
                            option.option().text().trim(),
                            Boolean.TRUE.equals(option.option().correct())
                        );
                        entity.setOrderIndex(option.order());
                        return optionRepository.save(entity);
                    })
                    .then(Mono.just(saved))
            );
    }

    private Statement toStatement(ManualStatementRequest request, Long accountId) {
        Statement statement = new Statement();
        statement.setTitle(request.title().trim());
        statement.setExamType(request.examType().trim());
        statement.setDurationMinutes(request.durationMinutes());
        statement.setVariant(request.variant());
        statement.setInstructions(request.instructions());
        statement.setTotalMaxScore(totalScore(request));
        statement.setSchoolYearId(request.schoolYearId());
        statement.setTermId(request.termId());
        statement.setSubjectId(request.subjectId());
        statement.setClassId(request.classId());
        statement.setCourseId(request.courseId());
        statement.setInstitutionId(request.institutionId());
        statement.setCreatedBy(accountId);
        statement.setVisible(Boolean.TRUE.equals(request.visible()));
        statement.setNeedsReview(false);
        statement.setSource(SOURCE);
        statement.setDeletedAt(null);
        return statement;
    }

    private Double totalScore(ManualStatementRequest request) {
        return request.questions().stream()
            .map(ManualStatementRequest.Question::maxScore)
            .filter(score -> score != null)
            .mapToDouble(Double::doubleValue)
            .sum();
    }

    private record NumberedQuestion(int number, ManualStatementRequest.Question question) {}

    private record OrderedOption(int order, ManualStatementRequest.Option option) {}

    private List<NumberedQuestion> indexed(List<ManualStatementRequest.Question> questions) {
        return java.util.stream.IntStream.range(0, questions.size())
            .mapToObj(i -> new NumberedQuestion(i + 1, questions.get(i)))
            .toList();
    }

    private List<OrderedOption> withOrder(List<ManualStatementRequest.Option> options) {
        return java.util.stream.IntStream.range(0, options.size())
            .mapToObj(i -> new OrderedOption(i, options.get(i)))
            .toList();
    }
}
