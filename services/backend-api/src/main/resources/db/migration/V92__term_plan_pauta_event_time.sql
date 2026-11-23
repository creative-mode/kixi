-- O instante (campo `time` do CloudEvent) do último evento do motor aplicado à pauta do plano: entre dois eventos do mesmo estado
-- (um reenvio, uma homologação atrasada) o mais antigo não sobrescreve o mais recente (V2-13). UTC, como pauta_event_at.
ALTER TABLE term_plans ADD COLUMN pauta_event_time TIMESTAMP;
