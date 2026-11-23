-- Planos reagendados antes de a correção do reagendamento (kixi#534, ce19da28) ficaram com o agendamento concluído e sem enunciado
-- numa prova P1 ou P2 por dispensar: a pauta nunca abre e o abridor avisa a cada ciclo. Reabre-se o agendamento desses planos (o
-- professor liga o enunciado e conclui outra vez). Conservadora e idempotente: só planos com a pauta ainda por abrir, e só esse campo.
UPDATE term_plans p
   SET scheduling_concluded_at = NULL
 WHERE p.pauta_opened_at IS NULL
   AND p.scheduling_concluded_at IS NOT NULL
   AND EXISTS (SELECT 1 FROM term_assessments a
                WHERE a.plan_id = p.id AND a.kind IN ('P1', 'P2') AND a.dispensed_at IS NULL AND a.statement_id IS NULL);
