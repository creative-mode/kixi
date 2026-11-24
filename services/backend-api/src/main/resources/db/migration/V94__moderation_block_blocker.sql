-- S5/S6: o bloqueio passa a saber quem o pôs, para o bloqueador o poder ver e desfazer.
-- Linhas antigas ficam com NULL (autor desconhecido): qualquer dos dois lados as pode levantar.
ALTER TABLE moderation_blocks
    ADD COLUMN IF NOT EXISTS blocked_by BIGINT NULL REFERENCES accounts(id) ON DELETE CASCADE;
