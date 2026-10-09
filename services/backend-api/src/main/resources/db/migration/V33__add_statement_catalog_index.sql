-- The student catalog (GET /api/v1/statements/catalog) only ever reads visible,
-- non-deleted statements, newest first, a page at a time. A partial index over exactly
-- that slice serves the default order and the page window without touching drafts,
-- statements still under review or the trash, which the students never see.
--
-- The single-column filters (school year, term, subject, class, institution) already
-- have their own indexes from V11 and V25.

CREATE INDEX IF NOT EXISTS idx_statements_catalog_recent
    ON statements (created_at DESC, id DESC)
    WHERE visible = TRUE AND deleted_at IS NULL;
