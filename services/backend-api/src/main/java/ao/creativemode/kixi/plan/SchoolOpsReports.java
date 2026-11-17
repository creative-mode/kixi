package ao.creativemode.kixi.plan;

import ao.creativemode.kixi.plan.TermPlanService.Access;
import ao.creativemode.kixi.plan.TermPlanService.Assessment;
import ao.creativemode.kixi.plan.TermPlanService.Plan;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Exportação da pauta em PDF (kixi#622). O Kixi não gera nem guarda PDF: pede ao motor SchoolOps uma ligação nova e curta
 * para um PDF que ele já gerou, descarrega-o e entrega os bytes a quem pediu. A pauta da disciplina e o resultado de uma
 * prova têm PDF guardado no motor; a ligação é pedida a cada exportação e não fica guardada aqui.
 *
 * <p>Quem pode exportar é quem pode ler o plano: o professor da turma e disciplina, o coordenador da instituição e o
 * administrador ({@link TermPlanService#get}). O aluno não chega aqui — a regra de segurança das rotas não o deixa entrar.
 */
@Component
public class SchoolOpsReports {

    private static final Logger log = LoggerFactory.getLogger(SchoolOpsReports.class);
    private static final Duration RESPONSE = Duration.ofSeconds(15);
    private static final List<String> LOCAL = List.of("localhost", "127.0.0.1");
    /**
     * Tecto do PDF em memória. O codec do WebClient traz 256 KB por omissão: uma pauta da disciplina com uma turma grande
     * passa disso e o motor respondia 200 com o Kixi a rebentar com 500. O {@code OcrServiceClient} e o {@code GroqClient}
     * já fixam o seu por isto mesmo. 5 MB cobre uma pauta de uma centena de alunos em várias disciplinas.
     */
    private static final int MAX_PDF_BYTES = 5 * 1024 * 1024;

    private final SchoolOpsProperties properties;
    private final TermPlanService plans;
    private final AuditoriaRelatorios auditoria;
    private final WebClient.Builder webClients;

    public SchoolOpsReports(SchoolOpsProperties properties, TermPlanService plans, AuditoriaRelatorios auditoria,
            WebClient.Builder webClients) {
        this.properties = properties;
        this.plans = plans;
        this.auditoria = auditoria;
        this.webClients = webClients;
    }

    /** A pauta da disciplina (mini-pauta homologada): o último PDF que o motor gerou para a pauta do plano. */
    public Mono<byte[]> miniPauta(Long planId) {
        return plans.access().flatMap(who -> plans.get(who, planId)
                .flatMap(plan -> exportar(who, plan, "MINI_PAUTA", null, null)));
    }

    /** O resultado de uma prova da pauta: a prova é a avaliação do plano, e o motor conhece-a pelo enunciado. */
    public Mono<byte[]> resultado(Long planId, Long assessmentId) {
        return plans.access().flatMap(who -> plans.get(who, planId).flatMap(plan -> {
            Assessment assessment = plan.assessments().stream()
                    .filter(a -> a.id().equals(assessmentId)).findFirst()
                    .orElseThrow(() -> ApiException.notFound("Assessment not found in this plan: " + assessmentId));
            if (assessment.statementId() == null) {
                return Mono.error(ApiException.conflict("This assessment has no statement yet, so there is no result to export."));
            }
            return exportar(who, plan, "RESULTADO_PROVA", String.valueOf(assessment.statementId()), assessmentId);
        }));
    }

    private Mono<byte[]> exportar(Access who, Plan plan, String kind, String provaId, Long assessmentId) {
        if (!properties.isConfigured()) {
            return Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Reports are unavailable: the school ops motor is not configured."));
        }
        String pautaId = plan.pautaRef();
        if (pautaId == null || pautaId.isBlank()) {
            return Mono.error(ApiException.conflict("This plan has no pauta at the motor yet."));
        }
        Map<String, Object> pedido = new LinkedHashMap<>();
        pedido.put("pautaId", pautaId);
        pedido.put("tipo", kind);
        if (provaId != null) {
            pedido.put("provaId", provaId);
        }
        return ligacao(pedido).flatMap(this::descarregar)
                // Sem auditoria não há exportação: se o registo falhar, o PDF não sai e o erro é 503,
                // não um 500 de base de dados a chegar ao professor.
                .flatMap(pdf -> auditoria.registar(who.accountId(), plan.id(), kind, assessmentId)
                        .onErrorMap(erro -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                                "Não foi possível registar a exportação; tente de novo."))
                        .thenReturn(pdf));
    }

    /** Um cliente com o tecto do PDF (o codec traz 256 KB) para os pedidos e para o download. */
    private WebClient.Builder cliente() {
        return webClients.clone().codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_PDF_BYTES));
    }

    /** Pede ao motor uma ligação nova e curta, e devolve o endereço assinado. */
    private Mono<String> ligacao(Map<String, Object> pedido) {
        WebClient http = cliente().baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getToken())
                .build();
        return http.post().uri("/v1/relatorios").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .bodyValue(pedido).retrieve().bodyToMono(Map.class)
                .timeout(RESPONSE)
                .onErrorMap(this::erroDoPedido)
                .map(resposta -> resposta.get("url") == null ? null : String.valueOf(resposta.get("url")))
                .filter(url -> url != null && !url.isBlank())
                .switchIfEmpty(Mono.error(new ApiException(HttpStatus.BAD_GATEWAY, "The motor returned no report link.")));
    }

    /** Abre a ligação assinada e devolve o PDF. O motor recusa-a depois do prazo (403): isso é um 502 para o cliente. */
    private Mono<byte[]> descarregar(String url) {
        URI uri = validar(url);
        return cliente().build().get().uri(uri).accept(MediaType.APPLICATION_PDF).retrieve().toEntity(byte[].class)
                .timeout(RESPONSE)
                .onErrorMap(this::erroDaLigacao)
                .flatMap(resposta -> {
                    MediaType tipo = resposta.getHeaders().getContentType();
                    byte[] corpo = resposta.getBody();
                    if (corpo == null || corpo.length == 0 || tipo == null || !MediaType.APPLICATION_PDF.isCompatibleWith(tipo)) {
                        return Mono.error(new ApiException(HttpStatus.BAD_GATEWAY, "The report link did not return a PDF."));
                    }
                    return Mono.just(corpo);
                });
    }

    /**
     * A ligação vem do motor, mas o Kixi não abre URLs que não sejam dele: HTTPS, ou HTTP só em localhost, e no anfitrião
     * configurado. Sem isto, um motor comprometido (ou mal configurado) fazia o backend pedir o que quisesse.
     */
    private URI validar(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The motor returned an invalid report link.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        String motorHost = URI.create(properties.getBaseUrl()).getHost();
        boolean local = LOCAL.contains(host);
        boolean schemeOk = "https".equals(scheme) || ("http".equals(scheme) && local);
        boolean hostOk = local || host.equals(motorHost == null ? "" : motorHost.toLowerCase());
        if (!schemeOk || !hostOk) {
            log.warn("[relatorios] ligação recusada: {}", url);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The motor returned a report link outside its own address.");
        }
        return uri;
    }

    /** A resposta do pedido de ligação: 409 e 404 são do domínio; o resto é o motor indisponível. */
    private Throwable erroDoPedido(Throwable erro) {
        if (erro instanceof WebClientResponseException resposta) {
            int estado = resposta.getStatusCode().value();
            if (estado == 409) {
                return ApiException.conflict("The motor has no PDF for this report yet. Export it again after the pauta is homologated.");
            }
            if (estado == 404) {
                return ApiException.notFound("The motor does not know this pauta.");
            }
            log.warn("[relatorios] motor respondeu {}: {}", estado, resposta.getResponseBodyAsString());
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The motor answered " + estado + ".");
        }
        if (erro instanceof TimeoutException) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The motor did not answer in time. Try again.");
        }
        if (erro instanceof WebClientRequestException) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The motor is unreachable.");
        }
        return erro;
    }

    /** A ligação assinada já não vale (prazo esgotado, ficheiro apagado) ou o motor não respondeu. */
    private Throwable erroDaLigacao(Throwable erro) {
        if (erro instanceof WebClientResponseException) {
            return new ApiException(HttpStatus.BAD_GATEWAY, "The report link is no longer valid. Export it again.");
        }
        if (erro instanceof TimeoutException || erro instanceof WebClientRequestException) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The motor did not answer when the report was fetched. Try again.");
        }
        return erro;
    }
}
