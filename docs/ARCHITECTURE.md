# Smart Study Gap Analyzer — Architecture (Milestone 1)

Status: design only. No application code exists yet.

## 1. Environment (verified 2026-09-28)

| Item | Value |
|---|---|
| OS | Ubuntu 26.04 LTS |
| java / javac (PATH) | OpenJDK 25.0.4 |
| Maven | 3.9.12, running on JDK 21.0.12 (`JAVA_HOME` set in `~/.bashrc`) |
| MySQL | 8.4.11, server running on 127.0.0.1:3306 |
| Git | 2.53.0 |

Notes:
- PATH `java` is 25 but Maven uses 21. Both JDKs include `javac`.
  **Decision:** target Java 21 (LTS) with `maven.compiler.release=21`. This
  builds identically under either JDK.
- MySQL root/`ash` login without a password is denied. A dedicated
  application DB user must be created in Milestone 2 (credentials come from
  environment variables, never from source).
- Git branch was `master`; renamed to `main` in Milestone 2.

## 2. Final architecture

```
Browser (HTML/CSS/vanilla JS)
   │  fetch() + JSON, Authorization: Bearer <token>
   ▼
HTTP server  (JDK built-in com.sun.net.httpserver — no framework)
   ▼
Controller   parse request, call service, write JSON. No logic.
   ▼
Service      validation, hashing, evaluation, scoring, gap analysis
   ▼
Repository   JDBC + PreparedStatement only. All SQL lives here.
   ▼
MySQL 8.4
```

Design choices (kept minimal, per "do not over-engineer"):

| Concern | Choice | Reason |
|---|---|---|
| HTTP | JDK `HttpServer` | Zero dependencies; enough for ~12 endpoints; no Spring |
| JSON | Jackson (`jackson-databind`) | Standard, safe parsing of request bodies |
| DB driver | `mysql-connector-j` | Required for JDBC to MySQL |
| Connections | `DriverManager` via one `Database` helper | Simple; a pool can be added later if needed |
| Password hashing | PBKDF2WithHmacSHA256, per-user random salt (JDK built-in) | No plaintext; no extra dependency |
| Auth | Random opaque token, stored **hashed** in `auth_sessions` | Student identity comes from the token, never from the request body |
| Config | `application.properties` (non-secret) + env vars (DB creds) | No hardcoded credentials |
| Static files | Same server serves `frontend/` | Same origin, so no CORS handling |
| Tests | JUnit 5 (service logic unit tests; repository tests against a test DB) | |

Security rules baked into the design:
- The submit endpoint accepts only `{questionId, selectedOptionId}`. Correctness
  is computed on the server from `question_options.is_correct`.
- The question-listing DTO never contains `is_correct`.
- The `studentId` is never accepted from the client; it is resolved from the token.

## 3. Backend package structure

Maven module at `backend/`, base package `com.smartstudy` (revised in
Milestone 2: flat layer packages, with `util/` holding cross-cutting helpers
instead of separate `server/`, `security/` and `exception/` packages).

```
backend/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/smartstudy/
    │   │   ├── App.java                 entry point: load config, build/start HttpServer
    │   │   ├── config/                  AppConfig (properties + env, threshold validation)
    │   │   ├── controller/              HealthController, AuthController, AuthFilter, Endpoint;
    │   │   │                            later QuizController, AttemptController, PerformanceController
    │   │   ├── service/                 AuthService; later QuizService, AttemptService,
    │   │   │                            GapAnalysisService, DashboardService
    │   │   ├── repository/              Database, StudentRepository + SessionRepository (interfaces),
    │   │   │                            JdbcStudentRepository, JdbcSessionRepository, DataAccessException;
    │   │   │                            later QuizRepository, ...
    │   │   ├── model/                   Student, AuthSession; later Topic, Quiz, Question, ...
    │   │   ├── dto/                     Request/response records (RegisterRequest, LoginRequest,
    │   │   │                            LoginResponse, StudentResponse, AuthenticatedUser, ErrorResponse, ...)
    │   │   └── util/                    HttpUtil, PasswordHasher, TokenGenerator, ErrorLog and the
    │   │                                exception types (ValidationException, AuthenticationException, ...)
    │   └── resources/
    │       └── application.properties
    └── test/java/com/smartstudy/        mirrors main packages
```

Dependency direction is strictly downward: controller → service → repository.
Controllers never touch JDBC; repositories never contain business rules.

## 4. Frontend structure

Vanilla HTML/CSS/JS, no build step, served from `frontend/`.

```
frontend/
├── index.html          landing / redirect
├── register.html
├── login.html
├── quizzes.html        quiz list
├── quiz.html           take a quiz (questions + submit)
├── result.html         score + per-question outcome after submit
├── history.html        attempt history
├── dashboard.html      topic-wise performance + learning gaps
├── css/
│   └── styles.css
└── js/
    ├── api.js          fetch wrapper: base URL, token header, error handling
    ├── auth.js         store/clear token, redirect to login if missing
    └── <page>.js       one script per page
```

The token is kept in `sessionStorage`/`localStorage`. Because the DOM is
built from server data, the frontend must use `textContent` (not `innerHTML`)
for any user- or DB-supplied strings.

## 5. Database entities

Database: `smart_study_gap_analyzer` (MySQL 8.4, InnoDB, utf8mb4).

| Table | Key columns |
|---|---|
| `students` | id PK, name, email UNIQUE, password_hash, password_salt, created_at |
| `auth_sessions` | id PK, student_id FK, token_hash UNIQUE, created_at, expires_at |
| `topics` | id PK, name UNIQUE |
| `quizzes` | id PK, title, description, created_at |
| `questions` | id PK, quiz_id FK, topic_id FK, question_text, position |
| `question_options` | id PK, question_id FK, option_text, is_correct |
| `quiz_attempts` | id PK, student_id FK, quiz_id FK, total_questions, correct_count, score_percent, submitted_at |
| `attempt_answers` | id PK, attempt_id FK, question_id FK, selected_option_id FK (nullable = unanswered), is_correct |

Design points:
- Single-answer multiple choice: exactly one `is_correct = TRUE` option per
  question (enforced in service/seed validation).
- `attempt_answers.is_correct` is a snapshot computed server-side at submission,
  so history stays stable if a quiz is later edited.
- Topic accuracy and gap classification are **derived** by query
  (`attempt_answers` → `questions` → `topics`) and not stored.
- Thresholds are configuration, not a table.

## 6. Entity relationships

```
students 1 ──── N auth_sessions
students 1 ──── N quiz_attempts
quizzes  1 ──── N questions
topics   1 ──── N questions
questions 1 ─── N question_options
quizzes  1 ──── N quiz_attempts
quiz_attempts 1 ─ N attempt_answers
questions 1 ──── N attempt_answers
question_options 1 ─ N attempt_answers  (the selected option)
```

Topics link to quizzes only through questions, so a single quiz can span
several topics.

## 7. Gap analysis

```
accuracy = correct answers / total questions * 100
```

Per topic, aggregated over all of the student's attempts (cumulative). An
unanswered question counts as a question and as incorrect.

Default thresholds (in `application.properties`):

```
gap.threshold.strong=75
gap.threshold.moderate=50
```

| Accuracy | Label |
|---|---|
| `>= strong` | Strong |
| `>= moderate` and `< strong` | Moderate |
| `< moderate` | Needs Improvement |
| no questions in topic yet | No Data (not classified) |

Startup validation: `0 <= moderate < strong <= 100`, otherwise fail fast.
Boundary rule uses `< strong` (not `<= 74`), so 74.5% is Moderate.

## 8. Initial API plan

All paths under `/api`, JSON bodies. "Auth" = requires bearer token.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/auth/register` | no | Register student (name, email, password) |
| POST | `/api/auth/login` | no | Returns token |
| POST | `/api/auth/logout` | yes | Invalidate token |
| GET | `/api/quizzes` | yes | List quizzes |
| GET | `/api/quizzes/{id}/questions` | yes | Questions + options, **no correctness data** |
| POST | `/api/quizzes/{id}/attempts` | yes | Body: `{"answers": [{questionId, selectedOptionId}]}` → 201 `{id, quizId, submittedAt, totalQuestions, answeredCount}`. Evaluated and stored server-side; no score or correctness in the response (implemented in Milestone 9) |
| GET | `/api/attempts` | yes | Current student's attempt history |
| GET | `/api/attempts/{id}` | yes | One attempt with per-question outcome (own attempts only) |
| GET | `/api/performance/topics` | yes | Topic-wise accuracy |
| GET | `/api/performance/gaps` | yes | Topics with classification |
| GET | `/api/dashboard` | yes | Aggregate: overall score, recent attempts, topics, gaps |
| GET | `/api/health` | no | Liveness check |

Error format: `{ "error": "message" }` with 400 / 401 / 403 / 404 / 500.
Validation failures (400) add `"fields": { "<field>": "<message>" }`; messages never echo the input.
Ownership checks (attempt belongs to the caller) live in the service layer.

### Authentication (implemented in Milestone 3)

| Endpoint | Success | Failure |
|---|---|---|
| `POST /api/auth/register` `{name,email,password}` | 201 `{id,name,email}` | 400 validation, 409 duplicate email |
| `POST /api/auth/login` `{email,password}` | 200 `{token,tokenType,expiresAt,student}` | 400 missing fields, 401 invalid credentials |
| `GET /api/auth/me` | 200 `{id,name,email}` | 401 |
| `POST /api/auth/logout` | 200 `{message}` | 401 |

- **Passwords:** PBKDF2WithHmacSHA256, 210,000 iterations, 32-byte key, 16-byte `SecureRandom`
  salt, both Base64 (compatible with `database/seed.sql`). Verified with `MessageDigest.isEqual`
  (constant time). Unknown-email logins verify against a dummy hash so timing and message match
  wrong-password logins.
- **Password policy:** 8-128 characters with at least one letter and one digit. Login does not
  re-apply the policy.
- **Email:** trimmed, lower-cased, max 255, simple linear-time pattern. Name: 1-100 characters,
  no control characters.
- **Sessions:** opaque 256-bit `SecureRandom` token (Base64URL, 43 chars), returned once at login.
  Only its SHA-256 hex digest is stored in `auth_sessions.token_hash`. Lifetime is
  `auth.session.hours` (default 24, env `AUTH_SESSION_HOURS`).
- **Revocation:** logout deletes the session row, so a revoked token can never authenticate; no
  schema change was needed. Expired sessions found during a check are deleted too.
- **Filter:** `AuthFilter` (a JDK `HttpServer` `Filter`) is attached to protected contexts. It requires
  `Authorization: Bearer <token>`, rejects malformed headers without touching the database,
  validates the session, and publishes an `AuthenticatedUser` on the exchange. Handlers get the
  caller only from there; a client-supplied student id is never used.
- **Time zones:** JDBC connections are pinned to UTC (`connectionTimeZone=UTC`) so `expires_at`
  round-trips as the same instant regardless of JVM or server zone.

## 9. Development milestones

| # | Milestone | Outcome |
|---|---|---|
| 1 | Analysis, env check, architecture | This document |
| 2 | Project skeleton + DB foundation | `pom.xml`, config, `.gitignore`, schema SQL, DB user/connection, `/api/health` |
| 3 | Student registration + login | PBKDF2 hashing, sessions, auth filter, tests |
| 4 | Quiz listing + questions | Repositories/services/controllers, seed data, no `is_correct` leakage |
| 5 | Submission, evaluation, scoring | Server-side grading, stored attempt + answers |
| 6 | Attempt history | List + detail endpoints, ownership checks |
| 7 | Topic performance + gap analysis | Configurable thresholds, classification tests |
| 8 | Dashboard API | Aggregated endpoint |
| 9 | Frontend pages | Register/login, quizzes, quiz, result, history, dashboard |
| 10 | Hardening + docs | Input validation review, README, end-to-end run |

Each milestone: implement only that scope, test, report, suggest a commit, stop.

## 10. Decisions (resolved at start of Milestone 2)

1. Java target: **21** (`maven.compiler.release=21`); JDK 25 stays installed, Maven keeps using JDK 21.
2. Gap analysis: **cumulative** accuracy across all completed attempts.
   Recent-performance and trend calculations are planned for later; latest-attempt-only
   analysis will not be implemented.
3. Git branch: **`main`**.
4. MySQL app user: created by the developer (needs `sudo mysql`); commands are in
   `database/README.md`. Credentials come from environment variables only.
