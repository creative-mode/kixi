-- Posts com estrutura (issue #347): uma lista de blocos (título, texto, lista, destaque,
-- fórmula, imagem, desafio) guardada como JSON em texto. `content` continua a existir e,
-- num post com blocos, guarda o resumo em texto simples para os clientes antigos.

ALTER TABLE posts ADD COLUMN IF NOT EXISTS blocks TEXT NULL;
