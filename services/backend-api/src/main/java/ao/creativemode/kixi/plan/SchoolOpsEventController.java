package ao.creativemode.kixi.plan;

import ao.creativemode.kixi.shared.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * é inofensivo (só atualiza o estado da pauta do plano).
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
        // uma pauta homologada não volta atrás por um evento de cálculo atrasado
        return db.sql("""
                UPDATE term_plans SET pauta_status = :s, pauta_event_at = CURRENT_TIMESTAMP
                WHERE id = :p AND (pauta_status IS DISTINCT FROM 'HOMOLOGADA' OR :s IN ('HOMOLOGADA', 'RECTIFICADA', 'ANULADA'))
                """).bind("s", status).bind("p", planId).fetch().rowsUpdated().then();
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
