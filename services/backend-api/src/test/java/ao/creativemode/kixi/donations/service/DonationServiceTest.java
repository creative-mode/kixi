package ao.creativemode.kixi.donations.service;

import static ao.creativemode.kixi.donations.service.DonationFixtures.executable;
import static ao.creativemode.kixi.donations.service.DonationFixtures.pdf;
import static ao.creativemode.kixi.donations.service.DonationFixtures.png;
import static ao.creativemode.kixi.donations.service.DonationFixtures.jpeg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.donations.config.DonationProperties;
import ao.creativemode.kixi.donations.dto.DonationReviewRequest;
import ao.creativemode.kixi.donations.dto.DonationSubmission;
import ao.creativemode.kixi.donations.model.Donation;
import ao.creativemode.kixi.donations.model.DonationFile;
import ao.creativemode.kixi.donations.repository.DonationFileRepository;
import ao.creativemode.kixi.donations.repository.DonationRepository;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.ocr.client.OcrServiceClient;
import ao.creativemode.kixi.ocr.client.OcrUploadedFile;
import ao.creativemode.kixi.ocr.dto.OcrResponse;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.storage.ImageStorage;
import ao.creativemode.kixi.shared.storage.StoredObject;
import ao.creativemode.kixi.shared.storage.UploadSniffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The donation service (issue #252). The public half is written against the caller, and so are these
 * tests: each rule is a way a script, a mistake or a hostile file gets in if it is missing.
 */
class DonationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");
    private static final long OPENED_A_MINUTE_AGO = NOW.toEpochMilli() - 60_000;

    private DonationRepository donations;
    private DonationFileRepository files;
    private ImageStorage storage;
    private StatementRepository statements;
    private OcrServiceClient ocr;
    private TransactionalOperator tx;
    private DonationProperties properties;
    private DonationService service;
    private final List<String> storedKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        donations = mock(DonationRepository.class);
        files = mock(DonationFileRepository.class);
        storage = mock(ImageStorage.class);
        statements = mock(StatementRepository.class);
        ocr = mock(OcrServiceClient.class);
        tx = mock(TransactionalOperator.class);
        properties = new DonationProperties();
        properties.setMaxFileBytes(200_000);
        properties.setMaxTotalBytes(500_000);
        service = new DonationService(donations, files, storage, statements, ocr, properties,
                Clock.fixed(NOW, ZoneOffset.UTC), tx, "jwt-secret-for-tests");

        when(tx.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        when(donations.existsByReference(anyString())).thenReturn(Mono.just(false));
        when(donations.save(any(Donation.class))).thenAnswer(call -> {
            Donation donation = call.getArgument(0);
            if (donation.getId() == null) {
                donation.setId(77L);
            }
            return Mono.just(donation);
        });
        when(files.save(any(DonationFile.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(storage.put(anyString(), any(), any(byte[].class))).thenAnswer(call -> {
            storedKeys.add(call.getArgument(0));
            return Mono.just(new StoredObject(call.getArgument(0), "/x"));
        });
        when(storage.delete(anyString())).thenReturn(Mono.empty());
    }

    private DonationSubmission.Upload upload(String name, byte[] bytes) {
        return new DonationSubmission.Upload(name, bytes);
    }

    private DonationSubmission submission(List<DonationSubmission.Upload> uploads) {
        return new DonationSubmission("Ana Silva", "ana@example.com", "Instituto de Telecomunicações",
                "Matemática", "12ª Classe", "2024/2025", "Exame", "Prova do 1º trimestre",
                true, null, OPENED_A_MINUTE_AGO, uploads);
    }

    private DonationSubmission submission() {
        return submission(List.of(upload("prova.png", png(20, 20, 9))));
    }

    private DonationSubmission with(DonationSubmission base, java.util.function.UnaryOperator<DonationSubmission> change) {
        return change.apply(base);
    }

    private void assertRefused(DonationSubmission submission, HttpStatus status) {
        StepVerifier.create(service.submit(submission, "hash"))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(status))
                .verify();
        verify(donations, never()).save(any());
    }

    // ── Bots ────────────────────────────────────────────────────────────────

    @Test
    void aFilledHoneypotGetsAConvincingReferenceAndNothingIsStored() {
        DonationSubmission bot = new DonationSubmission("x", "x@x.xx", "Escola", "Disc", null, null, "Exame", null,
                true, "https://spam.example", OPENED_A_MINUTE_AGO, List.of(upload("a.png", png(8, 8, 1))));

        StepVerifier.create(service.submit(bot, "hash"))
                .assertNext(created -> assertThat(created.reference()).matches("KD-[2-9A-Z]{4}-[2-9A-Z]{4}"))
                .verifyComplete();

        verifyNoInteractions(storage, files);
        verify(donations, never()).save(any());
    }

    @Test
    void aFormFilledFasterThanAPersonCanIsTreatedTheSameWay() {
        DonationSubmission fast = with(submission(), s -> new DonationSubmission(s.donorName(), s.donorEmail(),
                s.institutionName(), s.subjectName(), s.classGrade(), s.schoolYear(), s.examType(), s.notes(),
                true, null, NOW.toEpochMilli() - 800, s.files()));

        StepVerifier.create(service.submit(fast, "hash")).expectNextCount(1).verifyComplete();

        verify(donations, never()).save(any());
        verifyNoInteractions(storage);
    }

    @Test
    void aMissingOrFutureStartTimeIsABotToo() {
        for (Long startedAt : new Long[]{null, NOW.toEpochMilli() + 3_600_000}) {
            DonationSubmission odd = with(submission(), s -> new DonationSubmission(s.donorName(), s.donorEmail(),
                    s.institutionName(), s.subjectName(), s.classGrade(), s.schoolYear(), s.examType(), s.notes(),
                    true, null, startedAt, s.files()));
            StepVerifier.create(service.submit(odd, "hash")).expectNextCount(1).verifyComplete();
        }
        verify(donations, never()).save(any());
    }

    @Test
    void aBotIsAnsweredBeforeItsFieldsAreChecked() {
        // Probing the rules with a bad field must not tell a script which ones exist.
        DonationSubmission probe = new DonationSubmission(null, "not-an-email", "x", "x", null, null, "Nada", null,
                false, "bot", OPENED_A_MINUTE_AGO, List.of());

        StepVerifier.create(service.submit(probe, "hash")).expectNextCount(1).verifyComplete();
    }

    // ── Fields ──────────────────────────────────────────────────────────────

    @Test
    void consentIsRequired() {
        assertRefused(with(submission(), s -> new DonationSubmission(s.donorName(), s.donorEmail(), s.institutionName(),
                s.subjectName(), s.classGrade(), s.schoolYear(), s.examType(), s.notes(), false, null,
                OPENED_A_MINUTE_AGO, s.files())), HttpStatus.BAD_REQUEST);
    }

    @Test
    void theFieldsAreHeldToTheirRules() {
        List<DonationSubmission> bad = List.of(
                field(s -> new DonationSubmission(s.donorName(), s.donorEmail(), "<script>alert(1)</script>", s.subjectName(),
                        s.classGrade(), s.schoolYear(), s.examType(), s.notes(), true, null, OPENED_A_MINUTE_AGO, s.files())),
                field(s -> new DonationSubmission(s.donorName(), s.donorEmail(), s.institutionName(), "x",
                        s.classGrade(), s.schoolYear(), s.examType(), s.notes(), true, null, OPENED_A_MINUTE_AGO, s.files())),
                field(s -> new DonationSubmission(s.donorName(), "ana@", s.institutionName(), s.subjectName(),
                        s.classGrade(), s.schoolYear(), s.examType(), s.notes(), true, null, OPENED_A_MINUTE_AGO, s.files())),
                field(s -> new DonationSubmission(s.donorName(), s.donorEmail(), s.institutionName(), s.subjectName(),
                        s.classGrade(), "ontem", s.examType(), s.notes(), true, null, OPENED_A_MINUTE_AGO, s.files())),
                field(s -> new DonationSubmission(s.donorName(), s.donorEmail(), s.institutionName(), s.subjectName(),
                        s.classGrade(), s.schoolYear(), "Teste de choque", s.notes(), true, null, OPENED_A_MINUTE_AGO, s.files())),
                field(s -> new DonationSubmission(s.donorName(), s.donorEmail(), s.institutionName(), s.subjectName(),
                        s.classGrade(), s.schoolYear(), s.examType(), "a".repeat(1001), true, null, OPENED_A_MINUTE_AGO, s.files())),
                field(s -> new DonationSubmission("n".repeat(101), s.donorEmail(), s.institutionName(), s.subjectName(),
                        s.classGrade(), s.schoolYear(), s.examType(), s.notes(), true, null, OPENED_A_MINUTE_AGO, s.files())));
        for (DonationSubmission submission : bad) {
            assertRefused(submission, HttpStatus.BAD_REQUEST);
        }
    }

    private DonationSubmission field(java.util.function.UnaryOperator<DonationSubmission> change) {
        return change.apply(submission());
    }

    @Test
    void theNameAndTheEmailAreOptionalAndBlankMeansAbsent() {
        DonationSubmission anonymous = with(submission(), s -> new DonationSubmission("  ", "", s.institutionName(),
                s.subjectName(), "", "", s.examType(), " ", true, null, OPENED_A_MINUTE_AGO, s.files()));

        StepVerifier.create(service.submit(anonymous, "hash")).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Donation> saved = ArgumentCaptor.forClass(Donation.class);
        verify(donations).save(saved.capture());
        assertThat(saved.getValue().getDonorName()).isNull();
        assertThat(saved.getValue().getDonorEmail()).isNull();
        assertThat(saved.getValue().getNotes()).isNull();
    }

    // ── Files ───────────────────────────────────────────────────────────────

    @Test
    void aDonationNeedsAFileAndHasACap() {
        assertRefused(submission(List.of()), HttpStatus.BAD_REQUEST);
        List<DonationSubmission.Upload> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(upload("p" + i + ".png", png(10 + i, 10, i)));
        }
        assertRefused(submission(eleven), HttpStatus.BAD_REQUEST);
    }

    @Test
    void anOversizedFileIs413AndTheTotalIsCappedToo() {
        assertRefused(submission(List.of(upload("big.png", new byte[300_000]))), HttpStatus.PAYLOAD_TOO_LARGE);

        byte[] a = png(400, 400, 5);
        byte[] big = new byte[190_000];
        System.arraycopy(a, 0, big, 0, a.length);
        assertRefused(submission(List.of(upload("1.png", big), upload("2.png", big.clone()), upload("3.png", big.clone()))),
                HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void whatTheFileIsComesFromItsBytesNotItsNameOrItsClaimedType() {
        assertRefused(submission(List.of(upload("totally-a-photo.png", executable()))), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertRefused(submission(List.of(upload("p.pdf", "#!/bin/sh\nrm -rf /\n".getBytes()))), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        verifyNoInteractions(storage);
    }

    @Test
    void aPdfWithScriptIsRefusedBeforeAnythingIsStored() {
        assertRefused(submission(List.of(upload("p.pdf", pdf(1, "<< /S /JavaScript /JS (x) >>")))),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        verifyNoInteractions(storage);
    }

    @Test
    void theSameFileTwiceInOneDonationIsOneFile() {
        byte[] photo = png(30, 30, 40);

        StepVerifier.create(service.submit(submission(List.of(upload("a.png", photo), upload("b.png", photo.clone()))), "hash"))
                .expectNextCount(1).verifyComplete();

        verify(storage, times(1)).put(anyString(), any(), any(byte[].class));
        verify(files, times(1)).save(any(DonationFile.class));
    }

    // ── What is stored ──────────────────────────────────────────────────────

    @Test
    void theStoredKeyIsRandomAndNeverCarriesTheOriginalName() {
        StepVerifier.create(service.submit(
                submission(List.of(upload("..\\..\\etc\\passwd<>.png", png(12, 12, 77)))), "hash"))
                .expectNextCount(1).verifyComplete();

        assertThat(storedKeys).singleElement().satisfies(key -> {
            assertThat(key).matches("donations/[0-9a-f-]{36}");
            assertThat(key).doesNotContain("passwd");
        });
        ArgumentCaptor<DonationFile> saved = ArgumentCaptor.forClass(DonationFile.class);
        verify(files).save(saved.capture());
        assertThat(saved.getValue().getOriginalName()).doesNotContain("..").doesNotContain("\\").doesNotContain("<");
        assertThat(saved.getValue().getContentType()).as("what the bytes are").isEqualTo("image/png");
        assertThat(saved.getValue().getSha256()).hasSize(64);
    }

    @Test
    void aPhotoWithoutAnExtensionIsStoredWithTheOneItsBytesDecide() {
        StepVerifier.create(service.submit(
                submission(List.of(upload("IMG_20250116", jpeg(24, 24, 5)))), "hash"))
                .expectNextCount(1).verifyComplete();

        ArgumentCaptor<DonationFile> saved = ArgumentCaptor.forClass(DonationFile.class);
        verify(files).save(saved.capture());
        assertThat(saved.getValue().getOriginalName()).isEqualTo("IMG_20250116.jpg");
        assertThat(saved.getValue().getContentType()).isEqualTo("image/jpeg");
    }

    @Test
    void aNameWithTheWrongExtensionIsRewrittenToTheRealOne() {
        StepVerifier.create(service.submit(
                submission(List.of(upload("prova.png", jpeg(24, 24, 5)))), "hash"))
                .expectNextCount(1).verifyComplete();

        ArgumentCaptor<DonationFile> saved = ArgumentCaptor.forClass(DonationFile.class);
        verify(files).save(saved.capture());
        assertThat(saved.getValue().getOriginalName()).isEqualTo("prova.jpg");
    }

    @Test
    void safeNameKeepsTheRightExtensionReplacesTheWrongAndAddsTheMissing() {
        assertThat(DonationService.safeName("IMG_20250116", UploadSniffer.Kind.JPEG)).isEqualTo("IMG_20250116.jpg");
        assertThat(DonationService.safeName("prova.png", UploadSniffer.Kind.JPEG)).isEqualTo("prova.jpg");
        assertThat(DonationService.safeName("prova.PNG", UploadSniffer.Kind.PNG)).isEqualTo("prova.PNG");
        assertThat(DonationService.safeName("scan.jpeg", UploadSniffer.Kind.JPEG)).isEqualTo("scan.jpeg");
        assertThat(DonationService.safeName("notes.v1", UploadSniffer.Kind.JPEG)).isEqualTo("notes.v1.jpg");
        assertThat(DonationService.safeName("", UploadSniffer.Kind.PDF)).isEqualTo("ficheiro.pdf");
    }

    @Test
    void aDonationIsPendingWithItsConsentDatedAndVersioned() {
        StepVerifier.create(service.submit(submission(), "the-hash")).assertNext(created ->
                assertThat(created.reference()).matches("KD-[2-9A-Z]{4}-[2-9A-Z]{4}")).verifyComplete();

        ArgumentCaptor<Donation> saved = ArgumentCaptor.forClass(Donation.class);
        verify(donations).save(saved.capture());
        Donation donation = saved.getValue();
        assertThat(donation.getStatus()).isEqualTo("pending");
        assertThat(donation.getClientHash()).isEqualTo("the-hash");
        assertThat(donation.getConsentVersion()).isEqualTo(properties.getConsentVersion());
        assertThat(donation.getConsentAt()).isEqualTo(java.time.LocalDateTime.of(2026, 10, 15, 10, 0));
        assertThat(donation.getDonorEmail()).isEqualTo("ana@example.com");
    }

    @Test
    void ifTheDatabaseFailsTheFilesAlreadyStoredAreRemoved() {
        when(donations.save(any(Donation.class))).thenReturn(Mono.error(new IllegalStateException("db down")));

        StepVerifier.create(service.submit(
                        submission(List.of(upload("a.png", png(21, 21, 1)), upload("b.png", png(22, 22, 2)))), "hash"))
                .expectError(IllegalStateException.class).verify();

        assertThat(storedKeys).hasSize(2);
        for (String key : storedKeys) {
            verify(storage).delete(key);
        }
    }

    @Test
    void aReferenceCollisionDrawsAnotherOne() {
        when(donations.existsByReference(anyString())).thenReturn(Mono.just(true), Mono.just(false));

        StepVerifier.create(service.submit(submission(), "hash")).expectNextCount(1).verifyComplete();

        verify(donations, times(2)).existsByReference(anyString());
    }

    // ── The address ─────────────────────────────────────────────────────────

    @Test
    void theClientHashIsStableUnreadableAndDependsOnThePepper() {
        String hash = service.clientHash("41.223.10.9");

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}").doesNotContain("41.223");
        assertThat(service.clientHash("41.223.10.9")).isEqualTo(hash);
        assertThat(service.clientHash("41.223.10.10")).isNotEqualTo(hash);

        DonationProperties other = new DonationProperties();
        other.setPepper("another-pepper");
        DonationService peppered = new DonationService(donations, files, storage, statements, ocr, other,
                Clock.fixed(NOW, ZoneOffset.UTC), tx, "jwt-secret-for-tests");
        assertThat(peppered.clientHash("41.223.10.9")).isNotEqualTo(hash);
    }

    // ── Administrator ───────────────────────────────────────────────────────

    private Donation donation(String status) {
        return Donation.builder().id(5L).reference("KD-AAAA-BBBB").status(status).institutionName("Escola")
                .subjectName("Matemática").examType("Exame").clientHash("h").build();
    }

    @Test
    void reviewAcceptsOnlyApprovedOrRejectedAndRecordsWhoAndWhen() {
        when(donations.findById(5L)).thenReturn(Mono.just(donation("pending")));
        when(files.findAllByDonation(5L)).thenReturn(Flux.empty());

        StepVerifier.create(service.review(5L, 1L, new DonationReviewRequest("pending", null)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();

        StepVerifier.create(service.review(5L, 1L, new DonationReviewRequest("Rejected", "Foto ilegível")))
                .assertNext(detail -> {
                    assertThat(detail.status()).isEqualTo("rejected");
                    assertThat(detail.reviewNote()).isEqualTo("Foto ilegível");
                    assertThat(detail.reviewedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void aConvertedDonationIsNotReopenedByAReview() {
        when(donations.findById(5L)).thenReturn(Mono.just(donation("converted")));

        StepVerifier.create(service.review(5L, 1L, new DonationReviewRequest("rejected", null)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .verify();
        verify(donations, never()).save(any());
    }

    @Test
    void linkingNeedsARealStatementAndAnAcceptableDonation() {
        when(files.findAllByDonation(5L)).thenReturn(Flux.empty());
        when(donations.findById(5L)).thenReturn(Mono.just(donation("rejected")));
        StepVerifier.create(service.linkStatement(5L, 1L, 9L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .verify();

        when(donations.findById(5L)).thenReturn(Mono.just(donation("approved")));
        when(statements.findByIdAndDeletedAtIsNull(9L)).thenReturn(Mono.empty());
        StepVerifier.create(service.linkStatement(5L, 1L, 9L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        when(statements.findByIdAndDeletedAtIsNull(9L)).thenReturn(Mono.just(new Statement()));
        StepVerifier.create(service.linkStatement(5L, 1L, 9L))
                .assertNext(detail -> {
                    assertThat(detail.status()).isEqualTo("converted");
                    assertThat(detail.statementId()).isEqualTo(9L);
                })
                .verifyComplete();
    }

    @Test
    void linkingTwiceToTheSameStatementIsFineAndToAnotherIsAConflict() {
        Donation converted = donation("converted");
        converted.setStatementId(9L);
        when(donations.findById(5L)).thenReturn(Mono.just(converted));
        when(files.findAllByDonation(5L)).thenReturn(Flux.empty());

        StepVerifier.create(service.linkStatement(5L, 1L, 9L)).expectNextCount(1).verifyComplete();
        StepVerifier.create(service.linkStatement(5L, 1L, 10L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .verify();
    }

    @Test
    void theListIsValidatedBeforeItAsksTheDatabase() {
        for (StepVerifier.FirstStep<?> step : List.of(
                StepVerifier.create(service.list("weird", 1, 20)),
                StepVerifier.create(service.list(null, 0, 20)),
                StepVerifier.create(service.list(null, 1, 500)))) {
            step.expectError(ApiException.class).verify();
        }
        verifyNoInteractions(donations);
    }

    @Test
    void extractReadsTheStoredBytesAndHandsThemToTheOcrAsTheyAre() {
        byte[] bytes = png(40, 40, 3);
        when(donations.findById(5L)).thenReturn(Mono.just(donation("pending")));
        when(files.findAllByDonation(5L)).thenReturn(Flux.just(DonationFile.builder().id(1L).donationId(5L)
                .storageKey("donations/k").originalName("prova.png").contentType("image/png").sizeBytes(1L).build()));
        when(storage.get("donations/k")).thenReturn(Mono.just(bytes));
        OcrResponse extracted = new OcrResponse("success", "r", 1, 0.9, null, null, List.of(), List.of(), List.of(), List.of(), null);
        when(ocr.extractTextFromUploadedFiles(any())).thenReturn(Mono.just(extracted));

        StepVerifier.create(service.extract(5L)).expectNext(extracted).verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OcrUploadedFile>> sent = ArgumentCaptor.forClass(List.class);
        verify(ocr).extractTextFromUploadedFiles(sent.capture());
        assertThat(sent.getValue()).singleElement().satisfies(file -> {
            assertThat(file.filename()).isEqualTo("prova.png");
            assertThat(file.content()).isEqualTo(bytes);
        });
    }

    @Test
    void aDonationWithoutFilesHasNothingToExtract() {
        when(donations.findById(5L)).thenReturn(Mono.just(donation("pending")));
        when(files.findAllByDonation(5L)).thenReturn(Flux.empty());

        StepVerifier.create(service.extract(5L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .verify();
        verify(ocr, never()).extractTextFromUploadedFiles(any());
    }

    @Test
    void aFileOfAnotherDonationIsNotFound() {
        when(donations.findById(5L)).thenReturn(Mono.just(donation("pending")));
        when(files.findByIdAndDonation(anyLong(), eq(5L))).thenReturn(Mono.empty());

        StepVerifier.create(service.file(5L, 99L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }
}
