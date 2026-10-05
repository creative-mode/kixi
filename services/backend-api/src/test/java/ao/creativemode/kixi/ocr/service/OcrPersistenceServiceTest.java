package ao.creativemode.kixi.ocr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.junit.jupiter.api.Test;

import ao.creativemode.kixi.ocr.client.OcrServiceClient;
import ao.creativemode.kixi.ocr.client.OcrUploadedFile;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.ocr.dto.OcrResponse;
import ao.creativemode.kixi.ocr.dto.OcrResponse.ConfidenceField;
import ao.creativemode.kixi.ocr.dto.OcrResponse.OcrMetadata;
import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.model.Course;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.academic.model.Subject;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.CourseRepository;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
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
    private CourseRepository courseRepository;
    private SubjectRepository subjectRepository;
    private ClassRepository classRepository;
    private OcrImageAssociationService imageAssociationService;
    private OcrPersistenceService service;

    @BeforeEach
    void setUp() {
        ocrServiceClient = mock(OcrServiceClient.class);
        statementRepository = mock(StatementRepository.class);
        questionRepository = mock(QuestionRepository.class);
        optionRepository = mock(QuestionOptionRepository.class);
        schoolYearRepository = mock(SchoolYearRepository.class);
        courseRepository = mock(CourseRepository.class);
        subjectRepository = mock(SubjectRepository.class);
        classRepository = mock(ClassRepository.class);
        imageAssociationService = mock(OcrImageAssociationService.class);

        service = new OcrPersistenceService(
            ocrServiceClient,
            statementRepository,
            questionRepository,
            optionRepository,
            schoolYearRepository,
            courseRepository,
            subjectRepository,
            classRepository,
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

    @Test
    void findOrCreateClassPicksFirstMatchInsteadOfErroringOnDuplicateRows() throws Exception {
        Course course = new Course();
        course.setId(1L);
        SchoolYear schoolYear = new SchoolYear();
        schoolYear.setId(1L);

        Class firstMatch = new Class();
        firstMatch.setId(1L);
        Class secondMatch = new Class();
        secondMatch.setId(4L);
        when(classRepository.findByGradeAndCourseIdAndSchoolYearIdAndDeletedAtIsNull(12, 1L, 1L))
            .thenReturn(Flux.just(firstMatch, secondMatch));

        OcrMetadata metadata = metadataWithGrade("12");

        Method findOrCreateClass = OcrPersistenceService.class.getDeclaredMethod(
            "findOrCreateClass", OcrMetadata.class, Course.class, SchoolYear.class);
        findOrCreateClass.setAccessible(true);

        @SuppressWarnings("unchecked")
        Mono<Class> result = (Mono<Class>) findOrCreateClass.invoke(service, metadata, course, schoolYear);

        StepVerifier.create(result)
            .assertNext(resolved -> assertThat(resolved.getId()).isEqualTo(1L))
            .verifyComplete();
    }

    @Test
    void findOrCreateCoursePicksFirstMatchInsteadOfErroringOnDuplicateRows() throws Exception {
        Course firstMatch = new Course();
        firstMatch.setId(1L);
        Course secondMatch = new Course();
        secondMatch.setId(9L);
        when(courseRepository.findByNameIgnoreCaseAndDeletedAtIsNull("TODOS"))
            .thenReturn(Flux.just(firstMatch, secondMatch));

        OcrMetadata metadata = metadataWithCourseName("TODOS");

        Method findOrCreateCourse = OcrPersistenceService.class.getDeclaredMethod(
            "findOrCreateCourse", OcrMetadata.class);
        findOrCreateCourse.setAccessible(true);

        @SuppressWarnings("unchecked")
        Mono<Course> result = (Mono<Course>) findOrCreateCourse.invoke(service, metadata);

        StepVerifier.create(result)
            .assertNext(resolved -> assertThat(resolved.getId()).isEqualTo(1L))
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
