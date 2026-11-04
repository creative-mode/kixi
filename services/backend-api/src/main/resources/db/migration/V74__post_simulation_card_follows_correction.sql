-- A nota do cartão de uma prova publicada acompanha a correção (kixi#464).
--
-- O cartão continua a ser um instantâneo tirado na hora de publicar, mas a nota
-- e o número de respostas ainda por corrigir não ficam congelados: a correção
-- de uma resposta aberta reescreve-os na linha da publicação, para o feed nunca
-- mostrar uma nota que já não é a do resultado.
ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS pending_review_count INTEGER NULL;

-- Reconcilia os cartões já publicados com a tentativa que os originou: a nota
-- atual e as abertas que ainda aguardam correção. Um cartão cuja tentativa foi
-- apagada, ou que já foi apagado, fica como está.
UPDATE posts p
   SET final_score = s.final_score,
       pending_review_count = (
           SELECT COUNT(*)
             FROM simulation_answers a
            WHERE a.simulation_id = p.simulation_id
              AND a.deleted_at IS NULL
              AND a.review_status = 'PENDING_REVIEW'
       )
  FROM simulations s
 WHERE p.simulation_id = s.id
   AND s.deleted_at IS NULL
   AND p.deleted_at IS NULL;
