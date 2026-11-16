-- Report do catálogo para a demo (kixi#614): quantos enunciados VISÍVEIS existem por
-- disciplina e por classe, e onde faltam até atingir 2 por disciplina/classe.
--
-- Read-only; não muda nada. Corre contra qualquer Postgres com o schema do Kixi
-- (docker-compose up postgres, ou psql à mão num snapshot da demo):
--
--   docker compose exec postgres psql -U kixi -d kixi -f /caminho/catalog-cobertura.sql
--   # ou, no host directo:
--   psql "$DATABASE_URL" -f infra/load/catalog-cobertura.sql
--
-- Uso esperado: correr antes da demo e ler a coluna «faltam_para_2». Para ver
-- só as turmas do dia, muda a cláusula `WHERE c.grade IN (11, 12)` da secção 3.
-- O alvo é 2 por (disciplina, classe), não por disciplina: conta só o que é
-- visível e não está marcado para revisão nem preso a uma sala por fechar.

\echo '=== 1)' Enunciados visíveis por disciplina (alvo ≥ 2) ===
SELECT
    s.code                AS subject_code,
    s.name                AS subject,
    COUNT(st.id)          AS visiveis,
    GREATEST(0, 2 - COUNT(st.id)) AS faltam_para_2
FROM subjects s
LEFT JOIN statements st
       ON st.subject_id   = s.id
      AND st.deleted_at   IS NULL
      AND st.visible      = TRUE
      AND st.needs_review = FALSE
GROUP BY s.code, s.name
ORDER BY faltam_para_2 DESC, s.code;

\echo '=== 2)' Enunciados visíveis por disciplina × classe ===
SELECT
    s.code                AS subject_code,
    s.name                AS subject,
    c.grade               AS classe,
    COALESCE(co.name, '—') AS course,
    COUNT(st.id)          AS visiveis,
    GREATEST(0, 2 - COUNT(st.id)) AS faltam_para_2
FROM subjects s
CROSS JOIN classes c
LEFT JOIN courses  co ON co.id = c.course_id
LEFT JOIN statements st
       ON st.subject_id  = s.id
      AND st.class_id    = c.id
      AND st.deleted_at  IS NULL
      AND st.visible     = TRUE
      AND st.needs_review = FALSE
WHERE c.deleted_at IS NULL
GROUP BY s.code, s.name, c.grade, co.name
ORDER BY faltam_para_2 DESC, c.grade, s.code;

\echo '=== 3)' Só as turmas da demo (mude :turma_codes para o que interessa) ===
\echo '--- 3)  :turma_codes antes/depois desta secção ou ajusta o IN à mão'
\echo '-- exemplo: 11º e 12º de qualquer curso: WHERE c.grade IN (11, 12)'
SELECT
    c.grade               AS classe,
    co.name               AS course,
    s.code                AS subject_code,
    s.name                AS subject,
    COUNT(st.id)          AS visiveis,
    GREATEST(0, 2 - COUNT(st.id)) AS faltam_para_2
FROM classes   c
JOIN courses   co ON co.id = c.course_id
CROSS JOIN subjects s
LEFT JOIN statements st
       ON st.subject_id  = s.id
      AND st.class_id    = c.id
      AND st.deleted_at  IS NULL
      AND st.visible     = TRUE
      AND st.needs_review = FALSE
WHERE c.deleted_at IS NULL
  AND c.grade IN (11, 12)
GROUP BY c.grade, co.name, s.code, s.name
ORDER BY faltam_para_2 DESC, c.grade, co.name, s.code;
