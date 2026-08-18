package ao.creativemode.kixi.service;

import ao.creativemode.kixi.client.OcrServiceClient;
import ao.creativemode.kixi.client.OcrUploadedFile;
import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.ocr.OcrResponse;
import ao.creativemode.kixi.dto.ocr.OcrResponse.ExtractedOption;
import ao.creativemode.kixi.dto.ocr.OcrResponse.ExtractedQuestion;
import ao.creativemode.kixi.dto.ocr.OcrResponse.OcrMetadata;
import ao.creativemode.kixi.model.Class;
import ao.creativemode.kixi.model.Course;
import ao.creativemode.kixi.model.Question;
import ao.creativemode.kixi.model.QuestionOption;
import ao.creativemode.kixi.model.SchoolYear;
import ao.creativemode.kixi.model.Statement;
import ao.creativemode.kixi.model.Subject;
import ao.creativemode.kixi.repository.ClassRepository;
import ao.creativemode.kixi.repository.CourseRepository;
import ao.creativemode.kixi.repository.QuestionOptionRepository;
import ao.creativemode.kixi.repository.QuestionRepository;
import ao.creativemode.kixi.repository.SchoolYearRepository;
import ao.creativemode.kixi.repository.StatementRepository;
import ao.creativemode.kixi.repository.SubjectRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    private static final double LOW_CONFIDENCE_THRESHOLD = 0.8;
    private static final double MIN_CONFIDENCE_THRESHOLD = 0.5;

    private final OcrServiceClient ocrServiceClient;
    private final StatementRepository statementRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final SchoolYearRepository schoolYearRepository;
    private final CourseRepository courseRepository;
    private final SubjectRepository subjectRepository;
    private final ClassRepository classRepository;
    private final OcrImageAssociationService imageAssociationService;

    public OcrPersistenceService(
        OcrServiceClient ocrServiceClient,
        StatementRepository statementRepository,
        QuestionRepository questionRepository,
        QuestionOptionRepository optionRepository,
        SchoolYearRepository schoolYearRepository,
        CourseRepository courseRepository,
        SubjectRepository subjectRepository,
        ClassRepository classRepository,
        OcrImageAssociationService imageAssociationService
    ) {
        this.ocrServiceClient = ocrServiceClient;
        this.statementRepository = statementRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.schoolYearRepository = schoolYearRepository;
        this.courseRepository = courseRepository;
        this.subjectRepository = subjectRepository;
        this.classRepository = classRepository;
        this.imageAssociationService = imageAssociationService;
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
    @Transactional
    public Mono<StatementWithRelations> processAndPersist(
        List<OcrUploadedFile> files,
        Long createdBy
    ) {
        log.info(
            "Processing OCR and persisting: {} file(s), createdBy={}",
            files.size(),
            createdBy
        );

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

                return persistOcrResponse(ocrResponse, createdBy, files);
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
    @Transactional
    public Mono<StatementWithRelations> persistOcrResponse(
        OcrResponse ocrResponse,
        Long createdBy,
        List<OcrUploadedFile> sourceFiles
    ) {
        OcrMetadata metadata = ocrResponse.metadata();

        // Step 1: Find or create SchoolYear
        Mono<SchoolYear> schoolYearMono = findOrCreateSchoolYear(metadata);

        // Step 2: Find or create Course
        Mono<Course> courseMono = findOrCreateCourse(metadata);

        // Step 3: Find or create Subject
        Mono<Subject> subjectMono = findOrCreateSubject(metadata);

        // Combine the lookups and then create Class and Statement
        return Mono.zip(schoolYearMono, courseMono, subjectMono).flatMap(
            tuple -> {
                SchoolYear schoolYear = tuple.getT1();
                Course course = tuple.getT2();
                Subject subject = tuple.getT3();

                // Step 4: Find or create Class
                return findOrCreateClass(metadata, course, schoolYear).flatMap(
                    classEntity -> {
                        // Step 5: Create Statement
                        return createStatement(
                            ocrResponse,
                            metadata,
                            createdBy,
                            schoolYear,
                            course,
                            subject,
                            classEntity
                        ).flatMap(statement -> {
                            // Step 6: Create Questions
                            return createQuestions(
                                statement.getId(),
                                ocrResponse.questions()
                            )
                                .collectList()
                                .flatMap(questions -> {
                                    // Step 7: Load all options
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
                                                course,
                                                subject,
                                                classEntity,
                                                questions,
                                                options,
                                                ocrResponse.imagesToUpload()
                                            )));
                                });
                        });
                    }
                );
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
     * Find or create a Course based on OCR metadata.
     */
    private Mono<Course> findOrCreateCourse(OcrMetadata metadata) {
        String courseName = metadata.getCourseNameValue();

        if (courseName == null || courseName.isBlank()) {
            courseName = "TODOS"; // Default course for general exams
        }

        // Normalize course name
        String normalizedName = normalizeCourseName(courseName);
        final String finalCourseName = normalizedName;

        return courseRepository
            .findByNameIgnoreCaseAndDeletedAtIsNull(normalizedName)
            .switchIfEmpty(
                Mono.defer(() -> {
                    log.info("Creating new course from OCR metadata");
                    Course newCourse = new Course();
                    newCourse.setName(finalCourseName);
                    newCourse.setCode(generateCourseCode(finalCourseName));
                    return courseRepository.save(newCourse);
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

    /**
     * Find or create a Class based on OCR metadata.
     */
    private Mono<Class> findOrCreateClass(
        OcrMetadata metadata,
        Course course,
        SchoolYear schoolYear
    ) {
        String gradeStr = metadata.getClassGradeValue();

        if (gradeStr == null || gradeStr.isBlank()) {
            gradeStr = "12"; // Default to 12th grade for exams
            log.warn("Class grade not extracted, using default");
        }

        // Parse grade to Integer
        Integer grade;
        try {
            grade = Integer.parseInt(gradeStr.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            grade = 12; // Default to 12th grade
            log.warn("Could not parse OCR class grade, using default: 12");
        }

        final Integer finalGrade = grade;
        final String gradeCode = String.valueOf(grade);

        // Try to find with course first
        if (course != null && course.getId() != null) {
            return classRepository
                .findByGradeAndCourseIdAndSchoolYearIdAndDeletedAtIsNull(
                    grade,
                    course.getId(),
                    schoolYear.getId()
                )
                .switchIfEmpty(
                    Mono.defer(() -> {
                        log.info(
                            "Creating new class: grade={}, courseId={}, schoolYearId={}",
                            finalGrade,
                            course.getId(),
                            schoolYear.getId()
                        );
                        Class newClass = new Class();
                        newClass.setGrade(finalGrade);
                        newClass.setCourseId(course.getId());
                        newClass.setSchoolYearId(schoolYear.getId());
                        newClass.setCode(
                            generateClassCode(gradeCode, course.getCode())
                        );
                        return classRepository.save(newClass);
                    })
                );
        }

        // Find without course
        return classRepository
            .findByGradeAndSchoolYearIdAndDeletedAtIsNull(
                grade,
                schoolYear.getId()
            )
            .switchIfEmpty(
                Mono.defer(() -> {
                    log.info(
                        "Creating new class: grade={}, schoolYearId={}",
                        finalGrade,
                        schoolYear.getId()
                    );
                    Class newClass = new Class();
                    newClass.setGrade(finalGrade);
                    newClass.setSchoolYearId(schoolYear.getId());
                    newClass.setCode(generateClassCode(gradeCode, null));
                    return classRepository.save(newClass);
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
        Course course,
        Subject subject,
        Class classEntity
    ) {
        Statement statement = new Statement();

        // Title
        if (metadata.title() != null && metadata.title().value() != null) {
            statement.setTitle(metadata.title().value());
        } else {
            statement.setTitle(
                buildDefaultTitle(metadata, subject, classEntity, schoolYear)
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
        statement.setCourseId(course != null ? course.getId() : null);
        statement.setSubjectId(subject.getId());
        statement.setClassId(classEntity.getId());
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

        return Flux.fromIterable(extractedQuestions)
            .index()
            .flatMap(tuple -> {
                long index = tuple.getT1();
                ExtractedQuestion extracted = tuple.getT2();

                Question question = mapExtractedToQuestion(
                    statementId,
                    extracted,
                    (int) index
                );

                return questionRepository
                    .save(question)
                    .flatMap(savedQuestion -> {
                        // Create options if this is a multiple choice question
                        if (
                            extracted.options() != null &&
                            !extracted.options().isEmpty()
                        ) {
                            return createOptions(
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
            question.setNumber(
                Integer.parseInt(extracted.number().replaceAll("[^0-9]", ""))
            );
        } catch (NumberFormatException e) {
            question.setNumber(orderIndex + 1);
        }

        question.setOrderIndex(orderIndex);
        question.setText(extracted.getTextValue());
        question.setQuestionType(mapQuestionType(extracted.getTypeValue()));

        // Cotação (score)
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
     * Normalize course name for consistent storage.
     */
    private String normalizeCourseName(String name) {
        if (name == null) return null;
        return name.trim().toUpperCase();
    }

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
     * Generate course code from name.
     */
    private String generateCourseCode(String name) {
        if (name == null) return "GEN";
        return name.length() > 3
            ? name.substring(0, 3).toUpperCase()
            : name.toUpperCase();
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
     * Generate class code.
     */
    private String generateClassCode(String grade, String courseCode) {
        if (courseCode != null && !courseCode.isBlank()) {
            return grade + "-" + courseCode;
        }
        return grade + "-GEN";
    }

    /**
     * Build a default title from metadata.
     */
    private String buildDefaultTitle(
        OcrMetadata metadata,
        Subject subject,
        Class classEntity,
        SchoolYear schoolYear
    ) {
        StringBuilder title = new StringBuilder("Prova de Exame");

        if (subject != null) {
            title.append(" de ").append(subject.getName());
        }

        if (classEntity != null) {
            title.append(" ").append(classEntity.getGrade()).append("ª Classe");
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
