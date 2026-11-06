-- V77: medalha «Mentor da turma» (kixi#729): três respostas suas aceites pelos autores das perguntas.
INSERT INTO badges (code, name, description) VALUES
    ('MENTOR', 'Mentor da turma', 'Teve três respostas aceites por quem fez a pergunta.')
ON CONFLICT (code) DO NOTHING;
