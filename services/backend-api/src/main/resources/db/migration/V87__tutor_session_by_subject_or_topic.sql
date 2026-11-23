-- Tutor por disciplina ou tema (kixi#610).
--
-- Version note: V87 is the next free after V86, which the feed's «mostrar a nota» (kixi#612, PR #616) claims. This
-- branch includes that V86 while it is stacked on #616, and MigrationInventoryTest requires a contiguous sequence.
-- If another branch claims V87 first, renumber on rebase.
--
-- Até aqui uma sessão do tutor estava sempre presa a um enunciado (kixi#114). Fora das provas não há enunciado: a
-- sessão passa a poder ser aberta por disciplina (subjects) ou por um tema da matriz (subject_topics), e o material
-- vem das questões dessas provas visíveis. Uma sessão tem exactamente um contexto: um enunciado, ou uma disciplina/tema.
ALTER TABLE chat_sessions
    ALTER COLUMN statement_id DROP NOT NULL;

ALTER TABLE chat_sessions
    ADD COLUMN subject_id BIGINT REFERENCES subjects(id),
    ADD COLUMN topic_id BIGINT REFERENCES subject_topics(id);

ALTER TABLE chat_sessions
    ADD CONSTRAINT chk_chat_sessions_context CHECK (
        (statement_id IS NOT NULL AND subject_id IS NULL AND topic_id IS NULL)
        OR (statement_id IS NULL AND (subject_id IS NOT NULL OR topic_id IS NOT NULL))
    );

CREATE INDEX idx_chat_sessions_subject ON chat_sessions (subject_id) WHERE subject_id IS NOT NULL;
CREATE INDEX idx_chat_sessions_topic ON chat_sessions (topic_id) WHERE topic_id IS NOT NULL;
