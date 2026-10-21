package ao.creativemode.kixi.ocr.service;

import ao.creativemode.kixi.ocr.client.OcrServiceClient;
import ao.creativemode.kixi.ocr.client.OcrUploadedFile;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.ocr.dto.OcrResponse;
import ao.creativemode.kixi.ocr.dto.OcrResponse.ExtractedOption;
import ao.creativemode.kixi.ocr.dto.OcrResponse.ExtractedQuestion;
import ao.creativemode.kixi.ocr.dto.OcrResponse.OcrMetadata;
import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.model.Course;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.dto.statement.ManualStatementRequest;
import ao.creativemode.kixi.exams.service.QuestionOcrEvidence;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.academic.model.Subject;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service for persisting OCR extraction results.
 *
 * Handles the complete workflow of:
 * - Extracting data via OCR
 * - Looking up or creating related entities (SchoolYear, Course, Subject, Class)
 * - Creating Statement with Questions and Options
 *
 * Implements the uniqueness constraints:
 * - school_years: unique by (start_year, end_year)
 * - courses: unique by name (normalized)
 * - subjects: unique by name
 * - classes: unique by (grade, course_id, school_year_id)
 * - statement: unique by (title + variant + school_year_id + subject_id + class_id)
 */
@Service
public class OcrPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(
        OcrPersistenceService.class
    );

    private static final double MIN_CONFIDENCE_THRESHOLD = 0.5;

    private final OcrServiceClient ocrServiceClient;
    private final StatementRepository statementRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final SchoolYearRepository schoolYearRepository;
    private final SubjectRepository subjectRepository;
    private final OcrImageAssociationService imageAssociationService;
    private final TransactionalOperator transactionalOperator;

    public OcrPersistenceService(
        OcrServiceClient ocrServiceClient,
        StatementRepository statementRepository,
        QuestionRepository questionRepository,
        QuestionOptionRepository optionRepository,
        SchoolYearRepository schoolYearRepository,
        SubjectRepository subjectRepository,
        OcrImageAssociationService imageAssociationService,
        TransactionalOperator transactionalOperator
    ) {
        this.ocrServiceClient = ocrServiceClient;
        this.statementRepository = statementRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.schoolYearRepository = schoolYearRepository;
        this.subjectRepository = subjectRepository;
        this.imageAssociationService = imageAssociationService;
        this.transactionalOperator = transactionalOperator;
    }

    // =========================================================================
    // Main OCR Processing Methods
    // =========================================================================

    /**
     * Process uploaded files via OCR and persist the results.
     *
     * @param files     List of uploaded image files
     * @param createdBy ID of the user creating the statement
     * @return Mono containing the created statement with all related data
     */
    // Deliberately NOT @Transactional: the OCR call below is a remote HTTP
    // request that can take seconds, and a transaction would hold a database
    // connection open for its whole duration. The persistence step is wrapped
    // in an explicit transaction instead, which also survives the self
    // invocation that would otherwise bypass a proxy-based @Transactional.
    public Mono<StatementWithRelations> processAndPersist(
        List<OcrUploadedFile> files,
        Long createdBy
    ) {
        log.info("Processing OCR and persisting: {} file(s)", files.size());

        return ocrServiceClient
            .extractTextFromUploadedFiles(files)
            .flatMap(ocrResponse -> {
                if (ocrResponse.isError()) {
                    log.error("OCR extraction failed: requestId={}",
                        ocrResponse.requestId());
                    return Mono.error(
                        ApiException.badRequest("OCR extraction failed")
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

                return transactionalOperator
                    .<StatementWithRelations>execute(tx -> persistOcrResponse(ocrResponse, createdBy, files))
                    .single();
            })
            .doOnSuccess(result ->
                log.info(
                    "Statement created from OCR: statementId={}",
                    result.statement().getId()
                )
            )
            .doOnError(error -> log.error(
                "Failed to process and persist OCR: type={}",
                error.getClass().getSimpleName()));
    }

    /**
     * Persist an OCR response to the database.
     *
     * @param ocrResponse The OCR response containing extracted data
     * @param createdBy   ID of the user creating the statement
     * @return Mono containing the created statement with all related data
     */
    // Transaction boundary is the caller's transactionalOperator: this method
    // is only ever reached through a self invocation, which never goes through
    // the Spring proxy, so an annotation here would silently do nothing.
    public Mono<StatementWithRelations> persistOcrResponse(
        OcrResponse ocrResponse,
        Long createdBy,
        List<OcrUploadedFile> sourceFiles
    ) {
        OcrMetadata metadata = ocrResponse.metadata();

        // Step 1: Find or create SchoolYear
        Mono<SchoolYear> schoolYearMono = findOrCreateSchoolYear(metadata);

        // Step 2: Find or create Subject
        Mono<Subject> subjectMono = findOrCreateSubject(metadata);

        // The statement is left without a course and without a class. Courses and classes belong to
        // a school since V30 (and a course code only to be unique inside one, since V50), so
        // creating or reusing one by the name read from the paper would either fail or attach the
        // statement to some other school's course. The builder links it to the right class later
        // (issue #253).
        return Mono.zip(schoolYearMono, subjectMono).flatMap(
            tuple -> {
                SchoolYear schoolYear = tuple.getT1();
                Subject subject = tuple.getT2();

                // Step 3: Create Statement
                return createStatement(
                    ocrResponse,
                    metadata,
                    createdBy,
                    schoolYear,
                    subject
                ).flatMap(statement -> {
                    // Step 4: Create Questions
                    return createQuestions(
                        statement.getId(),
                        ocrResponse.questions()
                    )
                        .collectList()
                        .flatMap(questions -> {
                            // Step 5: Load all options
                            List<Long> questionIds = questions
                                .stream()
                                .map(Question::getId)
                                .toList();

                            return optionRepository
                                .findAllByQuestionIds(questionIds)
                                .collectList()
                                .flatMap(options -> imageAssociationService
                                    .persistQuestionImages(
                                        questions,
                                        ocrResponse.imagesToUpload(),
                                        sourceFiles
                                    )
                                    .map(ignoredImages ->
                                    new StatementWithRelations(
                                        statement,
                                        schoolYear,
                                        null,
                                        subject,
                                        null,
                                        questions,
                                        options,
                                        ocrResponse.imagesToUpload()
                                    )));
                        });
                });
            }
        );
    }

    // =========================================================================
    // Entity Lookup/Create Methods
    // =========================================================================

    /**
     * Find or create a SchoolYear based on OCR metadata.
     */
    private Mono<SchoolYear> findOrCreateSchoolYear(OcrMetadata metadata) {
        Integer startYear = metadata.getSchoolYearStartValue();
        Integer endYear = metadata.getSchoolYearEndValue();

        if (startYear == null || endYear == null) {
            // Default to current academic year
            int currentYear = LocalDateTime.now().getYear();
            int currentMonth = LocalDateTime.now().getMonthValue();
            // Academic year in Angola typically starts in September
            if (currentMonth >= 9) {
                startYear = currentYear;
                endYear = currentYear + 1;
            } else {
                startYear = currentYear - 1;
                endYear = currentYear;
            }
            log.warn(
                "School year not extracted, using default: {}/{}",
                startYear,
                endYear
            );
        }

        final Integer finalStartYear = startYear;
        final Integer finalEndYear = endYear;

        return schoolYearRepository
            .findByStartYearAndEndYearAndDeletedAtIsNull(startYear, endYear)
            .switchIfEmpty(
                Mono.defer(() -> {
                    log.info(
                        "Creating new school year: {}/{}",
                        finalStartYear,
                        finalEndYear
                    );
                    SchoolYear newSchoolYear = new SchoolYear();
                    newSchoolYear.setStartYear(finalStartYear);
                    newSchoolYear.setEndYear(finalEndYear);
                    return schoolYearRepository.save(newSchoolYear);
                })
            );
    }

    /**
     * Find or create a Subject based on OCR metadata.
     */
    private Mono<Subject> findOrCreateSubject(OcrMetadata metadata) {
        String subjectName = metadata.getSubjectNameValue();

        if (subjectName == null || subjectName.isBlank()) {
            return Mono.error(
                ApiException.badRequest(
                    "Subject name is required but not extracted from OCR"
                )
            );
        }

        // Normalize subject name
        String normalizedName = normalizeSubjectName(subjectName);
        final String finalSubjectName = normalizedName;

        return subjectRepository
            .findByNameIgnoreCaseAndDeletedAtIsNull(normalizedName)
            .next()
            .switchIfEmpty(
                Mono.defer(() -> {
                    log.info("Creating new subject from OCR metadata");
                    Subject newSubject = new Subject();
                    newSubject.setName(finalSubjectName);
                    newSubject.setCode(generateSubjectCode(finalSubjectName));
                    newSubject.setShortName(
                        generateShortName(finalSubjectName)
                    );
                    return subjectRepository.save(newSubject);
                })
            );
    }

    // =========================================================================
    // Statement and Questions Creation
    // =========================================================================

    /**
     * Create a Statement from OCR data.
     */
    private Mono<Statement> createStatement(
        OcrResponse ocrResponse,
        OcrMetadata metadata,
        Long createdBy,
        SchoolYear schoolYear,
        Subject subject
    ) {
        Statement statement = new Statement();

        // Title
        if (metadata.title() != null && metadata.title().value() != null) {
            statement.setTitle(metadata.title().value());
        } else {
            statement.setTitle(
                buildDefaultTitle(metadata, subject, schoolYear)
            );
        }

        // Exam type
        if (
            metadata.examType() != null && metadata.examType().value() != null
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
            statement.setDurationMinutes(metadata.durationMinutes().value());
        }

        // Variant
        if (metadata.variant() != null && metadata.variant().value() != null) {
            statement.setVariant(metadata.variant().value());
        }

        // Instructions
        if (
            metadata.instructions() != null &&
            metadata.instructions().value() != null
        ) {
            statement.setInstructions(metadata.instructions().value());
        }

        // Total max score
        if (metadata.getTotalMaxScoreValue() != null) {
            statement.setTotalMaxScore(metadata.getTotalMaxScoreValue());
        } else {
            // Calculate from questions
            double totalScore =
                ocrResponse.questions() != null
                    ? ocrResponse
                          .questions()
                          .stream()
                          .filter(q -> q.getCotacaoValue() != null)
                          .mapToDouble(ExtractedQuestion::getCotacaoValue)
                          .sum()
                    : 0.0;
            if (totalScore > 0) {
                statement.setTotalMaxScore(totalScore);
            }
        }

        // Set foreign keys
        statement.setSchoolYearId(schoolYear.getId());
        statement.setSubjectId(subject.getId());
        statement.setCreatedBy(createdBy);

        // OCR metadata
        statement.setOcrMetadata(
            ocrResponse.requestId(),
            ocrResponse.overallConfidence(),
            ocrResponse.needsReview()
        );
        statement.setSource("ocr");
        statement.setVisible(false); // Require manual review before publishing

        return statementRepository.save(statement);
    }

    /**
     * Create questions from OCR extracted data.
     */
    private Flux<Question> createQuestions(
        Long statementId,
        List<ExtractedQuestion> extractedQuestions
    ) {
        if (extractedQuestions == null || extractedQuestions.isEmpty()) {
            return Flux.empty();
        }

        AtomicInteger orderIndex = new AtomicInteger();
        return Flux.fromIterable(extractedQuestions)
            .concatMap(extracted -> {
                Question question = mapExtractedToQuestion(
                    statementId,
                    extracted,
                    orderIndex.getAndIncrement()
                );

                return questionRepository
                    .save(question)
                    .flatMapMany(savedQuestion -> {
                        if (isStructuredGroup(extracted)) {
                            return createSubitems(statementId, savedQuestion, extracted.subitemsContent(), orderIndex)
                                .startWith(savedQuestion);
                        }

                        // Create options if this is a multiple choice question
                        if (
                            extracted.options() != null &&
                            !extracted.options().isEmpty()
                        ) {
                            return createOptions(
                                savedQuestion.getId(),
                                extracted.options()
                            ).thenMany(Flux.just(savedQuestion));
                        }
                        return Flux.just(savedQuestion);
                    });
            });
    }

    private Flux<Question> createSubitems(
        Long statementId,
        Question parent,
        List<OcrResponse.SubitemContent> subitems,
        AtomicInteger orderIndex
    ) {
        return Flux.fromIterable(subitems)
            .index()
            .concatMap(tuple -> {
                int itemIndex = Math.toIntExact(tuple.getT1());
                OcrResponse.SubitemContent item = tuple.getT2();
                Question child = mapSubitemToQuestion(
                    statementId, parent, item, itemIndex, orderIndex.getAndIncrement());
                return questionRepository.save(child);
            });
    }

    private static boolean isStructuredGroup(ExtractedQuestion extracted) {
        return extracted.subitemsContent() != null
            && !extracted.subitemsContent().isEmpty()
            && (extracted.options() == null || extracted.options().isEmpty());
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
            question.setNumber(
                Integer.parseInt(extracted.number().replaceAll("[^0-9]", ""))
            );
        } catch (NumberFormatException e) {
            question.setNumber(orderIndex + 1);
        }

        question.setOrderIndex(orderIndex);
        boolean structuredGroup = isStructuredGroup(extracted);
        question.setText(structuredGroup ? extracted.getTextValue() : extracted.textWithSubitems());
        question.setQuestionType(structuredGroup ? Question.GROUP : mapQuestionType(extracted.getTypeValue()));
        if (structuredGroup && "brace".equals(extracted.groupLayout())) {
            question.setGroupLayout("brace");
        }

        // Cotação (score)
        if (!structuredGroup && extracted.getCotacaoValue() != null) {
            question.setMaxScore(extracted.getCotacaoValue());
        }

        // OCR metadata
        question.setRawText(extracted.rawText() != null ? extracted.rawText() : extracted.getTextValue());
        question.setOcrConfidence(extracted.confidence());
        question.setOcrFormulas(QuestionOcrEvidence.encode(formulaEvidence(extracted)));
        question.setOcrReviewed(false);
        question.setPageIndex(extracted.pageIndex());

        question.setNeedsReview(QuestionOcrEvidence.needsReview(
            extracted.confidence(), formulaEvidence(extracted), false));

        return question;
    }

    private Question mapSubitemToQuestion(
        Long statementId,
        Question parent,
        OcrResponse.SubitemContent item,
        int itemIndex,
        int orderIndex
    ) {
        int parentNumber = parent.getNumber() == null ? orderIndex + 1 : parent.getNumber();
        Question question = new Question();
        question.setStatementId(statementId);
        question.setNumber(Math.addExact(Math.multiplyExact(parentNumber, 100), itemIndex + 1));
        question.setOrderIndex(orderIndex);
        question.setParentId(parent.getId());
        question.setLabel(item.label() == null || item.label().isBlank()
            ? defaultSubitemLabel(itemIndex)
            : item.label().trim());
        question.setText(item.text());
        question.setQuestionType(mapQuestionType(
            item.type() == null || item.type().isBlank() ? "dissertativa" : item.type()));
        question.setMaxScore(item.cotacao());
        question.setRawText(item.rawText() != null ? item.rawText() : item.text());
        question.setOcrConfidence(item.confidence());
        question.setOcrFormulas(QuestionOcrEvidence.encode(formulaEvidence(item.formulas())));
        question.setOcrReviewed(false);
        question.setNeedsReview(QuestionOcrEvidence.needsReview(
            item.confidence(), formulaEvidence(item.formulas()), false));
        return question;
    }

    private static String defaultSubitemLabel(int index) {
        return index < 26
            ? String.valueOf((char) ('a' + index))
            : (char) ('a' + index % 26) + Integer.toString(index / 26);
    }

    private static List<ManualStatementRequest.Formula> formulaEvidence(ExtractedQuestion extracted) {
        if (extracted.formulas() == null || extracted.formulas().isEmpty()) return null;
        return formulaEvidence(extracted.formulas());
    }

    private static List<ManualStatementRequest.Formula> formulaEvidence(List<OcrResponse.FormulaSegment> formulas) {
        if (formulas == null || formulas.isEmpty()) return null;
        return formulas.stream()
            .map(formula -> new ManualStatementRequest.Formula(
                formula.raw(), formula.latex(), formula.confidence(), formula.kind(),
                formula.ocrConfidence(), formula.layoutConfidence()))
            .toList();
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
            // These two only exist as structure in the exam builder; stored as plain text they read as development.
            case "verdadeiro_falso", "composta" -> "development";
            default -> type;
        };
    }

    /**
     * Create options for a multiple choice question.
     */
    private Flux<QuestionOption> createOptions(
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

    // =========================================================================
    // Utility Methods
    // =========================================================================

    /**
     * Normalize subject name with proper Portuguese capitalization.
     */
    private String normalizeSubjectName(String name) {
        if (name == null) return null;

        // Subject name corrections
        String normalized = name.trim();
        return switch (normalized.toLowerCase()) {
            case "matematica", "matemática" -> "Matemática";
            case "fisica", "física" -> "Física";
            case "quimica", "química" -> "Química";
            case "biologia" -> "Biologia";
            case "portugues", "português" -> "Português";
            case "ingles", "inglês" -> "Inglês";
            case "frances", "francês" -> "Francês";
            case "historia", "história" -> "História";
            case "geografia" -> "Geografia";
            case "filosofia" -> "Filosofia";
            default -> toTitleCase(normalized);
        };
    }

    /**
     * Convert string to title case.
     */
    private String toTitleCase(String text) {
        if (text == null || text.isEmpty()) return text;
        String[] words = text.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) result.append(" ");
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1).toLowerCase());
                }
            }
        }
        return result.toString();
    }

    /**
     * Generate subject code from name.
     */
    private String generateSubjectCode(String name) {
        if (name == null) return "GEN";
        String code = name
            .replaceAll("[aeiouáàâãéèêíïóôõöúç\\s]", "")
            .toUpperCase();
        return code.length() > 4
            ? code.substring(0, 4)
            : (code.isEmpty()
                  ? name.substring(0, Math.min(3, name.length())).toUpperCase()
                  : code);
    }

    /**
     * Generate short name for subject.
     */
    private String generateShortName(String name) {
        if (name == null) return null;
        if (name.length() <= 5) return name;
        return name.substring(0, 5) + ".";
    }

    /**
     * Build a default title from metadata.
     */
    private String buildDefaultTitle(
        OcrMetadata metadata,
        Subject subject,
        SchoolYear schoolYear
    ) {
        StringBuilder title = new StringBuilder("Prova de Exame");

        if (subject != null) {
            title.append(" de ").append(subject.getName());
        }

        String grade = metadata.getClassGradeValue() == null
            ? "" : metadata.getClassGradeValue().replaceAll("[^0-9]", "");
        if (!grade.isEmpty()) {
            title.append(" ").append(grade).append("ª Classe");
        }

        if (metadata.variant() != null && metadata.variant().value() != null) {
            title.append(" - Série ").append(metadata.variant().value());
        }

        title
            .append(" - ")
            .append(schoolYear.getStartYear())
            .append("/")
            .append(schoolYear.getEndYear());

        return title.toString();
    }

    // =========================================================================
    // Result Records
    // =========================================================================

    /**
     * Complete result with statement and all related entities.
     */
    public record StatementWithRelations(
        Statement statement,
        SchoolYear schoolYear,
        Course course,
        Subject subject,
        Class classEntity,
        List<Question> questions,
        List<QuestionOption> options,
        List<OcrResponse.ImageToUpload> imagesToUpload
    ) {}
}
