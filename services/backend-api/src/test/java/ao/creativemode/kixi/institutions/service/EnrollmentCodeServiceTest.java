package ao.creativemode.kixi.institutions.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.function.BiFunction;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;

import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.institutions.dto.enrollment.CreateEnrollmentCodeRequest;
import ao.creativemode.kixi.institutions.model.EnrollmentCode;
import ao.creativemode.kixi.institutions.repository.EnrollmentCodeRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class EnrollmentCodeServiceTest {

    private EnrollmentCodeRepository codes;
    private ClassRepository classes;
    private DatabaseClient databaseClient;
    private EnrollmentCodeService service;

    @BeforeEach
    void setUp() {
        codes = mock(EnrollmentCodeRepository.class);
        classes = mock(ClassRepository.class);
        databaseClient = mock(DatabaseClient.class);
        service = new EnrollmentCodeService(codes, classes, databaseClient);
    }

    @Test
    void createsACodeForAnExistingClass() {
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(activeClass(7L)));
        when(codes.existsByCode(anyString())).thenReturn(Mono.just(false));
        when(codes.save(any(EnrollmentCode.class))).thenAnswer(invocation -> {
            EnrollmentCode saved = invocation.getArgument(0);
            saved.setId(1L);
            return Mono.just(saved);
        });

        StepVerifier.create(service.create(5L, new CreateEnrollmentCodeRequest(7L, 3, null)))
            .expectNextMatches(response ->
                response.classId().equals(7L)
                    && response.maxUses().equals(3)
                    && response.uses().equals(0)
                    && response.code() != null && response.code().length() == 8)
            .verifyComplete();
    }

    @Test
    void defaultsMaxUsesToOne() {
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(activeClass(7L)));
        when(codes.existsByCode(anyString())).thenReturn(Mono.just(false));
        when(codes.save(any(EnrollmentCode.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.create(5L, new CreateEnrollmentCodeRequest(7L, null, null)))
            .expectNextMatches(response -> response.maxUses().equals(1))
            .verifyComplete();
    }

    @Test
    void retriesOnAGeneratedCodeCollision() {
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(activeClass(7L)));
        when(codes.existsByCode(anyString())).thenReturn(Mono.just(true), Mono.just(false));
        when(codes.save(any(EnrollmentCode.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.create(5L, new CreateEnrollmentCodeRequest(7L, 1, null)))
            .expectNextCount(1)
            .verifyComplete();
    }

    @Test
    void cannotIssueACodeForAnUnknownClass() {
        when(classes.findByIdAndDeletedAtIsNull(9L)).thenReturn(Mono.empty());

        StepVerifier.create(service.create(5L, new CreateEnrollmentCodeRequest(9L, 1, null)))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.NOT_FOUND);
        verify(codes, never()).save(any(EnrollmentCode.class));
    }

    @Test
    void cannotIssueACodeThatAlreadyExpired() {
        LocalDateTime past = LocalDateTime.now().minusDays(1);

        StepVerifier.create(service.create(5L, new CreateEnrollmentCodeRequest(7L, 1, past)))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.BAD_REQUEST);
        verify(classes, never()).findByIdAndDeletedAtIsNull(any());
    }

    @Test
    void revokingAnUnknownCodeIsNotFound() {
        when(codes.findById(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.revoke(1L))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.NOT_FOUND);
    }

    @Test
    void revokingAnAlreadyRevokedCodeIsANoop() {
        EnrollmentCode code = new EnrollmentCode("ABCD1234", 7L, 1, null, 5L);
        code.setId(1L);
        code.setRevokedAt(LocalDateTime.now().minusHours(1));
        when(codes.findById(1L)).thenReturn(Mono.just(code));

        StepVerifier.create(service.revoke(1L))
            .expectNextCount(1)
            .verifyComplete();
        verify(codes, never()).save(any(EnrollmentCode.class));
    }

    @Test
    void redeemConsumesOneUseAndResolvesTheClass() {
        stubRedeem("ABCD1234", 1L, 7L);

        StepVerifier.create(service.redeem("abcd1234")) // lower case + extra space: normalized before binding
            .expectNextMatches(redemption -> redemption.enrollmentCodeId().equals(1L) && redemption.classId().equals(7L))
            .verifyComplete();
    }

    @Test
    void redeemFailsWhenTheUpdateMatchesNoRow() {
        stubRedeemEmpty("EXPIRED1");

        StepVerifier.create(service.redeem("EXPIRED1"))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void redeemRejectsABlankCodeWithoutTouchingTheDatabase() {
        StepVerifier.create(service.redeem(" "))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.BAD_REQUEST);
        verify(databaseClient, never()).sql(anyString());
    }

    private void stubRedeem(String expectedCode, Long codeId, Long classId) {
        DatabaseClient.GenericExecuteSpec update = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(org.mockito.ArgumentMatchers.contains("UPDATE enrollment_codes")))
                .thenReturn(update);
        when(update.bind(eq("code"), eq(expectedCode))).thenReturn(update);
        when(update.map(any(BiFunction.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            BiFunction<Row, RowMetadata, ?> mapper = invocation.getArgument(0);
            Row row = mock(Row.class);
            when(row.get("id", Long.class)).thenReturn(codeId);
            when(row.get("class_id", Long.class)).thenReturn(classId);
            return rows(Mono.just(mapper.apply(row, null)));
        });
    }

    private void stubRedeemEmpty(String expectedCode) {
        DatabaseClient.GenericExecuteSpec update = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(org.mockito.ArgumentMatchers.contains("UPDATE enrollment_codes")))
                .thenReturn(update);
        // Bound loosely (not eq(expectedCode)): a mismatched matcher here would make bind()
        // return Mockito's default null instead of chaining to `update`, and the resulting
        // NPE from the next call would surface as a raw test error rather than the intended
        // ApiException.
        when(update.bind(anyString(), any())).thenReturn(update);
        when(update.map(any(BiFunction.class))).thenAnswer(invocation -> rows(Mono.empty()));
    }

    @SuppressWarnings("unchecked")
    private static RowsFetchSpec<Object> rows(Mono<?> value) {
        RowsFetchSpec<Object> fetch = mock(RowsFetchSpec.class);
        when(fetch.one()).thenReturn((Mono<Object>) value);
        return fetch;
    }

    private static Class activeClass(Long id) {
        Class clazz = new Class();
        clazz.setId(id);
        return clazz;
    }
}
