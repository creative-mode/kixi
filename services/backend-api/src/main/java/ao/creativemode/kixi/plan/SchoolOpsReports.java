package ao.creativemode.kixi.plan;

import ao.creativemode.kixi.plan.TermPlanService.Access;
import ao.creativemode.kixi.plan.TermPlanService.Assessment;
import ao.creativemode.kixi.plan.TermPlanService.Plan;
import ao.creativemode.kixi.shared.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.core.DatabaseClient;
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
    private final DatabaseClient db;
    private final ObjectMapper json;

    public SchoolOpsReports(SchoolOpsProperties properties, TermPlanService plans, AuditoriaRelatorios auditoria,
            WebClient.Builder webClients, DatabaseClient db, ObjectMapper json) {
        this.properties = properties;
        this.plans = plans;
        this.auditoria = auditoria;
        this.webClients = webClients;
        this.db = db;
        this.json = json;
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

    /**
     * A pauta da turma: o motor gera-a a pedido, sem estado, a partir do pedido que o Kixi monta com o registo escolar
     * (turma, curso, alunos e as notas por disciplina). Não há ligação pelo meio: o PDF vem na resposta.
     */
    public Mono<byte[]> pautaDaTurma(Long planId) {
        return plans.access().flatMap(who -> plans.get(who, planId).flatMap(plan -> {
            if (!properties.isConfigured()) {
                return Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Reports are unavailable: the school ops motor is not configured."));
            }
            if (plan.institutionId() == null) {
                return Mono.error(ApiException.conflict("This plan has no institution, so there is no class sheet to export."));
            }
            return pedidoDaTurma(plan)
                    .flatMap(pedido -> pedirPautaDaTurma(pedido)
                            .flatMap(pdf -> auditoria.registar(who.accountId(), plan.id(), "PAUTA_TURMA", null)
                                    .onErrorMap(erro -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                                            "Não foi possível registar a exportação; tente de novo."))
                                    .thenReturn(pdf)));
        }));
    }

    /** Turma, curso, alunos e as notas de cada disciplina, a partir do registo escolar que o motor já escreveu. */
    private Mono<Map<String, Object>> pedidoDaTurma(Plan plan) {
        return db.sql("SELECT code, name FROM institutions WHERE id = :i AND deleted_at IS NULL").bind("i", plan.institutionId())
                .map((r, m) -> new Instituicao(r.get("code", String.class), r.get("name", String.class))).one()
                .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found: " + plan.institutionId())))
                .flatMap(instituicao -> db.sql("SELECT code, grade, course_id, school_year_id FROM classes WHERE id = :c AND deleted_at IS NULL")
                        .bind("c", plan.classId())
                        .map((r, m) -> new Turma(r.get("code", String.class), r.get("grade", Integer.class),
                                r.get("course_id", Long.class), r.get("school_year_id", Long.class))).one()
                        .switchIfEmpty(Mono.error(ApiException.notFound("Class not found: " + plan.classId())))
                        .flatMap(turma -> db.sql("SELECT code, name FROM courses WHERE id = :id").bind("id", turma.courseId())
                                .map((r, m) -> new Curso(r.get("code", String.class), r.get("name", String.class))).one()
                                .switchIfEmpty(Mono.error(ApiException.notFound("Course not found: " + turma.courseId())))
                                .flatMap(curso -> folhasDaTurma(plan).flatMap(folhas -> {
                                    if (folhas.folhas().isEmpty()) {
                                        return Mono.error(ApiException.conflict(
                                                "Ainda não há pautas desta turma e trimestre: exporta depois de o professor fechar as provas."));
                                    }
                                    // Sem número não há linha na folha: o contrato exige-o, e o `montar` filtra-os.
                                    // Se nenhum tiver número, o PDF sairia sem alunos — melhor dizer porquê (409).
                                    List<PautaDaTurma.Aluno> alunos = alunos(folhas.entradas()).values().stream()
                                            .filter(a -> a.numero() != null && a.numero() >= 1).toList();
                                    if (alunos.isEmpty()) {
                                        return Mono.error(ApiException.conflict("As pautas desta turma ainda não têm alunos com número."));
                                    }
                                    String anoLectivo = folhas.anoLectivo() == null ? plan.termNumber() + "º trimestre" : folhas.anoLectivo();
                                    return Mono.just(PautaDaTurma.montar(plan.institutionId(), instituicao.nome(), instituicao.sigla(),
                                            plan.classId(), turma.nome(), turma.classe(), turma.anoId(), anoLectivo, plan.termNumber(),
                                            turma.courseId(), curso.sigla(), curso.nome(),
                                            new ArrayList<>(alunos), folhas.disciplinas()));
                                }))));
    }

    private record Instituicao(String sigla, String nome) {
    }

    private record Turma(String nome, Integer classe, Long courseId, Long anoId) {
    }

    private record Curso(String sigla, String nome) {
    }

    private record Folha(String sheetId, JsonNode contexto) {
    }

    private record Linha(String sheetId, String componente, Long accountId, BigDecimal nota, String situacao, JsonNode payload) {
    }

    private record FolhasDaTurma(List<Folha> folhas, List<Linha> entradas, String anoLectivo,
            List<PautaDaTurma.Disciplina> disciplinas) {
    }

    /** As folhas do registo desta turma e trimestre, com as entradas de cada uma. */
    private Mono<FolhasDaTurma> folhasDaTurma(Plan plan) {
        return db.sql("SELECT sheet_id, CAST(context AS text) AS c FROM register_term_sheets WHERE status IN ('REGISTADA', 'FECHADA')")
                .map((r, m) -> new Folha(r.get("sheet_id", String.class), ler(r.get("c", String.class)))).all().collectList()
                .flatMap(todas -> {
                    List<Folha> daTurma = todas.stream().filter(f -> daTurma(f.contexto(), plan)).toList();
                    if (daTurma.isEmpty()) {
                        return Mono.just(new FolhasDaTurma(List.of(), List.of(), null, List.of()));
                    }
                    String anoLectivo = daTurma.stream().map(f -> f.contexto().path("anoLectivo").asText("")).filter(a -> !a.isBlank())
                            .findFirst().orElse(null);
                    return db.sql("SELECT sheet_id, component, account_id, score, status, CAST(payload AS text) AS p"
                                    + " FROM register_term_entries WHERE sheet_id IN (:ids)")
                            .bind("ids", daTurma.stream().map(Folha::sheetId).toList())
                            .map((r, m) -> new Linha(r.get("sheet_id", String.class), r.get("component", String.class),
                                    r.get("account_id", Long.class), r.get("score", BigDecimal.class), r.get("status", String.class),
                                    ler(r.get("p", String.class)))).all().collectList()
                            .map(entradas -> new FolhasDaTurma(daTurma, entradas, anoLectivo, disciplinas(daTurma, entradas)));
                });
    }

    private boolean daTurma(JsonNode contexto, Plan plan) {
        String turmaId = contexto.path("turmaId").asText("");
        String raiz = turmaId.split("-", 2)[0];
        return String.valueOf(plan.classId()).equals(raiz) && contexto.path("trimestre").asInt() == plan.termNumber();
    }

    /** O número e o nome vieram do motor no payload de cada entrada; o aluno é a conta. */
    private static Map<Long, PautaDaTurma.Aluno> alunos(List<Linha> entradas) {
        Map<Long, PautaDaTurma.Aluno> alunos = new LinkedHashMap<>();
        for (Linha linha : entradas) {
            JsonNode payload = linha.payload();
            Integer numero = payload.path("numero").isNumber() ? payload.path("numero").asInt() : null;
            String nome = payload.path("nome").asText("");
            alunos.putIfAbsent(linha.accountId(), new PautaDaTurma.Aluno(linha.accountId(), numero, nome, null));
        }
        return alunos;
    }

    private static List<PautaDaTurma.Disciplina> disciplinas(List<Folha> folhas, List<Linha> entradas) {
        List<PautaDaTurma.Disciplina> disciplinas = new ArrayList<>();
        for (Folha folha : folhas) {
            JsonNode contexto = folha.contexto();
            Map<String, List<PautaDaTurma.Entrada>> porComponente = new LinkedHashMap<>();
            entradas.stream().filter(l -> l.sheetId().equals(folha.sheetId()))
                    .sorted(Comparator.comparing(Linha::accountId))
                    .forEach(l -> porComponente.computeIfAbsent(l.componente(), k -> new ArrayList<>())
                            .add(new PautaDaTurma.Entrada(l.accountId(), l.nota(), l.situacao())));
            comValoresDaMt(folha, entradas, porComponente);
            if (porComponente.isEmpty()) {
                continue;
            }
            disciplinas.add(new PautaDaTurma.Disciplina(contexto.path("disciplinaId").asLong(),
                    contexto.path("disciplinaSigla").asText(""), contexto.path("disciplina").asText(""), porComponente));
        }
        return disciplinas;
    }

    /**
     * O motor regista só a MAC ({@code minipauta.registar}) e a MT ({@code pauta.registar}); a P1 e a P2 (NPP e NPT) vivem no
     * payload da linha da MT ({@code npp}, {@code npt}, {@code mac}), não como linhas próprias. Sem isto a folha da turma saía
     * sem as colunas P1 e P2 (vaga 1B, C1). Uma componente que o registo já tem em linhas próprias não se toca; um valor
     * nulo (prova dispensada, aluno sem nota) fica em branco em vez de inventar uma situação.
     */
    private static void comValoresDaMt(Folha folha, List<Linha> entradas, Map<String, List<PautaDaTurma.Entrada>> porComponente) {
        for (String[] campo : new String[][] {{"NPP", "npp"}, {"NPT", "npt"}, {"MAC", "mac"}}) {
            if (porComponente.containsKey(campo[0])) {
                continue;
            }
            List<PautaDaTurma.Entrada> derivadas = entradas.stream()
                    .filter(l -> l.sheetId().equals(folha.sheetId()) && "MT".equals(l.componente()))
                    .sorted(Comparator.comparing(Linha::accountId))
                    .filter(l -> l.payload().path(campo[1]).isNumber())
                    .map(l -> new PautaDaTurma.Entrada(l.accountId(), l.payload().path(campo[1]).decimalValue(), null))
                    .toList();
            if (!derivadas.isEmpty()) {
                porComponente.put(campo[0], new ArrayList<>(derivadas));
            }
        }
    }

    private JsonNode ler(String jsonTexto) {
        try {
            return jsonTexto == null || jsonTexto.isBlank() ? json.createObjectNode() : json.readTree(jsonTexto);
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    /** Pede ao motor a folha da turma: o PDF vem na própria resposta, não há ligação. */
    private Mono<byte[]> pedirPautaDaTurma(Map<String, Object> pedido) {
        return cliente().baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getToken())
                .build().post().uri("/v1/pautas-turma").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_PDF)
                .bodyValue(pedido).retrieve().toEntity(byte[].class)
                .timeout(RESPONSE)
                .onErrorMap(this::erroDoPedido)
                .flatMap(this::comoPdf);
    }

    private Mono<byte[]> comoPdf(ResponseEntity<byte[]> resposta) {
        MediaType tipo = resposta.getHeaders().getContentType();
        byte[] corpo = resposta.getBody();
        if (corpo == null || corpo.length == 0 || tipo == null || !MediaType.APPLICATION_PDF.isCompatibleWith(tipo)) {
            return Mono.error(new ApiException(HttpStatus.BAD_GATEWAY, "The report did not come back as a PDF."));
        }
        return Mono.just(corpo);
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
                .flatMap(this::comoPdf);
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
