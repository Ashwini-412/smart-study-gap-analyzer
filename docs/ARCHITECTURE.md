# Smart Study Gap Analyzer — Architecture

Status: implemented and validated through Milestone 15 (see section 9). Written as the
Milestone 1 design and updated as each milestone landed; setup and run instructions are in the
root `README.md`.

## 1. Development environment (verified 2026-09-28, Milestone 1)

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
| Static files | Same server serves `frontend/` at `/` (`StaticFileController`, Milestone 13) | Same origin, so no CORS handling |
| Tests | JUnit 5 (service/controller tests over in-memory repositories; JDBC repository tests against the configured MySQL database, skipped unless `DB_PASSWORD` is set, cleaning up the rows they create) | |

Security rules baked into the design:
- The submit endpoint accepts only `{questionId, selectedOptionId}`. Correctness
  is computed on the server from `question_options.is_correct`.
- The question-listing DTO never contains `is_correct`.
- The `studentId` is never accepted from the client; it is resolved from the token.

### Static frontend serving (implemented in Milestone 13)

The API contexts (`/api/...`) and the frontend share one `HttpServer`. `StaticFileController`
is mounted on the root context `/`, which the JDK server only uses when no `/api/...` context
matches, so no API route changed.

- **Location:** `frontend.dir` in `application.properties`, overridable with env `FRONTEND_DIR`,
  default `../frontend` (relative to the working directory, i.e. `backend/`). If the directory
  is missing, startup logs a warning and serves the API only.
- **Restrictions:**
  - GET/HEAD only; anything else gets 405.
  - Only regular files of an allow-listed type (`.html`, `.css`, `.js`, `.svg`, `.png`, `.ico`)
    inside the root. Everything else gets a plain 404:
    - `.`/`..` segments and hidden files;
    - paths that normalise, or resolve through a symlink, outside the root;
    - directories. `/` and `.../` map to `index.html`, and there are no listings.
  - An unmatched `/api/...` path still gets the API's JSON 404, not a static-file response.
- **Headers:** a strict same-origin `Content-Security-Policy` that allows no inline script,
  `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer` and `Cache-Control: no-cache`.

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
    │   │   ├── controller/              HealthController, AuthController, AuthFilter, Endpoint, TopicController,
    │   │   │                            QuizController, QuizIdRouter, AttemptController, PerformanceController,
    │   │   │                            StaticFileController
    │   │   ├── service/                 AuthService, TopicService, QuizService, QuestionService, AttemptService,
    │   │   │                            PerformanceService (gap analysis); no DashboardService (section 8)
    │   │   ├── repository/              Database, DataAccessException, one interface + Jdbc* implementation each
    │   │   │                            for students, sessions, topics, quizzes, questions, attempts, performance
    │   │   ├── model/                   Student, AuthSession, Topic, Quiz, Question, QuestionOption,
    │   │   │                            QuizAttempt, AttemptAnswer
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

Vanilla HTML/CSS/JS (ES modules), no build step and no dependencies. Served by the Java
server from `frontend/` (section 2). Implemented in Milestone 13.

```
frontend/
├── index.html          redirect: dashboard if a token is present, else login
├── login.html          sign in; shows registered / logged-out / session-expired notices
├── register.html       create account; client-side confirm-password check, backend field errors
├── dashboard.html      summary, topic gap table, recent attempts
├── quizzes.html        quiz list
├── quiz.html           take a quiz (?id=): questions + submit
├── result.html         one attempt's stored result (?id=)
├── history.html        all of the student's attempts, newest first
├── css/
│   └── styles.css      responsive layout, light/dark
└── js/
    ├── api.js          the only module that calls fetch: token, JSON, errors, 401 handling
    ├── auth.js         requireAuth() guard (GET /api/auth/me), shared header/nav, logout
    ├── ui.js           DOM helpers (createElement/textContent), loading/empty/error states
    ├── attempts.js     attempt table shared by dashboard and history
    └── <page>.js       one module per page
```

API and auth structure:
- **Single API client.** `api.js` sends every request as JSON with `Authorization: Bearer <token>`.
  It turns the backend error format (`{error, fields?}`) into one `ApiError` type; an
  unreachable server becomes status 0. Pages render loading, empty and error states (with
  retry) from that.
- **Token handling.** The login token is kept in `sessionStorage` only, and passwords are never
  stored. A 401 on a protected call clears the token and redirects to
  `login.html?expired=1`. Logout calls `POST /api/auth/logout`, which revokes the session
  server-side, then clears the token.
- **Identity.** Protected pages call `requireAuth()` first. Identity comes from
  `GET /api/auth/me`, and no student id is ever sent or stored.
- **No client-side grading.** The quiz page sends only `{questionId, selectedOptionId}` pairs.
  Scores, counts, per-question correctness and gap classifications are shown exactly as the
  backend returns them, and the correct option is never shown.
- **Dashboard composition.** There is no `/api/dashboard` yet (section 8). The dashboard is
  assembled from `GET /api/auth/me`, `/api/attempts`, `/api/performance/gaps` and
  `/api/quizzes`. History and result pages also use `/api/quizzes` (or `/api/quizzes/{id}`)
  for quiz titles.
- **XSS.** All server-supplied strings are inserted with `textContent`, never `innerHTML`, and
  the CSP from section 2 blocks inline script.

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

## 8. API

All paths under `/api`, JSON bodies. "Auth" = requires bearer token.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/auth/register` | no | Register student (name, email, password) |
| POST | `/api/auth/login` | no | Returns token |
| POST | `/api/auth/logout` | yes | Invalidate token |
| GET | `/api/auth/me` | yes | Current student (section below) |
| GET | `/api/topics` | yes | List topics, ordered by name |
| POST | `/api/topics` | yes | Body `{name}` → 201; 409 on duplicate name (case-insensitive) |
| GET | `/api/quizzes` | yes | List quizzes |
| POST | `/api/quizzes` | yes | Body `{title, description?}` → 201; 409 on duplicate title |
| GET | `/api/quizzes/{id}` | yes | One quiz with its topics; 404 if unknown or non-numeric |
| POST | `/api/quizzes/{id}/questions` | yes | Body `{topicId, questionText, options: [{text, correct}]}` (2–6 options, exactly one correct) → 201; 404 unknown quiz, 400 unknown topic |
| GET | `/api/quizzes/{id}/questions` | yes | Questions + options, **no correctness data** |
| POST | `/api/quizzes/{id}/attempts` | yes | Body: `{"answers": [{questionId, selectedOptionId}]}` → 201 `{id, quizId, submittedAt, totalQuestions, answeredCount}`. Evaluated and stored server-side; no score or correctness in the response (implemented in Milestone 9) |
| GET | `/api/attempts` | yes | Current student's attempt history → 200 `[{id, quizId, submittedAt, totalQuestions, answeredCount, correctCount, scorePercent}]`, newest first (`submitted_at DESC, id DESC`), `[]` when none. Values are the snapshot stored at submission; one query (implemented in Milestone 11) |
| GET | `/api/attempts/{id}` | yes | One of the caller's own attempts → 200 `{id, quizId, submittedAt, totalQuestions, answeredCount, correctCount, scorePercent, answers: [{questionId, selectedOptionId, correct}]}`, read from the evaluation stored at submission (no correct option ids). Another student's attempt → 404, same as unknown (implemented in Milestone 10) |
| GET | `/api/performance/topics` | yes | **Planned, not implemented** (404). Topic-wise accuracy is returned by `/api/performance/gaps` |
| GET | `/api/performance/gaps` | yes | Every topic with the caller's cumulative accuracy and classification → 200 `[{topicId, topicName, totalQuestions, correctCount, accuracyPercent, classification}]`, ordered by topic name. `totalQuestions` counts each of the topic's questions in every attempt, unanswered included; `accuracyPercent` is null and `classification` is `No Data` when that is 0. Thresholds from `gap.threshold.*` (section 7); one query over stored `is_correct` values (implemented in Milestone 12) |
| GET | `/api/dashboard` | yes | **Planned, not implemented.** Aggregate: overall score, recent attempts, topics, gaps. The Milestone 13 dashboard page composes existing endpoints instead (section 4) |
| GET | `/api/health` | no | Liveness check |

Error format: `{ "error": "message" }` with 400 / 401 / 404 / 405 / 409 / 500. No endpoint returns 403:
another student's attempt is a 404, indistinguishable from an unknown one.
Request bodies must match the DTO exactly: unknown properties and wrong JSON types (for example a
number where a string is expected, `"5"` or `1.5` for an id) are 400, never coerced.
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

Delivered (from the git history):

| # | Milestone |
|---|---|
| 1 | Analysis, environment check, this architecture |
| 2 | Project skeleton, schema, seed data, DB connection, `/api/health` |
| 3 | Registration, login, sessions, auth filter |
| 4 | Topics, quizzes, questions (management + listing without answer key) |
| 5 | Repository/DAO audit, quiz-attempt persistence (transactional) |
| 6 | Service-layer audit, attempt validation |
| 7 | Authentication integration audit, cross-controller auth tests |
| 8 | Quiz management API audit, malformed-input hardening |
| 9 | Attempt submission with server-side evaluation |
| 10 | Attempt result retrieval with ownership checks |
| 11 | Attempt history |
| 12 | Topic gap analysis with configurable thresholds |
| 13 | Vanilla frontend, same-origin static file serving |
| 14 | Testing and validation pass |
| 15 | Final polish, README, demo readiness |

The original Milestone 1 plan is kept below for context. Its numbering differs from the
delivered milestones; the planned dashboard API (plan item 8) was not built, and the dashboard
page composes existing endpoints instead (section 4).

| # | Planned milestone | Outcome |
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
