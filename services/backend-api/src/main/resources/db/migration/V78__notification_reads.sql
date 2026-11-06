-- V78: «Marcar tudo como lido» nas notificações (kixi#729).
--
-- As notificações do aluno são derivadas do que já existe (provas, resultados, respostas); o que se guarda é o momento
-- até ao qual ele já as viu. Uma linha por conta.
CREATE TABLE notification_reads (
    account_id BIGINT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    read_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
