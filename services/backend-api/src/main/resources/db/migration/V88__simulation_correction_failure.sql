-- kixi#609: o motor SchoolOps avisa quando não consegue corrigir uma simulação livre (a IA falhou depois de todas as tentativas).
-- O estado da correção (PENDING, IN_PROGRESS, DONE, FAILED) é derivado das respostas; só a falha se guarda, e limpa-se quando uma
-- nova correção da IA chega.
ALTER TABLE simulations ADD COLUMN IF NOT EXISTS correction_failed_at TIMESTAMP WITHOUT TIME ZONE NULL;
ALTER TABLE simulations ADD COLUMN IF NOT EXISTS correction_failure_reason VARCHAR(500) NULL;
