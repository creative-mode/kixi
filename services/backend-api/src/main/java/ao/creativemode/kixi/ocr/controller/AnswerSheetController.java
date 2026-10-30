package ao.creativemode.kixi.ocr.controller;

import ao.creativemode.kixi.exams.service.StatementService;
import ao.creativemode.kixi.exams.service.StatementWriteAccessService;
import ao.creativemode.kixi.ocr.client.OcrServiceClient;
import ao.creativemode.kixi.ocr.service.AnswerSheetStructure;
import ao.creativemode.kixi.ocr.service.OcrUploadBuffer;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.ApiAbuseRateLimiter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Lê as folhas de resposta dos alunos de uma prova em papel (kixi#499). Quem chama diz só de que enunciado são as
 * folhas; a estrutura (questões, tipos, letras) sai do enunciado. A resposta é o contrato v1 do OCR
 * ({@code sheets}, {@code warnings}, confiança por campo): o que foi lido, nunca o que vale, e nunca uma nota.
 */
@RestController
@RequestMapping("/api/v1/ocr/answer-sheets")
public class AnswerSheetController {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".pdf", ".webp", ".bmp", ".tiff", ".tif");
    private static final int MAX_FILES = 10;

    private final OcrServiceClient ocr;
    private final StatementService statements;
    private final StatementWriteAccessService writeAccess;
    private final CurrentAccountService currentAccount;
    private final ApiAbuseRateLimiter abuseRateLimiter;
    private final ObjectMapper mapper;

    public AnswerSheetController(OcrServiceClient ocr, StatementService statements, StatementWriteAccessService writeAccess,
            CurrentAccountService currentAccount, ApiAbuseRateLimiter abuseRateLimiter, ObjectMapper mapper) {
        this.ocr = ocr;
        this.statements = statements;
        this.writeAccess = writeAccess;
        this.currentAccount = currentAccount;
        this.abuseRateLimiter = abuseRateLimiter;
        this.mapper = mapper;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<JsonNode>> extract(
            @RequestPart("statementId") String statementId,
            @RequestPart("files") Flux<FilePart> files) {
        long id;
        try {
            id = Long.parseLong(statementId.trim());
        } catch (NumberFormatException error) {
            return Mono.error(ApiException.badRequest("statementId must be a number"));
        }
        return currentAccount.requiredAccountId()
                .flatMap(account -> abuseRateLimiter.checkUpload(account).thenReturn(account))
                .zipWith(currentAccount.hasAnyRole("ADMIN"))
                .flatMap(caller -> writeAccess.requireCanWrite(id, caller.getT1(), caller.getT2()))
                .then(Mono.defer(() -> files.collectList()))
                .flatMap(list -> {
                    if (list.isEmpty()) {
                        return Mono.error(ApiException.badRequest("At least one file is required"));
                    }
                    if (list.size() > MAX_FILES) {
                        return Mono.error(ApiException.badRequest("Maximum " + MAX_FILES + " files allowed per request"));
                    }
                    for (FilePart file : list) {
                        String name = file.filename() == null ? "" : file.filename().toLowerCase();
                        if (ALLOWED_EXTENSIONS.stream().noneMatch(name::endsWith)) {
                            return Mono.error(ApiException.badRequest("Unsupported file type"));
                        }
                    }
                    return statements.findByIdWithQuestions(id)
                            .flatMap(statement -> OcrUploadBuffer.buffer(list)
                                    .flatMap(buffered -> ocr.extractAnswerSheets(
                                            buffered, AnswerSheetStructure.of(statement, mapper).toString())));
                })
                .map(ResponseEntity::ok);
    }
}
