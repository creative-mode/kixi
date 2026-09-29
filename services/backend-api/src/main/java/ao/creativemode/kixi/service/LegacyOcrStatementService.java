package ao.creativemode.kixi.service;

import ao.creativemode.kixi.client.OcrServiceClient;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.dto.ocr.OcrResponse;
import ao.creativemode.kixi.dto.ocr.OcrResponse.ExtractedOption;
import ao.creativemode.kixi.dto.ocr.OcrResponse.ExtractedQuestion;
import ao.creativemode.kixi.dto.ocr.OcrResponse.OcrMetadata;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.exams.service.StatementWithQuestions;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Simple OCR-to-statement creation path that does not look up or create
 * related entities (SchoolYear, Course, Subject, Class). Kept separate from
 * StatementService, which owns statement CRUD/lifecycle, so that the exams
 * module never needs to depend on the OCR client.
 *
 * For full entity lookup/creation, use OcrPersistenceService instead.
 */
@Service
public class LegacyOcrStatementService {

    private static final Logger log = LoggerFactory.getLogger(
        LegacyOcrStatementService.class
    );

    private static final double LOW_CONFIDENCE_THRESHOLD = 0.8;

    private final StatementRepository statementRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final OcrServiceClient ocrServiceClient;

    public LegacyOcrStatementService(
        StatementRepository statementRepository,
        QuestionRepository questionRepository,
        QuestionOptionRepository optionRepository,
        OcrServiceClient ocrServiceClient
    ) {
        this.statementRepository = statementRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.ocrServiceClient = ocrServiceClient;
    }

    /**
     * Create a statement from uploaded images using OCR.
     *
     * @param files List of uploaded image files
     * @param createdBy ID of the user creating the statement
     * @return Mono containing the created statement with questions
     */
    @Transactional
    public Mono<StatementWithQuestions> createFromOcr(
        List<FilePart> files,
        Long createdBy
    ) {
        log.info(
            "Creating statement from OCR: {} file(s), createdBy={}",
            files.size(),
            createdBy
        );

        return ocrServiceClient
            .extractText(files)
            .flatMap(ocrResponse -> {
                if (ocrResponse.isError()) {
                    log.error(
                        "OCR extraction failed: {}",
                        ocrResponse.errorMessage()
                    );
                    return Mono.error(
                        ApiException.badRequest(
                            "OCR extraction failed: " +
                                ocrResponse.errorMessage()
                        )
                    );
                }

                log.info(
                    "OCR extraction successful: requestId={}, confidence={}, questions={}",
                    ocrResponse.requestId(),
                    ocrResponse.overallConfidence(),
                    ocrResponse.questions() != null
                        ? ocrResponse.questions().size()
                        : 0
                );

                return createStatementFromOcrResponse(ocrResponse, createdBy);
            })
            .doOnSuccess(result ->
                log.info(
                    "Statement created from OCR: statementId={}, questions={}",
                    result.statement().getId(),
                    result.questions().size()
                )
            )
            .doOnError(error -> log.error(
                "Failed to create statement from OCR: type={}",
                error.getClass().getSimpleName()));
    }

    /**
     * Create a statement from an OCR response.
     *
     * @param ocrResponse The OCR response containing extracted data
     * @param createdBy ID of the user creating the statement
     * @return Mono containing the created statement with questions
     */
    @Transactional
    public Mono<StatementWithQuestions> createStatementFromOcrResponse(
        OcrResponse ocrResponse,
        Long createdBy
    ) {
        // Create and populate statement from metadata
        Statement statement = mapMetadataToStatement(
            ocrResponse.metadata(),
            ocrResponse
        );
        statement.setCreatedBy(createdBy);
        statement.setOcrMetadata(
            ocrResponse.requestId(),
            ocrResponse.overallConfidence(),
            ocrResponse.needsReview()
        );
        statement.setSource("ocr");

        // Calculate total max score from questions
        if (ocrResponse.questions() != null) {
            double totalScore = ocrResponse
                .questions()
                .stream()
                .filter(q -> q.getCotacaoValue() != null)
                .mapToDouble(ExtractedQuestion::getCotacaoValue)
                .sum();
            if (totalScore > 0) {
                statement.setTotalMaxScore(totalScore);
            }
        }

        // Save statement first
        return statementRepository
            .save(statement)
            .flatMap(savedStatement -> {
                if (
                    ocrResponse.questions() == null ||
                    ocrResponse.questions().isEmpty()
                ) {
                    return Mono.just(
                        new StatementWithQuestions(
                            savedStatement,
                            List.of(),
                            List.of()
                        )
                    );
                }

                // Create and save questions
                return createQuestionsFromOcr(
                    savedStatement.getId(),
                    ocrResponse.questions()
                )
                    .collectList()
                    .flatMap(savedQuestions -> {
                        // Collect all question IDs
                        List<Long> questionIds = savedQuestions
                            .stream()
                            .map(Question::getId)
                            .toList();

                        // Load all options for these questions
                        return optionRepository
                            .findAllByQuestionIds(questionIds)
                            .collectList()
                            .map(options ->
                                new StatementWithQuestions(
                                    savedStatement,
                                    savedQuestions,
                                    options
                                )
                            );
                    });
            });
    }

    /**
     * Map OCR metadata to Statement entity.
     */
    private Statement mapMetadataToStatement(
        OcrMetadata metadata,
        OcrResponse ocrResponse
    ) {
        Statement statement = new Statement();

        if (metadata != null) {
            // Title
            if (metadata.title() != null && metadata.title().value() != null) {
                statement.setTitle(metadata.title().value());
            } else {
                statement.setTitle(
                    "Imported Statement - " + LocalDateTime.now()
                );
            }

            // Exam type
            if (
                metadata.examType() != null &&
                metadata.examType().value() != null
            ) {
                statement.setExamType(metadata.examType().value());
            } else {
                statement.setExamType("Prova de Exame");
            }

            // Duration
            if (
                metadata.durationMinutes() != null &&
                metadata.durationMinutes().value() != null
            ) {
                statement.setDurationMinutes(
                    metadata.durationMinutes().value()
                );
            }

            // Variant
            if (
                metadata.variant() != null && metadata.variant().value() != null
            ) {
                statement.setVariant(metadata.variant().value());
            }

            // Instructions
            if (
                metadata.instructions() != null &&
                metadata.instructions().value() != null
            ) {
                statement.setInstructions(metadata.instructions().value());
            }

            // Total max score from metadata
            if (metadata.getTotalMaxScoreValue() != null) {
                statement.setTotalMaxScore(metadata.getTotalMaxScoreValue());
            }

            // Note: schoolYearId, termId, subjectId, classId, courseId
            // are resolved in OcrPersistenceService which does the full lookup
        }

        // Set OCR-specific fields
        statement.setVisible(false); // Require manual review before publishing
        statement.setNeedsReview(
            ocrResponse.needsReview() ||
                (ocrResponse.overallConfidence() != null &&
                    ocrResponse.overallConfidence() < LOW_CONFIDENCE_THRESHOLD)
        );

        return statement;
    }

    /**
     * Create questions from OCR extracted questions.
     */
    private Flux<Question> createQuestionsFromOcr(
        Long statementId,
        List<ExtractedQuestion> extractedQuestions
    ) {
        return Flux.fromIterable(extractedQuestions)
            .index()
            .flatMap(tuple -> {
                int index = tuple.getT1().intValue();
                ExtractedQuestion extracted = tuple.getT2();

                Question question = mapExtractedToQuestion(
                    statementId,
                    extracted,
                    index
                );

                return questionRepository
                    .save(question)
                    .flatMap(savedQuestion -> {
                        // Create options if this is a multiple choice question
                        if (
                            extracted.options() != null &&
                            !extracted.options().isEmpty()
                        ) {
                            return createOptionsFromOcr(
                                savedQuestion.getId(),
                                extracted.options()
                            ).then(Mono.just(savedQuestion));
                        }
                        return Mono.just(savedQuestion);
                    });
            });
    }

    /**
     * Map extracted question to Question entity.
     */
    private Question mapExtractedToQuestion(
        Long statementId,
        ExtractedQuestion extracted,
        int orderIndex
    ) {
        Question question = new Question();
        question.setStatementId(statementId);

        // Parse number (might be string like "1", "2a", etc.)
        try {
            String numStr =
                extracted.number() != null
                    ? extracted.number().replaceAll("[^0-9]", "")
                    : "";
            question.setNumber(
                numStr.isEmpty() ? orderIndex + 1 : Integer.parseInt(numStr)
            );
        } catch (NumberFormatException e) {
            question.setNumber(orderIndex + 1);
        }

        question.setOrderIndex(orderIndex);

        // Text
        question.setText(extracted.getTextValue());

        // Question type - map from Portuguese to database format
        String type = extracted.getTypeValue();
        question.setQuestionType(mapQuestionType(type));

        // Cotação (max score)
        if (extracted.getCotacaoValue() != null) {
            question.setMaxScore(extracted.getCotacaoValue());
        }

        // OCR metadata
        question.setOcrConfidence(extracted.confidence());
        question.setPageIndex(extracted.pageIndex());

        // Mark for review if low confidence
        question.setNeedsReview(
            extracted.confidence() != null &&
                extracted.confidence() < LOW_CONFIDENCE_THRESHOLD
        );

        return question;
    }

    /**
     * Map question type from Portuguese to database format.
     */
    private String mapQuestionType(String type) {
        if (type == null) {
            return "unknown";
        }
        return switch (type.toLowerCase()) {
            case "dissertativa" -> "development";
            case "multipla_escolha" -> "multiple_choice";
            default -> type;
        };
    }

    /**
     * Create options from OCR extracted options.
     */
    private Flux<QuestionOption> createOptionsFromOcr(
        Long questionId,
        List<ExtractedOption> extractedOptions
    ) {
        return Flux.fromIterable(extractedOptions)
            .index()
            .flatMap(tuple -> {
                int index = tuple.getT1().intValue();
                ExtractedOption extracted = tuple.getT2();

                QuestionOption option = new QuestionOption();
                option.setQuestionId(questionId);
                option.setOptionLabel(extracted.optionLabel());
                option.setOptionText(extracted.optionText());
                option.setOrderIndex(index);
                option.setOcrConfidence(extracted.confidence());
                option.setIsCorrect(false); // OCR cannot determine correct answer

                return optionRepository.save(option);
            });
    }
}
