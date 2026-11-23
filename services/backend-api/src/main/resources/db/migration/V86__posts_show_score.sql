-- O cartão de uma simulação publica a nota para a turma (kixi#592), mas o autor pode preferir não a mostrar.
-- A flag é por post: guarda-se no momento da publicação e vale para as leituras depois disso. TRUE é a regra
-- antiga (a nota sempre visível); FALSE é o cartão com a nota e os acertos escondidos a quem não é o autor.

ALTER TABLE posts
    ADD COLUMN show_score BOOLEAN NOT NULL DEFAULT TRUE;
