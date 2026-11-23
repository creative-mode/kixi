-- kixi#622: cada pedido de exportação da pauta em PDF fica registado (quem, que plano, que documento,
-- quando), sem guardar o link nem o conteúdo. O motor é que assina e serve os PDF.
--
-- Sem chave estrangeira para accounts de propósito: este registo é a prova de que houve acesso ao
-- relatório da escola e tem de sobreviver à eliminação da conta (o direito ao esquecimento apaga os
-- dados pessoais, não a prova do acesso). O account_id é o identificador, não a pessoa.
CREATE TABLE IF NOT EXISTS report_exports (
    id            BIGSERIAL PRIMARY KEY,
    account_id    BIGINT      NOT NULL,
    plan_id       BIGINT      NOT NULL,
    kind          VARCHAR(24) NOT NULL,
    assessment_id BIGINT,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_report_exports_plan ON report_exports (plan_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_report_exports_account ON report_exports (account_id, created_at DESC);
