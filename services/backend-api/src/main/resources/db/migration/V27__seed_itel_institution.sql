INSERT INTO institutions (code, name, short_name) VALUES
    ('ITEL', 'Instituto de Telecomunicações', 'ITEL')
ON CONFLICT (code) DO NOTHING;

INSERT INTO institution_subjects (institution_id, subject_id)
SELECT i.id, s.id FROM institutions i CROSS JOIN subjects s WHERE i.code = 'ITEL'
ON CONFLICT (institution_id, subject_id) DO NOTHING;
