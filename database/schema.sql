-- Smart Study Gap Analyzer - MySQL 8.4 schema
--
-- Run once as a MySQL admin:   sudo mysql < database/schema.sql
-- This script is intentionally NOT idempotent: CREATE TABLE fails if a table
-- already exists, so it never silently leaves a stale schema in place.
-- To start over, drop the database yourself first.
--
-- Topic accuracy / gap classification is NOT stored anywhere. It is derived
-- from attempt_answers -> questions -> topics at query time.

CREATE DATABASE IF NOT EXISTS smart_study_gap_analyzer
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE smart_study_gap_analyzer;

-- ---------------------------------------------------------------------------
-- students
-- password_hash / password_salt: Base64 (PBKDF2WithHmacSHA256). Never plaintext.
-- ---------------------------------------------------------------------------
CREATE TABLE students (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name           VARCHAR(100)    NOT NULL,
    email          VARCHAR(255)    NOT NULL,
    password_hash  VARCHAR(255)    NOT NULL,
    password_salt  VARCHAR(64)     NOT NULL,
    created_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_students_email (email)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- auth_sessions
-- token_hash: SHA-256 hex of the bearer token (the raw token is never stored).
-- ---------------------------------------------------------------------------
CREATE TABLE auth_sessions (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    student_id  BIGINT UNSIGNED NOT NULL,
    token_hash  CHAR(64)        NOT NULL,
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at  TIMESTAMP       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_auth_sessions_token_hash (token_hash),
    KEY idx_auth_sessions_student (student_id),
    KEY idx_auth_sessions_expires (expires_at),
    CONSTRAINT fk_auth_sessions_student
        FOREIGN KEY (student_id) REFERENCES students (id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- topics
-- ---------------------------------------------------------------------------
CREATE TABLE topics (
    id    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name  VARCHAR(100)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_topics_name (name)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- quizzes
-- ---------------------------------------------------------------------------
CREATE TABLE quizzes (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    title        VARCHAR(200)    NOT NULL,
    description  VARCHAR(1000)   NULL,
    created_at   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_quizzes_title (title)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- questions
-- A quiz can span several topics; topic is set per question.
-- A quiz/topic cannot be deleted while questions reference it.
-- ---------------------------------------------------------------------------
CREATE TABLE questions (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    quiz_id        BIGINT UNSIGNED NOT NULL,
    topic_id       BIGINT UNSIGNED NOT NULL,
    question_text  VARCHAR(1000)   NOT NULL,
    position       INT UNSIGNED    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_questions_quiz_position (quiz_id, position),
    KEY idx_questions_topic (topic_id),
    CONSTRAINT fk_questions_quiz
        FOREIGN KEY (quiz_id)  REFERENCES quizzes (id) ON DELETE CASCADE,
    CONSTRAINT fk_questions_topic
        FOREIGN KEY (topic_id) REFERENCES topics (id)  ON DELETE RESTRICT
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- question_options
-- Single-answer multiple choice: exactly one is_correct = TRUE per question.
-- That rule is enforced by the service layer / seed validation, not by MySQL.
-- uq_options_id_question exists so attempt_answers can prove that a selected
-- option belongs to the question it answers (composite foreign key).
-- ---------------------------------------------------------------------------
CREATE TABLE question_options (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    question_id  BIGINT UNSIGNED NOT NULL,
    option_text  VARCHAR(500)    NOT NULL,
    is_correct   BOOLEAN         NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uq_options_id_question (id, question_id),
    KEY idx_options_question (question_id),
    CONSTRAINT fk_options_question
        FOREIGN KEY (question_id) REFERENCES questions (id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- quiz_attempts
-- One row per submission. score_percent is this attempt's own score snapshot
-- (correct_count / total_questions * 100), not a per-topic value.
-- History is protected: a student/quiz with attempts cannot be removed
-- by deleting the quiz (RESTRICT); deleting a student removes their attempts.
-- ---------------------------------------------------------------------------
CREATE TABLE quiz_attempts (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    student_id       BIGINT UNSIGNED NOT NULL,
    quiz_id          BIGINT UNSIGNED NOT NULL,
    total_questions  INT UNSIGNED    NOT NULL,
    correct_count    INT UNSIGNED    NOT NULL,
    score_percent    DECIMAL(5,2)    NOT NULL,
    submitted_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_attempts_student_submitted (student_id, submitted_at),
    KEY idx_attempts_quiz (quiz_id),
    CONSTRAINT fk_attempts_student
        FOREIGN KEY (student_id) REFERENCES students (id) ON DELETE CASCADE,
    CONSTRAINT fk_attempts_quiz
        FOREIGN KEY (quiz_id)    REFERENCES quizzes (id)  ON DELETE RESTRICT,
    CONSTRAINT chk_attempts_counts
        CHECK (total_questions > 0 AND correct_count <= total_questions),
    CONSTRAINT chk_attempts_score
        CHECK (score_percent BETWEEN 0 AND 100)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- attempt_answers
-- selected_option_id NULL = question left unanswered (counts as incorrect).
-- is_correct is the server-side grading snapshot taken at submission time.
-- The composite FK (selected_option_id, question_id) guarantees the chosen
-- option belongs to the answered question; it is skipped when NULL.
-- Questions/options referenced by history cannot be deleted (RESTRICT).
-- idx_answers_question supports the per-topic aggregation
-- (attempt_answers -> questions -> topics).
-- ---------------------------------------------------------------------------
CREATE TABLE attempt_answers (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    attempt_id          BIGINT UNSIGNED NOT NULL,
    question_id         BIGINT UNSIGNED NOT NULL,
    selected_option_id  BIGINT UNSIGNED NULL,
    is_correct          BOOLEAN         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_answers_attempt_question (attempt_id, question_id),
    KEY idx_answers_question (question_id),
    KEY idx_answers_option (selected_option_id, question_id),
    CONSTRAINT fk_answers_attempt
        FOREIGN KEY (attempt_id)  REFERENCES quiz_attempts (id) ON DELETE CASCADE,
    CONSTRAINT fk_answers_question
        FOREIGN KEY (question_id) REFERENCES questions (id)     ON DELETE RESTRICT,
    CONSTRAINT fk_answers_selected_option
        FOREIGN KEY (selected_option_id, question_id)
        REFERENCES question_options (id, question_id)           ON DELETE RESTRICT
) ENGINE=InnoDB;
