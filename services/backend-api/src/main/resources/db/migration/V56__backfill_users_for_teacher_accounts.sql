-- Giving a teacher platform access (POST /teachers/{id}/account) only ever created the
-- account and linked teachers.account_id — it never created the matching row in `users`,
-- which is what GET /profiles/accounts/{accountId} and GET /profiles/users/{userId} read
-- from (the same table a self-registered or Google-signed-in account gets). Every teacher
-- given access before this fix has a profile that 404s. Backfill the missing `users` row
-- from the teacher's own record, for every active teacher that still lacks one.
INSERT INTO users (account_id, first_name, last_name, photo)
SELECT t.account_id, t.first_name, t.last_name, t.photo
FROM teachers t
WHERE t.account_id IS NOT NULL
  AND t.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM users u WHERE u.account_id = t.account_id);
