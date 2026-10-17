-- Visibilidade do perfil público de cada pessoa (issue #336).
--
-- Há menores na plataforma: por omissão só quem partilha uma escola vê o perfil completo.
-- Uma tabela à parte, e não uma coluna em users, para não mexer no que já cria utilizadores.
-- Sem linha, vale 'school'.

CREATE TABLE user_profile_settings (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    visibility VARCHAR(10) NOT NULL DEFAULT 'school'
        CONSTRAINT ck_user_profile_settings_visibility
        CHECK (visibility IN ('public', 'school', 'private')),
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
