-- Trilho de auditoria das respostas dos alunos (kixi#596).
--
-- Apagar, restaurar ou purgar uma resposta muda o que o recálculo das notas vê, e o purge é irreversível. Só o administrador o
-- faz, com um motivo, e cada acto fica aqui: quem, quando, sobre que resposta/simulação e porquê. Sem chave estrangeira para a
-- resposta, de propósito: o registo do purge tem de sobreviver à resposta que apagou.
CREATE TABLE IF NOT EXISTS simulation_answer_audit (
    id BIGSERIAL PRIMARY KEY,
    answer_id BIGINT NOT NULL,
    simulation_id BIGINT NULL,
    action VARCHAR(20) NOT NULL,
    actor_account_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_simulation_answer_audit_action CHECK (action IN ('SOFT_DELETE', 'RESTORE', 'PURGE'))
);

CREATE INDEX IF NOT EXISTS idx_simulation_answer_audit_answer ON simulation_answer_audit (answer_id);
CREATE INDEX IF NOT EXISTS idx_simulation_answer_audit_created ON simulation_answer_audit (created_at DESC);
