-- Flag «editado» honesta para as publicações e os comentários do feed (kixi#593).
--
-- O spring-data-r2dbc marca `updated_at` em qualquer save (esconder, desmarcar aceitação,
-- apagar), por isso «foi editado» não pode ser derivado de `created_at != updated_at` — um
-- comentário aceite e depois desmarcado pareceria editado. Esta coluna regista só a edição
-- pelo autor (ou moderador, no comentário); fica NULL para tudo o resto.

ALTER TABLE posts ADD COLUMN edited_at TIMESTAMP WITHOUT TIME ZONE NULL;
ALTER TABLE post_comments ADD COLUMN edited_at TIMESTAMP WITHOUT TIME ZONE NULL;
