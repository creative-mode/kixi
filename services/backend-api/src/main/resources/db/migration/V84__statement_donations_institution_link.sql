-- Ligação da doação à escola (kixi#597).
--
-- As doações de enunciados já chegavam com o nome da escola (institution_name), mas como texto
-- livre isso duplicava «ITEL», «Itel» e «I.T.E.L.». Esta coluna aponta a escola real quando o
-- doador a escolheu da lista; fica NULL quando o doador escreveu uma escola fora do catálogo
-- («Outra escola»). O nome fica sempre escrito e serve de fallback para as linhas antigas.

ALTER TABLE statement_donations
    ADD COLUMN institution_id BIGINT NULL REFERENCES institutions(id) ON DELETE SET NULL;

CREATE INDEX idx_statement_donations_institution ON statement_donations (institution_id);

-- Janela deslizante para o catálogo público de escolas. Mesmo padrão da tabela das doações
-- públicas, com prefixo próprio para a chave nunca cair na das doações.
CREATE TABLE institutions_public_rate_limits (
    rate_key            VARCHAR(64) PRIMARY KEY,
    window_started_at   TIMESTAMPTZ NOT NULL,
    attempts            INTEGER NOT NULL CHECK (attempts > 0),
    updated_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_institutions_public_rate_limits_updated_at
    ON institutions_public_rate_limits (updated_at);
