# Smart Study Gap Analyzer

## Project

Smart Study Gap Analyzer is a full-stack web application for analyzing
student quiz performance and identifying learning gaps.

## Technology

Backend:
- Java
- Maven
- REST-style HTTP APIs
- JDBC
- MySQL

Frontend:
- HTML5
- CSS3
- Vanilla JavaScript

Database:
- MySQL

## Architecture

Frontend
    ↓
Controller
    ↓
Service
    ↓
Repository / DAO
    ↓
JDBC
    ↓
MySQL

## Core Features

1. Student registration
2. Student login
3. Quiz listing
4. Quiz questions
5. Quiz submission
6. Automatic answer evaluation
7. Score calculation
8. Quiz attempt history
9. Topic-wise performance
10. Learning-gap identification
11. Performance dashboard

## Gap Analysis

Accuracy:

correct answers / total questions * 100

Initial classification:

>= 75% → Strong
50–74% → Moderate
< 50% → Needs Improvement

Thresholds must be configurable.

## Important Rules

- Do not use Spring Boot unless explicitly approved.
- Do not use React unless explicitly approved.
- Do not use Angular or Vue.
- Do not use MongoDB.
- Do not introduce AI/ML unless explicitly requested.
- Use MySQL.
- Use JDBC.
- Use prepared statements.
- Never store plaintext passwords.
- Do not trust frontend-provided correctness values.
- Keep business logic in the service layer.
- Keep database operations in repository/DAO classes.
- Keep controllers thin.
- Use DTOs where appropriate.
- Validate inputs.
- Do not hardcode database credentials.
- Do not over-engineer.
- Do not delete existing work without confirmation.

## Development Process

Build incrementally.

For each milestone:

1. Explain what is being built.
2. Inspect existing files.
3. Implement only the requested milestone.
4. Test it.
5. Fix errors.
6. Report files changed.
7. Report commands executed.
8. Report test results.
9. Suggest a Git commit.
10. Stop and wait for the next instruction.

Never claim something works without testing it.

## Error Handling

When an error occurs:

1. Identify the error.
2. Explain the likely cause.
3. Inspect relevant files.
4. Make the smallest appropriate fix.
5. Run the relevant test again.
6. Verify the result.

Do not randomly rewrite the project.

## Current Development Goal

Start with Milestone 1:

Project analysis + Linux environment check + architecture.

