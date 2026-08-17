INSERT INTO roles (name, description) VALUES
    ('ADMIN', 'System administrator with full access'),
    ('TEACHER', 'Teacher with access to create and manage statements'),
    ('STUDENT', 'Student with access to view statements and take simulations')
ON CONFLICT (name) DO NOTHING;

INSERT INTO terms (number, name) VALUES
    (1, '1º Trimestre'),
    (2, '2º Trimestre'),
    (3, '3º Trimestre')
ON CONFLICT (number) DO NOTHING;

INSERT INTO subjects (code, name, short_name) VALUES
    ('MAT', 'Matemática', 'Mat'),
    ('PORT', 'Língua Portuguesa', 'Port'),
    ('FIS', 'Física', 'Fís'),
    ('QUIM', 'Química', 'Quím'),
    ('BIO', 'Biologia', 'Bio'),
    ('HIST', 'História', 'Hist'),
    ('GEO', 'Geografia', 'Geo'),
    ('ING', 'Inglês', 'Ing'),
    ('FIL', 'Filosofia', 'Fil')
ON CONFLICT (code) DO NOTHING;

INSERT INTO school_years (start_year, end_year) VALUES
    (2024, 2025)
ON CONFLICT (start_year, end_year) DO NOTHING;
