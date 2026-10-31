-- Um enunciado de prova de sala só é visível ao aluno depois da prova terminada e corrigida (kixi#519).
-- release_after_exam guarda a intenção de publicar enquanto o enunciado está retido.
ALTER TABLE statements ADD COLUMN release_after_exam BOOLEAN NOT NULL DEFAULT FALSE;

-- Liberta o enunciado quando já não há sala por fechar nem respostas das salas por rever.
CREATE FUNCTION release_statement_if_graded(sid BIGINT) RETURNS VOID AS $$
BEGIN
    UPDATE statements s SET visible = TRUE, release_after_exam = FALSE, updated_at = CURRENT_TIMESTAMP
    WHERE s.id = sid AND s.release_after_exam
      AND NOT EXISTS (SELECT 1 FROM exam_rooms r WHERE r.statement_id = sid AND r.status <> 'CLOSED')
      AND NOT EXISTS (SELECT 1 FROM simulation_answers a JOIN simulations x ON x.id = a.simulation_id
                      WHERE x.statement_id = sid AND x.exam_room_id IS NOT NULL AND x.deleted_at IS NULL
                        AND a.deleted_at IS NULL AND a.review_status = 'PENDING_REVIEW');
END;
$$ LANGUAGE plpgsql;

-- Uma sala nova retém o enunciado visível.
CREATE FUNCTION embargo_statement_on_room() RETURNS TRIGGER AS $$
BEGIN
    UPDATE statements SET release_after_exam = TRUE, visible = FALSE, updated_at = CURRENT_TIMESTAMP
    WHERE id = NEW.statement_id AND visible;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_exam_rooms_embargo AFTER INSERT ON exam_rooms
    FOR EACH ROW EXECUTE FUNCTION embargo_statement_on_room();

-- Fechar a sala pode libertar o enunciado.
CREATE FUNCTION release_on_room_closed() RETURNS TRIGGER AS $$
BEGIN
    PERFORM release_statement_if_graded(NEW.statement_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_exam_rooms_release AFTER UPDATE OF status ON exam_rooms
    FOR EACH ROW WHEN (NEW.status = 'CLOSED') EXECUTE FUNCTION release_on_room_closed();

-- Corrigir a última resposta pendente pode libertar o enunciado.
CREATE FUNCTION release_on_answer_graded() RETURNS TRIGGER AS $$
DECLARE sid BIGINT;
BEGIN
    SELECT x.statement_id INTO sid FROM simulations x WHERE x.id = NEW.simulation_id AND x.exam_room_id IS NOT NULL;
    IF sid IS NOT NULL THEN PERFORM release_statement_if_graded(sid); END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_simulation_answers_release AFTER UPDATE OF review_status ON simulation_answers
    FOR EACH ROW WHEN (OLD.review_status = 'PENDING_REVIEW' AND NEW.review_status <> 'PENDING_REVIEW')
    EXECUTE FUNCTION release_on_answer_graded();

-- O professor que mostra o enunciado mais cedo decide: a retenção acaba.
CREATE FUNCTION clear_embargo_on_manual_show() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.visible AND NEW.release_after_exam THEN NEW.release_after_exam := FALSE; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_statements_manual_show BEFORE UPDATE OF visible ON statements
    FOR EACH ROW WHEN (NEW.visible AND NOT OLD.visible) EXECUTE FUNCTION clear_embargo_on_manual_show();

-- Os enunciados já ligados a salas por fechar ou por corrigir ficam retidos.
UPDATE statements s SET release_after_exam = TRUE, visible = FALSE
WHERE s.visible AND (
    EXISTS (SELECT 1 FROM exam_rooms r WHERE r.statement_id = s.id AND r.status <> 'CLOSED')
    OR EXISTS (SELECT 1 FROM simulation_answers a JOIN simulations x ON x.id = a.simulation_id
               WHERE x.statement_id = s.id AND x.exam_room_id IS NOT NULL AND x.deleted_at IS NULL
                 AND a.deleted_at IS NULL AND a.review_status = 'PENDING_REVIEW'));
