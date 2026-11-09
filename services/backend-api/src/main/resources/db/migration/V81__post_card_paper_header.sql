-- O cartão de uma prova publicada no feed traz o cabeçalho da folha (kixi#587).
--
-- Até aqui o cartão guardava só o título, a escola e os números do resultado, por isso o feed mostrava o desenho
-- estático. Passa a guardar também o tipo da prova, a classe, a disciplina, a duração, o ano letivo e a série, lidos no
-- servidor a partir do enunciado na hora de publicar (nunca do pedido).
ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS paper_kind VARCHAR(100) NULL,
    ADD COLUMN IF NOT EXISTS grade VARCHAR(30) NULL,
    ADD COLUMN IF NOT EXISTS subject VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS duration_minutes INTEGER NULL,
    ADD COLUMN IF NOT EXISTS school_year VARCHAR(20) NULL,
    ADD COLUMN IF NOT EXISTS variant VARCHAR(50) NULL;

-- Os cartões já publicados: o mesmo cabeçalho, a partir do enunciado da tentativa (simulação -> enunciado -> classe,
-- disciplina, ano letivo). A classe escreve-se «12ª Classe» e o ano «2025/2026», como o cabeçalho da folha. Uma tentativa
-- sem enunciado, ou um campo que o enunciado não tem, fica como está (nulo).
UPDATE posts p
   SET paper_kind = st.exam_type,
       grade = CASE WHEN c.grade IS NOT NULL THEN c.grade || 'ª Classe' END,
       subject = sub.name,
       duration_minutes = st.duration_minutes,
       school_year = CASE WHEN sy.start_year IS NOT NULL
                          THEN sy.start_year || '/' || sy.end_year END,
       variant = st.variant
  FROM simulations s
  JOIN statements st ON st.id = s.statement_id
  LEFT JOIN classes c ON c.id = st.class_id
  LEFT JOIN subjects sub ON sub.id = st.subject_id
  LEFT JOIN school_years sy ON sy.id = st.school_year_id
 WHERE p.simulation_id = s.id
   AND p.simulation_id IS NOT NULL;
