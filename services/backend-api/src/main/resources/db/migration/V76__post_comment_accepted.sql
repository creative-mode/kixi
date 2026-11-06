-- V76: resposta aceite numa discussão (kixi#729).
--
-- O autor da publicação marca, no máximo, uma resposta como aceite. A coluna fica no comentário; o serviço garante
-- que só há uma por publicação (aceitar outra retira a anterior).
ALTER TABLE post_comments ADD COLUMN is_accepted BOOLEAN NOT NULL DEFAULT FALSE;
