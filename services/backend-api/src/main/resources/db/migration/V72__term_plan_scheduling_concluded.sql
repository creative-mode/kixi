-- A pauta abre quando o professor conclui o agendamento do plano (não quando tudo está corrigido), e cada prova é
-- encerrada no motor só quando a sua sala fecha ou a data passa (kixi#536).
ALTER TABLE term_plans ADD COLUMN scheduling_concluded_at TIMESTAMP,
                       ADD COLUMN scheduling_concluded_by BIGINT REFERENCES accounts(id);
ALTER TABLE term_assessments ADD COLUMN closed_notified_at TIMESTAMP;

-- Planos que já tinham a pauta aberta contam como agendamento concluído e prova encerrada.
UPDATE term_plans SET scheduling_concluded_at = pauta_opened_at WHERE pauta_opened_at IS NOT NULL;
UPDATE term_assessments SET closed_notified_at = CURRENT_TIMESTAMP
 WHERE kind IN ('P1', 'P2') AND plan_id IN (SELECT id FROM term_plans WHERE pauta_opened_at IS NOT NULL);
