package ao.creativemode.kixi.ocr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.junit.jupiter.api.Test;

import ao.creativemode.kixi.ocr.client.OcrServiceClient;
import ao.creativemode.kixi.ocr.client.OcrUploadedFile;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.ocr.dto.OcrResponse;
import ao.creativemode.kixi.ocr.dto.OcrResponse.ConfidenceField;
import ao.creativemode.kixi.ocr.dto.OcrResponse.OcrMetadata;
import ao.creativemode.kixi.ocr.dto.OcrResponse.ExtractedQuestion;
import ao.creativemode.kixi.ocr.dto.OcrResponse.FormulaSegment;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.academic.model.Subject;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OcrPersistenceServiceTest {

    private OcrServiceClient ocrServiceClient;
    private StatementRepository statementRepository;
    private QuestionRepository questionRepository;
    private QuestionOptionRepository optionRepository;
    private SchoolYearRepository schoolYearRepository;
    private SubjectRepository subjectRepository;
    private OcrImageAssociationService imageAssociationService;
    private OcrPersistenceService service;

    @BeforeEach
    void setUp() {
        ocrServiceClient = mock(OcrServiceClient.class);
        statementRepository = mock(StatementRepository.class);
        questionRepository = mock(QuestionRepository.class);
        optionRepository = mock(QuestionOptionRepository.class);
        schoolYearRepository = mock(SchoolYearRepository.class);
        subjectRepository = mock(SubjectRepository.class);
        imageAssociationService = mock(OcrImageAssociationService.class);

        service = new OcrPersistenceService(
            ocrServiceClient,
            statementRepository,
            questionRepository,
            optionRepository,
            schoolYearRepository,
            subjectRepository,
            imageAssociationService,
            // The OCR HTTP call must run outside the transaction; only the
            // persistence step is wrapped. Here the operator just invokes the
            // callback, which is all a unit test needs to observe the flow.
            new TransactionalOperator() {
                @Override
                public <T> Flux<T> execute(org.springframework.transaction.reactive.TransactionCallback<T> action) {
                    return Flux.from(action.doInTransaction(null));
                }
            }
        );
    }

    @Test
    void convertsOcrErrorIntoBadRequestAndDoesNotTouchRepositories() {
        OcrResponse response = new OcrResponse(
            "error",
            "req-error",
            10,
            0.0,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Unreadable document"
        );
        when(ocrServiceClient.extractTextFromUploadedFiles(anyList())).thenReturn(Mono.just(response));

        OcrUploadedFile source = new OcrUploadedFile(
            "exam.png",
            org.springframework.http.MediaType.IMAGE_PNG,
            new byte[] {1}
        );

        StepVerifier.create(service.processAndPersist(List.of(source), 7L))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ApiException.class);
                ApiException apiException = (ApiException) error;
                assertThat(apiException.getStatusCode()).isEqualTo(400);
                assertThat(apiException.getMessage())
                    .isEqualTo("OCR extraction failed");
            })
            .verify();

        verify(statementRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(questionRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(ocrServiceClient).extractTextFromUploadedFiles(anyList());
    }

    // =========================================================================
    // Regression: findOrCreateClass/Course/Subject used to look up their
    // match with a Mono-returning repository method. Nothing in the schema
    // enforces uniqueness on (grade, course, school year) or on a course/
    // subject's name, so once two rows happened to match, R2DBC threw
    // IncorrectResultSizeDataAccessException and the whole OCR persistence
    // request failed with a 500 - even though a perfectly good match existed.
    // The repositories now return Flux and the service takes the first
    // result via .next(); these tests exercise that private behavior
    // directly via reflection, since it isn't reachable through the small
    // success-path fixture the OcrResponse record would otherwise require.
    // =========================================================================

    // Courses and classes belong to a school, which the paper does not say (issue #253): the
    // legacy path leaves the statement without them instead of creating or borrowing one.
    @Test
    void persistingAnOcrResponseLeavesTheStatementWithoutCourseAndClass() {
        Subject subject = new Subject();
        subject.setId(3L);
        subject.setName("Matemática");
        SchoolYear year = new SchoolYear();
        year.setId(2L);
        year.setStartYear(2025);
        year.setEndYear(2026);
        when(schoolYearRepository.findByStartYearAndEndYearAndDeletedAtIsNull(2025, 2026))
            .thenReturn(Mono.just(year));
        when(subjectRepository.findByNameIgnoreCaseAndDeletedAtIsNull("Matemática"))
            .thenReturn(Flux.just(subject));
        when(statementRepository.save(org.mockito.ArgumentMatchers.any(ao.creativemode.kixi.exams.model.Statement.class)))
            .thenAnswer(invocation -> {
                ao.creativemode.kixi.exams.model.Statement saved = invocation.getArgument(0);
                saved.setId(9L);
                return Mono.just(saved);
            });
        when(optionRepository.findAllByQuestionIds(anyList())).thenReturn(Flux.empty());
        when(imageAssociationService.persistQuestionImages(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(List.of()));

        OcrMetadata metadata = new OcrMetadata(
            null, null, null, null, null,
            new ConfidenceField<Integer>(2025, 0.9), new ConfidenceField<Integer>(2026, 0.9),
            new ConfidenceField<String>("12", 0.9), new ConfidenceField<String>("INFORMÁTICA", 0.9),
            new ConfidenceField<String>("Matemática", 0.9),
            null, null, null, null, null, null);
        OcrResponse response = new OcrResponse(
            "success", "req-1", 10, 0.9, null, metadata, List.of(), List.of(), List.of(), List.of(), null);

        StepVerifier.create(service.persistOcrResponse(response, 7L, List.of()))
            .assertNext(result -> {
                assertThat(result.statement().getCourseId()).isNull();
                assertThat(result.statement().getClassId()).isNull();
                assertThat(result.course()).isNull();
                assertThat(result.classEntity()).isNull();
                assertThat(result.statement().getSubjectId()).isEqualTo(3L);
                assertThat(result.statement().getTitle()).contains("12ª Classe");
            })
            .verifyComplete();
    }

    @Test
    void findOrCreateSubjectPicksFirstMatchInsteadOfErroringOnDuplicateRows() throws Exception {
        Subject firstMatch = new Subject();
        firstMatch.setId(1L);
        Subject secondMatch = new Subject();
        secondMatch.setId(10L);
        when(subjectRepository.findByNameIgnoreCaseAndDeletedAtIsNull("Matemática"))
            .thenReturn(Flux.just(firstMatch, secondMatch));

        OcrMetadata metadata = metadataWithSubjectName("Matemática");

        Method findOrCreateSubject = OcrPersistenceService.class.getDeclaredMethod(
            "findOrCreateSubject", OcrMetadata.class);
        findOrCreateSubject.setAccessible(true);

        @SuppressWarnings("unchecked")
        Mono<Subject> result = (Mono<Subject>) findOrCreateSubject.invoke(service, metadata);

        StepVerifier.create(result)
            .assertNext(resolved -> assertThat(resolved.getId()).isEqualTo(1L))
            .verifyComplete();
    }

    @Test
    void directOcrPersistenceKeepsOriginalTextFormulaConfidenceAndReviewState() throws Exception {
        ExtractedQuestion extracted = new ExtractedQuestion(
            "1", 0.71, List.of(), List.of(), new ConfidenceField<>("Determine v0", 0.71),
            "dissertativa", null, List.of(), false, null, 0, 0, 100,
            List.of(new FormulaSegment("v0", "v_0", 0.62, "formula")), "1 Determine v0 (source OCR)");
        Method mapper = OcrPersistenceService.class.getDeclaredMethod(
            "mapExtractedToQuestion", Long.class, ExtractedQuestion.class, int.class);
        mapper.setAccessible(true);

        Question saved = (Question) mapper.invoke(service, 9L, extracted, 0);

        assertThat(saved.getText()).isEqualTo("Determine v0");
        assertThat(saved.getRawText()).isEqualTo("1 Determine v0 (source OCR)");
        assertThat(saved.getNeedsReview()).isTrue();
        assertThat(saved.getOcrReviewed()).isFalse();
        assertThat(saved.getOcrFormulas()).contains("v_0", "0.62");
    }

    @Test
    void persistsStructuredSubitemsAsChildrenWithoutFlatteningTheirMathEvidence() throws Exception {
        AtomicLong ids = new AtomicLong();
        when(questionRepository.save(any(Question.class))).thenAnswer(invocation -> {
            Question question = invocation.getArgument(0);
            question.setId(ids.incrementAndGet());
            return Mono.just(question);
        });

        FormulaSegment statementFormula = new FormulaSegment("v₀", "v_0", 0.94, "formula", 0.91, 0.96);
        FormulaSegment itemFormula = new FormulaSegment("x²", "x^2", 0.61, "formula", 0.58, 0.83);
        ExtractedQuestion extracted = new ExtractedQuestion(
            "3", 0.92, List.of("a", "b"),
            List.of(
                new OcrResponse.SubitemContent("a", "Calcule x.", 2.0, "dissertativa", 0.72,
                    List.of(itemFormula), "a) Calcule x. OCR source"),
                new OcrResponse.SubitemContent("b", "Indique o resultado.", 1.0, "dissertativa", 0.95,
                    List.of())
            ),
            new ConfidenceField<>("Determine v₀.", 0.92), "composta", 3.0, List.of(), false, null, 0, 20, 80,
            List.of(statementFormula));

        Method createQuestions = OcrPersistenceService.class.getDeclaredMethod(
            "createQuestions", Long.class, List.class);
        createQuestions.setAccessible(true);
        @SuppressWarnings("unchecked")
        Flux<Question> persisted = (Flux<Question>) createQuestions.invoke(service, 9L, List.of(extracted));

        List<Question> questions = persisted.collectList().block();

        assertThat(questions).hasSize(3);
        Question parent = questions.get(0);
        Question firstItem = questions.get(1);
        Question secondItem = questions.get(2);
        assertThat(parent.getQuestionType()).isEqualTo(Question.GROUP);
        assertThat(parent.getText()).isEqualTo("Determine v₀.");
        assertThat(firstItem.getRawText()).isEqualTo("a) Calcule x. OCR source");
        assertThat(parent.getRawText()).isEqualTo("Determine v₀.");
        assertThat(parent.getMaxScore()).isNull();
        assertThat(parent.getOcrFormulas()).contains("v₀", "v_0", "0.94");
        assertThat(firstItem.getParentId()).isEqualTo(parent.getId());
        assertThat(firstItem.getLabel()).isEqualTo("a");
        assertThat(firstItem.getNumber()).isEqualTo(301);
        assertThat(firstItem.getOrderIndex()).isEqualTo(1);
        assertThat(firstItem.getText()).isEqualTo("Calcule x.");
        assertThat(firstItem.getQuestionType()).isEqualTo("development");
        assertThat(firstItem.getMaxScore()).isEqualTo(2.0);
        assertThat(firstItem.getOcrConfidence()).isEqualTo(0.72);
        assertThat(firstItem.getNeedsReview()).isTrue();
        assertThat(firstItem.getOcrFormulas()).contains("x²", "x^2", "0.61");
        assertThat(secondItem.getParentId()).isEqualTo(parent.getId());
        assertThat(secondItem.getLabel()).isEqualTo("b");
        assertThat(secondItem.getNumber()).isEqualTo(302);
        assertThat(secondItem.getOrderIndex()).isEqualTo(2);
    }

    // OcrMetadata field order: examType, durationMinutes, variant, title,
    // instructions, schoolYearStart, schoolYearEnd, classGrade, courseName,
    // subjectName, totalMaxScore, schoolYear, term, subject, course, classInfo
    private OcrMetadata metadataWithGrade(String grade) {
        return new OcrMetadata(
            null, null, null, null, null, null, null,
            new ConfidenceField<>(grade, 0.9), // classGrade
            null, null, null, null, null, null, null, null
        );
    }

    private OcrMetadata metadataWithCourseName(String courseName) {
        return new OcrMetadata(
            null, null, null, null, null, null, null, null,
            new ConfidenceField<>(courseName, 0.9), // courseName
            null, null, null, null, null, null, null
        );
    }

    private OcrMetadata metadataWithSubjectName(String subjectName) {
        return new OcrMetadata(
            null, null, null, null, null, null, null, null, null,
            new ConfidenceField<>(subjectName, 0.9), // subjectName
            null, null, null, null, null, null
        );
    }
}
