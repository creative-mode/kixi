-- Questões com alíneas (issue #247).
--
-- Uma questão do tipo 'group' é só o enunciado comum (sem resposta nem cotação) e cada alínea é uma
-- questão filha, com o seu tipo, a sua cotação e as suas opções. Assim a nota, a correção e as
-- respostas continuam a trabalhar sobre questões respondíveis, e o enunciado comum não se repete.
--
-- `number` é único por enunciado: as filhas usam pai*100 + índice (301, 302, ...), só para serem
-- únicas, e os números impressos dos de topo (1, 2, 3, ...) ficam como estão. A ordem da prova é a de
-- `order_index` (o grupo, as suas alíneas, a questão seguinte), que `number` não consegue dizer
-- (3 < 4 < 301). O que se mostra de uma filha é "3a", feito do número do pai e do rótulo.

ALTER TABLE questions
    ADD COLUMN IF NOT EXISTS parent_id BIGINT NULL REFERENCES questions(id),
    ADD COLUMN IF NOT EXISTS label VARCHAR(10) NULL;

CREATE INDEX IF NOT EXISTS idx_questions_parent_id ON questions(parent_id) WHERE parent_id IS NOT NULL;
