-- Publicar o resultado de uma simulação no feed.
--
-- O cartão é um instantâneo tirado no servidor na hora de publicar: a nota, o
-- número de acertos e o nome da escola não vêm do cliente, e não mudam se a
-- prova for corrigida depois. simulation_id aponta para a tentativa que o
-- originou; sem ela o post é um post normal.

ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS simulation_id BIGINT NULL,
    ADD COLUMN IF NOT EXISTS statement_title VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS school_name VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS final_score DOUBLE PRECISION NULL,
    ADD COLUMN IF NOT EXISTS max_score DOUBLE PRECISION NULL,
    ADD COLUMN IF NOT EXISTS correct_answers INTEGER NULL,
    ADD COLUMN IF NOT EXISTS total_questions INTEGER NULL,
    ADD COLUMN IF NOT EXISTS time_spent_seconds INTEGER NULL;

-- Uma tentativa só se publica uma vez (dois toques no botão não duplicam o cartão).
CREATE UNIQUE INDEX IF NOT EXISTS uk_posts_simulation_id
    ON posts(simulation_id) WHERE simulation_id IS NOT NULL AND deleted_at IS NULL;
