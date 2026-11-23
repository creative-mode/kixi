package ao.creativemode.kixi.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.ocr.client.OcrServiceClient;
import ao.creativemode.kixi.ocr.client.OcrServiceClient.OcrClientException;
import ao.creativemode.kixi.ocr.client.OcrServiceClient.OcrServerException;
import ao.creativemode.kixi.shared.exception.ApiException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * V2-1: um ficheiro inválido ou ilegível é um erro do utilizador, não do sistema: regista-se em WARN e sem stack; só as falhas
 * reais do serviço OCR (5xx, rede, tempo esgotado) ficam em ERROR, que é o que os alertas de produção vigiam.
 */
class OcrFailureLogTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger("test-ocr-failure");
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private ILoggingEvent only() {
        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    @Test
    void aRejectedFileIsAWarningWithoutAStack() {
        OcrFailure.log(logger, "OCR request failed", new OcrClientException("OCR request failed: bad file", 400));

        assertThat(only().getLevel()).isEqualTo(Level.WARN);
        assertThat(only().getThrowableProxy()).isNull();
        assertThat(only().getFormattedMessage()).contains("OCR request failed").contains("OcrClientException");
    }

    @Test
    void anUnreadableResultAndAContentErrorAreWarnings() {
        OcrFailure.log(logger, "job", new OcrFailure.Unreadable(OcrProblem.NO_TEXT));
        OcrFailure.log(logger, "request", new OcrServerException("OCR service error: {\"status\": \"error\"}", 500, true));
        OcrFailure.log(logger, "statement", ApiException.badRequest("OCR extraction failed"));

        assertThat(appender.list).extracting(ILoggingEvent::getLevel).containsOnly(Level.WARN);
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    void realServiceFailuresStayErrors() {
        OcrFailure.log(logger, "server", new OcrServerException("OCR service error: boom", 503));
        OcrFailure.log(logger, "timeout", new java.util.concurrent.TimeoutException("slow"));
        OcrFailure.log(logger, "other", new IllegalStateException("OCR service returned no event stream"));

        assertThat(appender.list).extracting(ILoggingEvent::getLevel).containsOnly(Level.ERROR);
    }

    @Test
    void theClientLogsARejectedFileAsAWarningNotAnError() throws IOException {
        Logger clientLogger = (Logger) LoggerFactory.getLogger(OcrServiceClient.class);
        ListAppender<ILoggingEvent> clientAppender = new ListAppender<>();
        clientAppender.start();
        clientLogger.addAppender(clientAppender);
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                    .setBody("{\"detail\":\"Unsupported or invalid OCR file\"}"));
            OcrServiceClient client = new OcrServiceClient(server.url("/").toString(), 5000, 0);

            try {
                client.extractTextFromBytes(new byte[] {1}, "exam.png").block();
            } catch (OcrClientException expected) {
                // o erro sobe como sempre; o que importa é o nível do registo
            }

            assertThat(clientAppender.list).extracting(ILoggingEvent::getLevel).doesNotContain(Level.ERROR).contains(Level.WARN);
        } finally {
            clientLogger.detachAppender(clientAppender);
        }
    }
}
