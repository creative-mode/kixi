package ao.creativemode.kixi.ocr;

import ao.creativemode.kixi.ocr.client.OcrServiceClient.OcrClientException;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Os erros conhecidos da leitura OCR, cada um com um código estável (V2-4). O frontend mapeia as mensagens em inglês conhecidas
 * («Unsupported or invalid OCR file», «OCR processing failed», …) para português (kixi-frontend#841): por isso a mensagem existente
 * ({@code detail} do erro padrão, {@code error} do estado do trabalho) <b>não muda</b>, e o código vai à parte, em
 * {@code properties.code} e em {@code errorCode} do estado do trabalho. O texto em português é do frontend, a partir do código.
 */
public enum OcrProblem {

    /** O Kixi não reconhece o ficheiro como imagem ou PDF (ou está truncado). */
    FILE_INVALID(HttpStatus.BAD_REQUEST, "OCR_FILE_INVALID", "Unsupported or invalid OCR file"),
    /** Passa o limite de píxeis. */
    IMAGE_TOO_LARGE(HttpStatus.BAD_REQUEST, "IMAGE_TOO_LARGE", "OCR image exceeds the maximum pixel count"),
    /** O serviço OCR recusou o ficheiro (4xx). */
    FILE_REJECTED(HttpStatus.BAD_REQUEST, "OCR_FILE_REJECTED",
        "The OCR request was rejected. Please verify the uploaded file and try again."),
    /** A imagem abriu mas não tem texto: em branco. */
    NO_TEXT(HttpStatus.BAD_REQUEST, "OCR_NO_TEXT", "OCR extraction failed"),
    /** A imagem abriu mas está tão má que não se lê. */
    UNREADABLE(HttpStatus.BAD_REQUEST, "OCR_UNREADABLE", "OCR extraction failed"),
    /** O serviço OCR não conseguiu abrir o ficheiro. */
    FILE_CORRUPT(HttpStatus.BAD_REQUEST, "OCR_FILE_CORRUPT", "OCR extraction failed"),
    /** Falha genérica na leitura. */
    PROCESSING_FAILED(HttpStatus.BAD_REQUEST, "OCR_PROCESSING_FAILED", "OCR extraction failed"),
    /** O serviço OCR está em baixo. */
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "OCR_UNAVAILABLE",
        "The OCR service is temporarily unavailable. Please try again later.");

    /** A mensagem que o estado de um trabalho falhado sempre teve, qualquer que seja a causa. */
    public static final String JOB_FAILED_MESSAGE = "OCR processing failed";

    private final HttpStatus status;
    private final String code;
    private final String message;

    OcrProblem(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** A mensagem em inglês de sempre (o que o frontend já sabe mapear). */
    public String message() {
        return message;
    }

    /** O erro padrão do projecto, com a mensagem de sempre e o código estável à parte. */
    public ApiException exception() {
        return exception(message);
    }

    /** O mesmo, com a mensagem exacta que aquele ponto já devolvia. */
    public ApiException exception(String legacyMessage) {
        return new ApiException(status, status.getReasonPhrase(), legacyMessage, code);
    }

    /**
     * O que o serviço OCR disse de um resultado sem texto ({@code errorMessage}): «Imagem ilegível (…)», «No text could be extracted…»,
     * «Failed to load image». Qualquer outra coisa, ou nada, é a falha genérica.
     */
    public static OcrProblem fromServiceMessage(String serviceMessage) {
        if (serviceMessage == null) return PROCESSING_FAILED;
        String text = serviceMessage.toLowerCase(java.util.Locale.ROOT);
        if (text.contains("ilegível") || text.contains("ilegivel")) return UNREADABLE;
        if (text.contains("no text could be extracted")) return NO_TEXT;
        if (text.contains("failed to load image")) return FILE_CORRUPT;
        return PROCESSING_FAILED;
    }

    /** O problema que um trabalho de importação falhado leva como código. */
    public static OcrProblem forJobFailure(Throwable error) {
        if (error instanceof OcrFailure.Unreadable unreadable) return unreadable.problem();
        if (error instanceof OcrClientException) return FILE_REJECTED;
        return PROCESSING_FAILED;
    }
}
