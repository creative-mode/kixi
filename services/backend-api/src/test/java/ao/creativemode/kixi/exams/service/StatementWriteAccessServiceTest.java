package ao.creativemode.kixi.exams.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.institutions.service.InstitutionAccessService;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The statement-scoped write rule on its own. The question and option services
 * go through the same rule, and the whole chain over HTTP is covered by
 * StatementApprovalAuthorizationTest.
 */
class StatementWriteAccessServiceTest {

    private static final Long ACCOUNT_ID = 9L;
    private static final Long INSTITUTION_ID = 4L;
    private static final Long CLASS_ID = 3L;
    private static final Long SUBJECT_ID = 5L;

    private InstitutionAccessService accessService;
    private StatementWriteAccessService service;

    @BeforeEach
    void setUp() {
        accessService = mock(InstitutionAccessService.class);
        service = new StatementWriteAccessService(accessService);
    }

    @Test
    void anAdministratorIsWeighedAgainstTheInstitutionOnly() {
        when(accessService.requireCanAuthor(ACCOUNT_ID, true, INSTITUTION_ID, SUBJECT_ID, CLASS_ID))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.requireCanWrite(ofSchool(CLASS_ID), ACCOUNT_ID, true))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void aTeacherAssignedToTheClassMayWrite() {
        when(accessService.requireCanAuthor(ACCOUNT_ID, false, INSTITUTION_ID, SUBJECT_ID, CLASS_ID))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.requireCanWrite(ofSchool(CLASS_ID), ACCOUNT_ID, false))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void aTeacherOutsideTheirClassIsForbidden() {
        when(accessService.requireCanAuthor(ACCOUNT_ID, false, INSTITUTION_ID, SUBJECT_ID, CLASS_ID))
                .thenReturn(Mono.error(ApiException.forbidden("not assigned")));

        expectForbidden(ofSchool(CLASS_ID), false);
    }

    @Test
    void aSchoolStatementWithoutAClassIsTheAdministratorsAlone() {
        expectForbidden(ofSchool(null), false);

        verifyNoInteractions(accessService);
    }

    @Test
    void anAdministratorMayWriteASchoolStatementWithoutAClass() {
        when(accessService.requireCanAuthor(ACCOUNT_ID, true, INSTITUTION_ID, SUBJECT_ID, null))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.requireCanWrite(ofSchool(null), ACCOUNT_ID, true))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void aStatementFromTheOcrWithoutASchoolIsWeighedByItsClass() {
        Statement fromTheOcr = statement(1L, null, CLASS_ID);
        when(accessService.requireAssignedTo(ACCOUNT_ID, false, CLASS_ID, SUBJECT_ID))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.requireCanWrite(fromTheOcr, ACCOUNT_ID, false))
                .expectNextCount(1)
                .verifyComplete();

        verify(accessService).requireAssignedTo(ACCOUNT_ID, false, CLASS_ID, SUBJECT_ID);
    }

    @Test
    void aTeacherOutsideThatClassCannotWriteAStatementFromTheOcr() {
        Statement fromTheOcr = statement(1L, null, CLASS_ID);
        when(accessService.requireAssignedTo(ACCOUNT_ID, false, CLASS_ID, SUBJECT_ID))
                .thenReturn(Mono.error(ApiException.forbidden("not assigned")));

        StepVerifier.create(service.requireCanWrite(fromTheOcr, ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
    }

    @Test
    void aStatementFromTheOcrWithNeitherSchoolNorClassHasNothingToWeigh() {
        Statement orphan = statement(1L, null, null);

        StepVerifier.create(service.requireCanWrite(orphan, ACCOUNT_ID, false))
                .expectNextCount(1)
                .verifyComplete();

        verifyNoInteractions(accessService);
    }

    private void expectForbidden(Statement statement, boolean admin) {
        StepVerifier.create(service.requireCanWrite(statement, ACCOUNT_ID, admin))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
    }

    private Statement ofSchool(Long classId) {
        return statement(1L, INSTITUTION_ID, classId);
    }

    private Statement statement(Long id, Long institutionId, Long classId) {
        Statement statement = new Statement("P1", "Prova de Matemática");
        statement.setId(id);
        statement.setInstitutionId(institutionId);
        statement.setClassId(classId);
        statement.setSubjectId(SUBJECT_ID);
        return statement;
    }
}