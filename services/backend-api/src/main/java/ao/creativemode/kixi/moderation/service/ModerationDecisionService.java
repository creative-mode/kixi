package ao.creativemode.kixi.moderation.service;

import ao.creativemode.kixi.moderation.dto.AuditEntryResponse;
import ao.creativemode.kixi.moderation.dto.ModerationReportResponse;
import ao.creativemode.kixi.moderation.model.ModerationReport;
import ao.creativemode.kixi.moderation.repository.ModerationAdminRepository;
import ao.creativemode.kixi.moderation.repository.ModerationReportRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A fila de denúncias, a decisão e o registo de auditoria da moderação (kixi#591). Só o administrador chega aqui (a
 * regra está em {@code SecurityConfig}); o serviço guarda as regras de negócio: o par decisão/alvo, a nota e o facto de
 * cada decisão gravar sempre a auditoria na mesma transacção.
 */
@Service
public class ModerationDecisionService {

    public static final String IGNORED = "IGNORED";
    public static final String HIDDEN = "HIDDEN";
    public static final String SUSPENDED = "SUSPENDED";

    /** Os mesmos mínimos e máximos que o ecrã do administrador aplica (lib/moderation/model.ts). */
    static final int MIN_NOTE_TO_ACT = 10;
    static final int MAX_NOTE = 500;

    private static final Set<String> STATUSES = Set.of("OPEN", IGNORED, HIDDEN, SUSPENDED);

    private final ModerationReportRepository reports;
    private final ModerationAdminRepository admin;

    public ModerationDecisionService(ModerationReportRepository reports, ModerationAdminRepository admin) {
        this.reports = reports;
        this.admin = admin;
    }

    /** A fila, opcionalmente só as de um estado ({@code OPEN}, {@code IGNORED}, {@code HIDDEN}, {@code SUSPENDED}). */
    public Flux<ModerationReportResponse> queue(String status) {
        if (status != null && !status.isBlank() && !STATUSES.contains(status.trim())) {
            return Flux.error(ApiException.badRequest("Estado inválido."));
        }
        return admin.queue(status == null || status.isBlank() ? null : status.trim());
    }

    public Flux<AuditEntryResponse> audit() {
        return admin.audit();
    }

    /**
     * Decide uma denúncia por decidir. A transacção cobre as quatro escritas (fecho da denúncia, efeito no alvo,
     * auditoria): se qualquer uma falhar, nenhuma fica, por isso não há decisão sem trilho nem trilho sem decisão.
     */
    @Transactional
    public Mono<ModerationReportResponse> decide(Long actorId, Long reportId, String decision, String rawNote) {
        String note = rawNote == null ? "" : rawNote.trim();
        if (note.length() > MAX_NOTE) {
            return Mono.error(ApiException.badRequest("A nota tem de ter no máximo " + MAX_NOTE + " caracteres."));
        }
        if (!IGNORED.equals(decision) && note.length() < MIN_NOTE_TO_ACT) {
            return Mono.error(ApiException.badRequest("Para " + (HIDDEN.equals(decision) ? "ocultar" : "suspender")
                    + " escreva uma nota com pelo menos " + MIN_NOTE_TO_ACT + " caracteres: é ela que explica a decisão."));
        }
        return reports.findById(reportId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Denúncia não encontrada.")))
                .flatMap(report -> apply(actorId, report, decision, note));
    }

    private Mono<ModerationReportResponse> apply(Long actorId, ModerationReport report, String decision, String note) {
        boolean account = "ACCOUNT".equals(report.getTargetType());
        Long targetId = account ? report.getTargetAccountId() : report.getTargetInstitutionId();
        if (SUSPENDED.equals(decision) && !account) {
            return Mono.error(ApiException.badRequest("Uma escola pode ser ocultada, não suspensa."));
        }
        if (HIDDEN.equals(decision) && account) {
            return Mono.error(ApiException.badRequest("Uma pessoa pode ser suspensa, não ocultada."));
        }
        if (SUSPENDED.equals(decision) && actorId.equals(targetId)) {
            return Mono.error(ApiException.badRequest("Não pode suspender a sua própria conta."));
        }
        if (!"OPEN".equals(report.getStatus())) {
            return Mono.error(ApiException.conflict("Esta denúncia já foi decidida."));
        }
        LocalDateTime now = LocalDateTime.now();
        // O UPDATE condicional é o que decide uma corrida entre dois administradores: só um muda a linha de OPEN.
        return admin.closeIfOpen(report.getId(), decision, actorId, note, now)
                .flatMap(changed -> changed == 0
                        ? Mono.<Void>error(ApiException.conflict("Esta denúncia já foi decidida."))
                        : effect(decision, targetId))
                .then(Mono.defer(() -> admin.recordAudit(
                        report.getId(), decision, report.getTargetType(), targetId, actorId, note, now)))
                .then(Mono.defer(() -> admin.report(report.getId())));
    }

    private Mono<Void> effect(String decision, Long targetId) {
        if (SUSPENDED.equals(decision)) {
            return admin.deactivateAccount(targetId).then();
        }
        if (HIDDEN.equals(decision)) {
            return admin.hideInstitution(targetId).then();
        }
        return Mono.empty();
    }
}
