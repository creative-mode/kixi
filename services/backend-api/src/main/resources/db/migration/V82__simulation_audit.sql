-- Trilho de auditoria das simulações e das respostas dos alunos (kixi#596).
--
-- Criar, editar, apagar, restaurar ou purgar uma simulação ou uma resposta muda o que o recálculo das notas vê, e o purge é
-- irreversível. Cada acto fica aqui: quem, quando, sobre o quê e, nos que apagam, restauram ou purgam (só o administrador),
-- porquê. Sem chave estrangeira para a linha auditada, de propósito: o registo de um purge tem de sobreviver ao que apagou.
DROP TABLE IF EXISTS simulation_answer_audit;

CREATE TABLE IF NOT EXISTS simulation_audit (
    id BIGSERIAL PRIMARY KEY,
    target_type VARCHAR(12) NOT NULL,
    target_id BIGINT NOT NULL,
    simulation_id BIGINT NULL,
    action VARCHAR(20) NOT NULL,
    actor_account_id BIGINT NOT NULL,
    reason VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_simulation_audit_target CHECK (target_type IN ('ANSWER', 'SIMULATION')),
    CONSTRAINT chk_simulation_audit_action CHECK (action IN ('CREATE', 'UPDATE', 'SOFT_DELETE', 'RESTORE', 'PURGE')),
    -- Apagar, restaurar e purgar exigem motivo; criar e editar (o aluno a responder, o motor a entregar) não.
    CONSTRAINT chk_simulation_audit_reason CHECK (action IN ('CREATE', 'UPDATE') OR reason IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_simulation_audit_target ON simulation_audit (target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_simulation_audit_simulation ON simulation_audit (simulation_id);
CREATE INDEX IF NOT EXISTS idx_simulation_audit_created ON simulation_audit (created_at DESC);
