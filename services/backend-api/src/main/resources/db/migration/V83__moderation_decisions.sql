-- Fila de denúncias, decisão e auditoria da moderação (kixi#591).
--
-- Uma denúncia nasce OPEN e o administrador decide-a uma única vez: IGNORED (nada muda), HIDDEN (só escolas) ou
-- SUSPENDED (só contas). A nota explica a decisão. O estado vive na própria denúncia; o trilho (quem, quando, o quê,
-- porquê) vai para moderation_audit, escrito na mesma transacção da decisão.
ALTER TABLE moderation_reports
    ADD COLUMN IF NOT EXISTS status VARCHAR(12) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN IF NOT EXISTS decided_at TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS decided_by_account_id BIGINT NULL,
    ADD COLUMN IF NOT EXISTS decision_note VARCHAR(500) NULL;

ALTER TABLE moderation_reports
    ADD CONSTRAINT chk_moderation_report_status CHECK (status IN ('OPEN', 'IGNORED', 'HIDDEN', 'SUSPENDED')),
    ADD CONSTRAINT chk_moderation_report_decided CHECK (
        (status = 'OPEN' AND decided_at IS NULL AND decided_by_account_id IS NULL)
        OR (status <> 'OPEN' AND decided_at IS NOT NULL AND decided_by_account_id IS NOT NULL)
    );

CREATE INDEX IF NOT EXISTS idx_moderation_reports_status
    ON moderation_reports (status, created_at DESC);

-- Sem chave estrangeira para a denúncia nem para o alvo, de propósito: o registo de uma decisão tem de sobreviver a
-- quem foi apagado depois.
CREATE TABLE IF NOT EXISTS moderation_audit (
    id BIGSERIAL PRIMARY KEY,
    report_id BIGINT NOT NULL,
    action VARCHAR(12) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id BIGINT NOT NULL,
    actor_account_id BIGINT NOT NULL,
    note VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_moderation_audit_action CHECK (action IN ('IGNORED', 'HIDDEN', 'SUSPENDED')),
    CONSTRAINT chk_moderation_audit_target CHECK (target_type IN ('ACCOUNT', 'INSTITUTION'))
);

CREATE INDEX IF NOT EXISTS idx_moderation_audit_created ON moderation_audit (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_moderation_audit_report ON moderation_audit (report_id);
