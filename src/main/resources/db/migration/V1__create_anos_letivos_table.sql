CREATE TABLE anos_letivos (
    id BIGSERIAL PRIMARY KEY,
    ano_inicio INT NOT NULL,
    ano_fim INT NOT NULL,
    CONSTRAINT uk_anos_letivos UNIQUE (ano_inicio, ano_fim),
    CONSTRAINT ck_anos_letivos_intervalo CHECK (ano_fim > ano_inicio)
);
