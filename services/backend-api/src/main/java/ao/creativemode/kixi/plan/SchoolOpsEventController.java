package ao.creativemode.kixi.plan;

import ao.creativemode.kixi.shared.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Receptor dos eventos assinados do motor SchoolOps (Standard Webhooks, kixi#525). A assinatura <b>é</b> a autenticação:
 * sem segredo configurado, ou com assinatura inválida ou fora de 5 minutos, responde 401 e não guarda nada. Repetir um evento
 * é inofensivo (só atualiza o estado da pauta do plano) e um evento fora de ordem não faz o estado recuar (ver
 * {@code applyIfForward}).
 */
@RestController
@RequestMapping("/api/v1/integrations/schoolops")
public class SchoolOpsEventController {

    private static final Logger log = LoggerFactory.getLogger(SchoolOpsEventController.class);

    private final SchoolOpsProperties properties;
    private final DatabaseClient db;
    private final ObjectMapper mapper;

    public SchoolOpsEventController(SchoolOpsProperties properties, DatabaseClient db, ObjectMapper mapper) {
        this.properties = properties;
        this.db = db;
        this.mapper = mapper;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> receive(@RequestHeader(value = "webhook-id", required = false) String id,
            @RequestHeader(value = "webhook-timestamp", required = false) String timestamp,
            @RequestHeader(value = "webhook-signature", required = false) String signature, @RequestBody String body) {
        if (!properties.isEventsConfigured() || !valid(id, timestamp, signature, body, System.currentTimeMillis() / 1000)) {
            return Mono.error(ApiException.unauthorized("Invalid event signature"));
        }
        JsonNode event;
        try {
            event = mapper.readTree(body);
        } catch (Exception e) {
            return Mono.error(ApiException.badRequest("Malformed event"));
        }
        String type = event.path("type").asText("");
        String pautaRef = event.path("data").path("pautaRef").asText("");
        String status = type.endsWith("homologada.v1") ? "HOMOLOGADA" : type.endsWith("anulada.v1") ? "ANULADA"
                : type.endsWith("rectificada.v1") ? "RECTIFICADA" : type.endsWith("componente-calculado.v1") ? "EM_CURSO" : null;
        if (status == null || !pautaRef.startsWith("kixi-plano-")) {
            return Mono.empty();
        }
        long planId;
        try {
            planId = Long.parseLong(pautaRef.substring("kixi-plano-".length()));
        } catch (NumberFormatException e) {
            return Mono.empty();
        }
        log.info("[schoolops] evento {} da pauta {} (plano {})", type, event.path("subject").asText(), planId);
        LocalDateTime time = eventTime(event.path("time").asText(""));
        return applyIfForward(planId, status, time, type);
    }

    /**
     * O estado da pauta do plano só avança (V2-13). Os eventos chegam por entrega repetida e podem chegar fora de ordem, por isso
     * cada um só se aplica se o estado não recuar:
     * <pre>
     *   estado actual   componente-calculado  homologada   rectificada   anulada
     *   (nenhum)        EM_CURSO              HOMOLOGADA   RECTIFICADA   ANULADA
     *   EM_CURSO        EM_CURSO (*)          HOMOLOGADA   RECTIFICADA   ANULADA
     *   HOMOLOGADA      ignora                HOMOLOGADA (*) RECTIFICADA ignora (**)
     *   RECTIFICADA     ignora                ignora       RECTIFICADA (*) ignora (**)
     *   ANULADA         ignora (terminal)     ignora       ignora        ANULADA (*)
     * </pre>
     * (*) mesmo estado: aplica-se só se o {@code time} do evento não for anterior ao do já aplicado (uma homologação mais antiga
     * não sobrescreve uma mais recente); sem {@code time} aplica-se. (**) o motor recusa anular uma pauta já homologada (409),
     * por isso um {@code anulada} nesse ponto é inconsistente: ignora-se e avisa-se. Um evento ignorado responde 204 como os outros
     * (o motor não deve repetir), sem alterar nada. A decisão é um só UPDATE condicional, atómico mesmo com entregas em paralelo.
     */
    private Mono<Void> applyIfForward(long planId, String status, LocalDateTime time, String type) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                UPDATE term_plans SET pauta_status = :s, pauta_event_at = CURRENT_TIMESTAMP, pauta_event_time = COALESCE(:t, pauta_event_time)
                WHERE id = :p AND (
                    pauta_status IS NULL
                    OR (pauta_status <> 'ANULADA' AND (
                        (:s = 'ANULADA' AND pauta_status = 'EM_CURSO')
                        OR (:s <> 'ANULADA' AND (CASE :s WHEN 'EM_CURSO' THEN 1 WHEN 'HOMOLOGADA' THEN 2 WHEN 'RECTIFICADA' THEN 3 END)
                                              > (CASE pauta_status WHEN 'EM_CURSO' THEN 1 WHEN 'HOMOLOGADA' THEN 2 WHEN 'RECTIFICADA' THEN 3 ELSE 0 END))
                        OR (:s = pauta_status AND (CAST(:t AS TIMESTAMP) IS NULL OR pauta_event_time IS NULL OR :t >= pauta_event_time))
                    ))
                )
                """).bind("s", status).bind("p", planId);
        spec = time == null ? spec.bindNull("t", LocalDateTime.class) : spec.bind("t", time);
        return spec.fetch().rowsUpdated().flatMap(updated -> {
            if (updated > 0) return Mono.<Void>empty();
            return db.sql("SELECT pauta_status FROM term_plans WHERE id = :p").bind("p", planId).fetch().one()
                    .map(row -> String.valueOf(row.get("pauta_status")))
                    .doOnNext(current -> {
                        if ("ANULADA".equals(status) && ("HOMOLOGADA".equals(current) || "RECTIFICADA".equals(current))) {
                            log.warn("[schoolops] plano {}: evento {} ignorado, a pauta já está {} e o motor não anula uma pauta homologada",
                                    planId, type, current);
                        } else {
                            log.debug("[schoolops] plano {}: evento {} ignorado, faria o estado recuar ({} -> {})", planId, type, current, status);
                        }
                    }).then();
        });
    }

    /** O {@code time} do CloudEvent em UTC (a coluna é um TIMESTAMP em UTC); ausente ou ilegível é {@code null}. */
    private static LocalDateTime eventTime(String text) {
        if (text.isBlank()) return null;
        try {
            return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** HMAC-SHA256 de {@code id.timestamp.corpo} com a chave do segredo {@code whsec_<base64>}; aceita várias assinaturas v1. */
    boolean valid(String id, String timestamp, String signature, String body, long nowSeconds) {
        if (id == null || timestamp == null || signature == null) return false;
        try {
            if (Math.abs(nowSeconds - Long.parseLong(timestamp)) > 300) return false;
            String secret = properties.getEventsSecret().replaceFirst("^whsec_", "");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256"));
            byte[] expected = mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            for (String part : signature.split(" ")) {
                String[] pair = part.split(",", 2);
                if (pair.length == 2 && pair[0].equals("v1") && MessageDigest.isEqual(expected, Base64.getDecoder().decode(pair[1]))) return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }
}
