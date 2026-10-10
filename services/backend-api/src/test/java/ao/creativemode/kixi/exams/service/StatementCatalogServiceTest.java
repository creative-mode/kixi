package ao.creativemode.kixi.exams.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.exams.dto.statement.StatementCatalogFilter;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.StatementCatalogRepository;
import ao.creativemode.kixi.exams.repository.StatementCatalogRepository.Criteria;
import ao.creativemode.kixi.exams.repository.StatementCatalogRepository.Sort;
import ao.creativemode.kixi.institutions.dto.enrollment.MeResponse;
import ao.creativemode.kixi.institutions.service.MeService;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class StatementCatalogServiceTest {

    private static final Long STUDENT = 42L;
    private static final Long ITEL = 4L;
    private static final Long OTHER_SCHOOL = 9L;
    private static final Long COURSE = 40L;
    private static final Long CLASS = 3L;

    private StatementCatalogRepository repository;
    private MeService meService;
    private StatementCatalogService service;

    @BeforeEach
    void setUp() {
        repository = mock(StatementCatalogRepository.class);
        meService = mock(MeService.class);
        service = new StatementCatalogService(repository, meService);
        when(repository.findPage(any())).thenReturn(Flux.empty());
        when(repository.count(any())).thenReturn(Mono.just(0L));
    }

    @Test
    void byDefaultTheStudentSeesTheirOwnSchoolWithTheirClassAndCourseFirst() {
        givenTheStudentIsEnrolled();

        StepVerifier.create(service.catalog(filter(null, null, null, 0, 20), STUDENT))
                .expectNextCount(1)
                .verifyComplete();

        Criteria criteria = capturedCriteria();
        assertThat(criteria.institutionId()).isEqualTo(ITEL);
        assertThat(criteria.affinityClassId()).isEqualTo(CLASS);
        assertThat(criteria.affinityCourseId()).isEqualTo(COURSE);
        assertThat(criteria.sort()).isEqualTo(Sort.RELEVANCE);
        // The affinity orders; it must not narrow the listing to the class or course.
        assertThat(criteria.classId()).isNull();
        assertThat(criteria.courseId()).isNull();
    }

    @Test
    void anExplicitSchoolWidensTheListingToAnotherInstitution() {
        givenTheStudentIsEnrolled();

        StepVerifier.create(service.catalog(filter(OTHER_SCHOOL, null, null, 0, 20), STUDENT))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(capturedCriteria().institutionId()).isEqualTo(OTHER_SCHOOL);
    }

    @Test
    void scopeAllListsEverySchool() {
        givenTheStudentIsEnrolled();

        StepVerifier.create(service.catalog(filter(null, "all", null, 0, 20), STUDENT))
                .expectNextCount(1)
                .verifyComplete();

        Criteria criteria = capturedCriteria();
        assertThat(criteria.institutionId()).isNull();
        assertThat(criteria.affinityClassId()).isEqualTo(CLASS);
    }

    @Test
    void theProfileIsNotReadWhenNeitherTheSchoolNorTheOrderNeedIt() {
        StepVerifier.create(service.catalog(filter(null, "all", "recent", 0, 20), STUDENT))
                .expectNextCount(1)
                .verifyComplete();

        verifyNoInteractions(meService);
        assertThat(capturedCriteria().sort()).isEqualTo(Sort.RECENT);
    }

    @Test
    void anAccountWithNoAffiliationSeesEverySchool() {
        when(meService.getMe(STUDENT)).thenReturn(Mono.just(me(null, null, null)));

        StepVerifier.create(service.catalog(filter(null, null, null, 0, 20), STUDENT))
                .expectNextCount(1)
                .verifyComplete();

        Criteria criteria = capturedCriteria();
        assertThat(criteria.institutionId()).isNull();
        assertThat(criteria.affinityClassId()).isNull();
        assertThat(criteria.affinityCourseId()).isNull();
    }

    @Test
    void thePageCarriesTheTotalAcrossEveryPage() {
        givenTheStudentIsEnrolled();
        when(repository.findPage(any())).thenReturn(Flux.just(statement(1L), statement(2L)));
        when(repository.count(any())).thenReturn(Mono.just(45L));

        StepVerifier.create(service.catalog(filter(null, null, null, 2, 10), STUDENT))
                .assertNext(page -> {
                    assertThat(page.content()).extracting(Statement::getId).containsExactly(1L, 2L);
                    assertThat(page.page()).isEqualTo(2);
                    assertThat(page.size()).isEqualTo(10);
                    assertThat(page.totalElements()).isEqualTo(45L);
                    assertThat(page.totalPages()).isEqualTo(5);
                })
                .verifyComplete();

        Criteria criteria = capturedCriteria();
        assertThat(criteria.limit()).isEqualTo(10);
        assertThat(criteria.offset()).isEqualTo(20L);
    }

    @Test
    void blankTextFiltersAreIgnoredAndTheRestAreTrimmed() {
        givenTheStudentIsEnrolled();
        StatementCatalogFilter filter = new StatementCatalogFilter(
                null, COURSE, null, 12, 5L, 2024L, 1L, "   ", "  matemática ", null, null, 0, 20);

        StepVerifier.create(service.catalog(filter, STUDENT)).expectNextCount(1).verifyComplete();

        Criteria criteria = capturedCriteria();
        assertThat(criteria.examType()).isNull();
        assertThat(criteria.query()).isEqualTo("matemática");
        assertThat(criteria.courseId()).isEqualTo(COURSE);
        assertThat(criteria.grade()).isEqualTo(12);
        assertThat(criteria.subjectId()).isEqualTo(5L);
        assertThat(criteria.schoolYearId()).isEqualTo(2024L);
        assertThat(criteria.termId()).isEqualTo(1L);
    }

    @Test
    void invalidPagingSortScopeOrTextAreRejectedBeforeTouchingTheDatabase() {
        assertBadRequest(filter(null, null, null, -1, 20));
        assertBadRequest(filter(null, null, null, 0, 0));
        assertBadRequest(filter(null, null, null, 0, StatementCatalogFilter.MAX_SIZE + 1));
        assertBadRequest(filter(null, null, "popular", 0, 20));
        assertBadRequest(filter(null, "everywhere", null, 0, 20));
        assertBadRequest(new StatementCatalogFilter(null, null, null, null, null, null, null, null,
                "x".repeat(StatementCatalogService.MAX_QUERY_LENGTH + 1), null, null, 0, 20));

        verifyNoInteractions(repository, meService);
    }

    private void assertBadRequest(StatementCatalogFilter filter) {
        StepVerifier.create(service.catalog(filter, STUDENT))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(400);
                })
                .verify();
    }

    private void givenTheStudentIsEnrolled() {
        when(meService.getMe(STUDENT)).thenReturn(Mono.just(me(ITEL, COURSE, CLASS)));
    }

    private Criteria capturedCriteria() {
        ArgumentCaptor<Criteria> captor = ArgumentCaptor.forClass(Criteria.class);
        verify(repository).findPage(captor.capture());
        verify(repository).count(captor.getValue());
        return captor.getValue();
    }

    private static StatementCatalogFilter filter(Long institutionId, String scope, String sort, int page, int size) {
        return new StatementCatalogFilter(
                institutionId, null, null, null, null, null, null, null, null, scope, sort, page, size);
    }

    private static MeResponse me(Long schoolId, Long courseId, Long classId) {
        return new MeResponse(
                STUDENT, "aluno", "aluno@itel.ao", "Ana", "Silva", null, List.of("STUDENT"),
                schoolId == null ? null : new MeResponse.SchoolInfo(schoolId, "ITEL", "ITEL"),
                courseId == null ? null : new MeResponse.CourseInfo(courseId, "TI", "Informática"),
                classId == null ? null : new MeResponse.ClassInfo(classId, "TI12A", 12, 2024L, "2024/2025"),
                false);
    }

    private static Statement statement(Long id) {
        Statement statement = new Statement("Exame Final", "Prova " + id);
        statement.setId(id);
        statement.setVisible(true);
        return statement;
    }
}
