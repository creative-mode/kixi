-- O motor SchoolOps devolve eventos da pauta aberta a partir do plano (kixi#525).
ALTER TABLE term_plans ADD COLUMN pauta_status VARCHAR(20),
                       ADD COLUMN pauta_event_at TIMESTAMP;
