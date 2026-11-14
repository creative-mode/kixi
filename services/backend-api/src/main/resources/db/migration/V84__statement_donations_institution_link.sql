-- Ligação da doação à escola (kixi#597).
--
-- As doações de enunciados já chegavam com o nome da escola (institution_name), mas como texto
-- livre isso duplicava «ITEL», «Itel» e «I.T.E.L.». Esta coluna aponta a escola real quando o
-- doador a escolheu da lista; fica NULL quando o doador escreveu uma escola fora do catálogo
-- («Outra escola»). O nome fica sempre escrito e serve de fallback para as linhas antigas.
--
-- O catálogo público de escolas partilha o limitador anónimo de `api_abuse_rate_limits`
-- (o mesmo usado pelo login e pelos uploads), não tem tabela própria.

ALTER TABLE statement_donations
    ADD COLUMN institution_id BIGINT NULL REFERENCES institutions(id) ON DELETE SET NULL;

CREATE INDEX idx_statement_donations_institution ON statement_donations (institution_id);
