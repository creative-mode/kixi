package ao.creativemode.kixi.ocr;

import ao.creativemode.kixi.ocr.client.OcrServiceClient.OcrClientException;
import ao.creativemode.kixi.ocr.client.OcrServiceClient.OcrServerException;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.slf4j.Logger;

/**
 * Quando uma falha do OCR é do utilizador e não do sistema. Um ficheiro inválido, corrompido ou em branco não é um incidente: regista-se
 * em WARN, sem stack, e deixa o nível ERROR (o que os alertas de produção vigiam) para as falhas reais do serviço OCR, da rede e dos
 * tempos esgotados.
 */
public final class OcrFailure {

    private OcrFailure() {
    }

    /** O serviço OCR leu o ficheiro mas não conseguiu ler nada dele (imagem em branco, ilegível). */
    public static class Unreadable extends RuntimeException {
        public Unreadable(String message) {
            super(message);
        }
    }

    /** Um erro de entrada do utilizador: o ficheiro foi recusado (4xx), o serviço não leu texto, ou o Kixi já o devolveu como 4xx. */
    public static boolean isUserError(Throwable error) {
        if (error instanceof OcrClientException || error instanceof Unreadable) return true;
        if (error instanceof OcrServerException server) return server.isContentError();
        return error instanceof ApiException api && api.getStatus() != null && api.getStatus().is4xxClientError();
    }

    /** WARN (sem stack) para um erro do utilizador, ERROR para uma falha real; só o tipo e a mensagem curta, nunca o corpo do ficheiro. */
    public static void log(Logger log, String what, Throwable error) {
        if (isUserError(error)) {
            log.warn("{} (ficheiro recusado ou ilegível): type={}", what, error.getClass().getSimpleName());
        } else {
            log.error("{}: type={}", what, error.getClass().getSimpleName());
        }
    }
}
