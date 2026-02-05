-- =============================================================================
-- Kixi Database Initialization Script
-- =============================================================================
-- This script creates all required tables for the Kixi platform.
-- It should be idempotent (safe to run multiple times).
-- =============================================================================

-- Create database if not exists (handled by Docker)
-- CREATE DATABASE kixi;

-- =============================================================================
-- School Years Table
-- =============================================================================
CREATE TABLE IF NOT EXISTS school_years (
    id BIGSERIAL PRIMARY KEY,
    start_year INTEGER NOT NULL,
    end_year INTEGER NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uk_school_years UNIQUE (start_year, end_year),
    CONSTRAINT ck_school_years_interval CHECK (end_year > start_year)
);

-- =============================================================================
-- Terms Table (Trimesters/Periods)
-- =============================================================================
CREATE TABLE IF NOT EXISTS terms (
    id BIGSERIAL PRIMARY KEY,
    number INTEGER NOT NULL,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uk_terms_number UNIQUE (number)
);

-- =============================================================================
-- Subjects Table
-- =============================================================================
CREATE TABLE IF NOT EXISTS subjects (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(20) NOT NULL,
    name VARCHAR(200) NOT NULL,
    short_name VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uk_subjects_code UNIQUE (code)
);

-- =============================================================================
-- Courses Table
-- =============================================================================
CREATE TABLE IF NOT EXISTS courses (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(20) NOT NULL,
    name VARCHAR(200) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uk_courses_code UNIQUE (code)
);

-- =============================================================================
-- Classes Table
-- =============================================================================
CREATE TABLE IF NOT EXISTS classes (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50),
    grade INTEGER NOT NULL,
    course_id BIGINT REFERENCES courses(id),
    school_year_id BIGINT REFERENCES school_years(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_classes_course ON classes(course_id);
CREATE INDEX IF NOT EXISTS idx_classes_school_year ON classes(school_year_id);

-- =============================================================================
-- Accounts Table
-- =============================================================================
CREATE TABLE IF NOT EXISTS accounts (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    email_verified BOOLEAN DEFAULT FALSE,
    last_login TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uk_accounts_username UNIQUE (username),
    CONSTRAINT uk_accounts_email UNIQUE (email)
);

-- =============================================================================
-- Users Table (Profile information)
-- =============================================================================
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    photo VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uk_users_account UNIQUE (account_id)
);

-- =============================================================================
-- Roles Table
-- =============================================================================
CREATE TABLE IF NOT EXISTS roles (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uk_roles_name UNIQUE (name)
);

-- =============================================================================
-- Account Roles Table (Many-to-Many)
-- =============================================================================
CREATE TABLE IF NOT EXISTS account_roles (
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    role_id BIGINT NOT NULL REFERENCES roles(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    PRIMARY KEY (account_id, role_id)
);

-- =============================================================================
-- Sessions Table
-- =============================================================================
CREATE TABLE IF NOT EXISTS sessions (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    token VARCHAR(500) NOT NULL,
    ip_address VARCHAR(50),
    expires_at TIMESTAMP NOT NULL,
    last_used TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sessions_account ON sessions(account_id);
CREATE INDEX IF NOT EXISTS idx_sessions_token ON sessions(token);

-- =============================================================================
-- Statements Table (Exam Papers)
-- =============================================================================
CREATE TABLE IF NOT EXISTS statements (
    id BIGSERIAL PRIMARY KEY,
    exam_type VARCHAR(100),
    duration_minutes INTEGER,
    variant VARCHAR(10),
    title VARCHAR(500),
    instructions TEXT,
    total_max_score DECIMAL(10, 2),
    school_year_id BIGINT REFERENCES school_years(id),
    term_id BIGINT REFERENCES terms(id),
    subject_id BIGINT REFERENCES subjects(id),
    class_id BIGINT REFERENCES classes(id),
    course_id BIGINT REFERENCES courses(id),
    created_by BIGINT REFERENCES accounts(id),
    visible BOOLEAN DEFAULT FALSE,
    needs_review BOOLEAN DEFAULT FALSE,
    ocr_confidence DECIMAL(5, 4),
    ocr_request_id VARCHAR(100),
    source VARCHAR(50) DEFAULT 'manual',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_statements_school_year ON statements(school_year_id);
CREATE INDEX IF NOT EXISTS idx_statements_term ON statements(term_id);
CREATE INDEX IF NOT EXISTS idx_statements_subject ON statements(subject_id);
CREATE INDEX IF NOT EXISTS idx_statements_class ON statements(class_id);
CREATE INDEX IF NOT EXISTS idx_statements_created_by ON statements(created_by);
CREATE INDEX IF NOT EXISTS idx_statements_visible ON statements(visible);
CREATE INDEX IF NOT EXISTS idx_statements_needs_review ON statements(needs_review);
CREATE INDEX IF NOT EXISTS idx_statements_source ON statements(source);
CREATE INDEX IF NOT EXISTS idx_statements_deleted_at ON statements(deleted_at);

-- =============================================================================
-- Questions Table
-- =============================================================================
CREATE TABLE IF NOT EXISTS questions (
    id BIGSERIAL PRIMARY KEY,
    statement_id BIGINT NOT NULL REFERENCES statements(id) ON DELETE CASCADE,
    number INTEGER NOT NULL,
    text TEXT NOT NULL,
    question_type VARCHAR(50) NOT NULL DEFAULT 'unknown',
    max_score DECIMAL(10, 2),
    order_index INTEGER,
    ocr_confidence DECIMAL(5, 4),
    page_index INTEGER DEFAULT 0,
    needs_review BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uk_questions_statement_number UNIQUE (statement_id, number)
);

CREATE INDEX IF NOT EXISTS idx_questions_statement ON questions(statement_id);
CREATE INDEX IF NOT EXISTS idx_questions_type ON questions(question_type);
CREATE INDEX IF NOT EXISTS idx_questions_deleted_at ON questions(deleted_at);

-- =============================================================================
-- Question Images Table
-- =============================================================================
CREATE TABLE IF NOT EXISTS question_images (
    id BIGSERIAL PRIMARY KEY,
    question_id BIGINT NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    image_url VARCHAR(1000) NOT NULL,
    caption VARCHAR(500),
    order_index INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_question_images_question ON question_images(question_id);

-- =============================================================================
-- Question Options Table (Multiple Choice Answers)
-- =============================================================================
CREATE TABLE IF NOT EXISTS question_options (
    id BIGSERIAL PRIMARY KEY,
    question_id BIGINT NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    option_label VARCHAR(10) NOT NULL,
    option_text TEXT NOT NULL,
    is_correct BOOLEAN DEFAULT FALSE,
    order_index INTEGER DEFAULT 0,
    ocr_confidence DECIMAL(5, 4),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uk_question_options_label UNIQUE (question_id, option_label)
);

CREATE INDEX IF NOT EXISTS idx_question_options_question ON question_options(question_id);
CREATE INDEX IF NOT EXISTS idx_question_options_deleted_at ON question_options(deleted_at);

-- =============================================================================
-- Simulations Table (Student Exam Attempts)
-- =============================================================================
CREATE TABLE IF NOT EXISTS simulations (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    statement_id BIGINT NOT NULL REFERENCES statements(id),
    school_year_id BIGINT REFERENCES school_years(id),
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP,
    time_spent_seconds INTEGER,
    final_score DECIMAL(10, 2),
    status VARCHAR(50) DEFAULT 'in_progress',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_simulations_account ON simulations(account_id);
CREATE INDEX IF NOT EXISTS idx_simulations_statement ON simulations(statement_id);
CREATE INDEX IF NOT EXISTS idx_simulations_status ON simulations(status);

-- =============================================================================
-- Simulation Answers Table
-- =============================================================================
CREATE TABLE IF NOT EXISTS simulation_answers (
    id BIGSERIAL PRIMARY KEY,
    simulation_id BIGINT NOT NULL REFERENCES simulations(id) ON DELETE CASCADE,
    question_id BIGINT NOT NULL REFERENCES questions(id),
    selected_option_id BIGINT REFERENCES question_options(id),
    answer_text TEXT,
    score_obtained DECIMAL(10, 2),
    is_correct BOOLEAN,
    answered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_simulation_answers_simulation ON simulation_answers(simulation_id);
CREATE INDEX IF NOT EXISTS idx_simulation_answers_question ON simulation_answers(question_id);

-- =============================================================================
-- Default Data
-- =============================================================================

-- Insert default roles
INSERT INTO roles (name, description) VALUES
    ('ADMIN', 'System administrator with full access'),
    ('TEACHER', 'Teacher with access to create and manage statements'),
    ('STUDENT', 'Student with access to view statements and take simulations')
ON CONFLICT (name) DO NOTHING;

-- Insert default terms
INSERT INTO terms (number, name) VALUES
    (1, '1º Trimestre'),
    (2, '2º Trimestre'),
    (3, '3º Trimestre')
ON CONFLICT (number) DO NOTHING;

-- Insert sample subjects
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

-- Insert sample school year
INSERT INTO school_years (start_year, end_year) VALUES
    (2024, 2025)
ON CONFLICT (start_year, end_year) DO NOTHING;

-- =============================================================================
-- Functions and Triggers
-- =============================================================================

-- Function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Apply trigger to all tables with updated_at column
DO $$
DECLARE
    t text;
BEGIN
    FOR t IN
        SELECT table_name FROM information_schema.columns
        WHERE column_name = 'updated_at'
        AND table_schema = 'public'
    LOOP
        EXECUTE format('
            DROP TRIGGER IF EXISTS update_%I_updated_at ON %I;
            CREATE TRIGGER update_%I_updated_at
                BEFORE UPDATE ON %I
                FOR EACH ROW
                EXECUTE FUNCTION update_updated_at_column();
        ', t, t, t, t);
    END LOOP;
END;
$$ language 'plpgsql';

-- =============================================================================
-- Grant Permissions (if needed for specific users)
-- =============================================================================
-- GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO kixi;
-- GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO kixi;
