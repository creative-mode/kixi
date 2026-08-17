package ao.creativemode.kixi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ao.creativemode.kixi.client.OcrServiceClient;
import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.ocr.OcrResponse;
import ao.creativemode.kixi.repository.ClassRepository;
import ao.creativemode.kixi.repository.CourseRepository;
import ao.creativemode.kixi.repository.QuestionOptionRepository;
import ao.creativemode.kixi.repository.QuestionRepository;
import ao.creativemode.kixi.repository.SchoolYearRepository;
import ao.creativemode.kixi.repository.StatementRepository;
import ao.creativemode.kixi.repository.SubjectRepository;
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

        service = new OcrPersistenceService(
            ocrServiceClient,
            statementRepository,
            questionRepository,
            optionRepository,
            schoolYearRepository,
            courseRepository,
            subjectRepository,
            classRepository
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
        when(ocrServiceClient.extractText(anyList())).thenReturn(Mono.just(response));

        StepVerifier.create(service.processAndPersist(List.of(), 7L))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ApiException.class);
                ApiException apiException = (ApiException) error;
                assertThat(apiException.getStatusCode()).isEqualTo(400);
                assertThat(apiException.getMessage())
                    .isEqualTo("OCR extraction failed: Unreadable document");
            })
            .verify();

        verify(statementRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(questionRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(ocrServiceClient).extractText(anyList());
    }
}
