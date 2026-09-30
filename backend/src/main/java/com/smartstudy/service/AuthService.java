package com.smartstudy.service;

import com.smartstudy.dto.AuthenticatedUser;
import com.smartstudy.dto.LoginRequest;
import com.smartstudy.dto.LoginResponse;
import com.smartstudy.dto.RegisterRequest;
import com.smartstudy.dto.StudentResponse;
import com.smartstudy.model.AuthSession;
import com.smartstudy.model.Student;
import com.smartstudy.repository.SessionRepository;
import com.smartstudy.repository.StudentRepository;
import com.smartstudy.util.AuthenticationException;
import com.smartstudy.util.PasswordHasher;
import com.smartstudy.util.TokenGenerator;
import com.smartstudy.util.ValidationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Registration, login, session validation and logout. */
public class AuthService {

    static final String INVALID_CREDENTIALS = "Invalid email or password";

    static final int NAME_MAX = 100;
    static final int EMAIL_MAX = 255;
    static final int PASSWORD_MIN = 8;
    static final int PASSWORD_MAX = 128;

    // Linear-time patterns (no nested quantifiers).
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final StudentRepository students;
    private final SessionRepository sessions;
    private final PasswordHasher hasher;
    private final TokenGenerator tokens;
    private final Clock clock;
    private final Duration sessionTtl;
    /** Verified against when the email is unknown, so unknown and wrong-password logins cost the same. */
    private final PasswordHasher.Hashed dummyCredentials;

    public AuthService(StudentRepository students, SessionRepository sessions, PasswordHasher hasher,
                       TokenGenerator tokens, Clock clock, Duration sessionTtl) {
        this.students = students;
        this.sessions = sessions;
        this.hasher = hasher;
        this.tokens = tokens;
        this.clock = clock;
        this.sessionTtl = sessionTtl;
        this.dummyCredentials = hasher.hash(tokens.generate());
    }

    public StudentResponse register(RegisterRequest request) {
        String name = request.name() == null ? null : request.name().trim();
        String email = normaliseEmail(request.email());
        String password = request.password();

        Map<String, String> errors = new LinkedHashMap<>();
        validateName(name, errors);
        validateEmail(email, errors);
        validatePassword(password, errors);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        PasswordHasher.Hashed hashed = hasher.hash(password);
        Student student = students.create(name, email, hashed.hash(), hashed.salt());
        return toResponse(student);
    }

    public LoginResponse login(LoginRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.email() == null || request.email().isBlank()) {
            errors.put("email", "Email is required");
        }
        if (request.password() == null || request.password().isEmpty()) {
            errors.put("password", "Password is required");
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        Optional<Student> found = students.findByEmail(normaliseEmail(request.email()));
        String hash = found.map(Student::passwordHash).orElse(dummyCredentials.hash());
        String salt = found.map(Student::passwordSalt).orElse(dummyCredentials.salt());
        boolean passwordOk = hasher.verify(request.password(), hash, salt);
        if (found.isEmpty() || !passwordOk) {
            // Same message and work for "no such email" and "wrong password".
            throw new AuthenticationException(INVALID_CREDENTIALS);
        }

        Student student = found.get();
        String rawToken = tokens.generate();
        // Whole seconds: that is all the TIMESTAMP column keeps, so DB and response agree.
        Instant expiresAt = clock.instant().plus(sessionTtl).truncatedTo(ChronoUnit.SECONDS);
        sessions.create(student.id(), TokenGenerator.hash(rawToken), expiresAt);
        return new LoginResponse(rawToken, "Bearer", expiresAt.toString(), toResponse(student));
    }

    /**
     * Resolves a raw bearer token to the calling student, or empty if the token is unknown,
     * expired or revoked. An expired session found here is deleted.
     */
    public Optional<AuthenticatedUser> authenticate(String rawToken) {
        Optional<AuthSession> found = sessions.findByTokenHash(TokenGenerator.hash(rawToken));
        if (found.isEmpty()) {
            return Optional.empty(); // never issued, or revoked (deleted) by logout
        }
        AuthSession session = found.get();
        if (!session.expiresAt().isAfter(clock.instant())) {
            sessions.deleteById(session.id());
            return Optional.empty();
        }
        return students.findById(session.studentId())
                .map(s -> new AuthenticatedUser(s.id(), s.name(), s.email(), session.id()));
    }

    /** Revokes the given session; its token stops authenticating immediately. */
    public void logout(long sessionId) {
        sessions.deleteById(sessionId);
    }

    private static StudentResponse toResponse(Student s) {
        return new StudentResponse(s.id(), s.name(), s.email());
    }

    private static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static void validateName(String name, Map<String, String> errors) {
        if (name == null || name.isEmpty()) {
            errors.put("name", "Name is required");
        } else if (name.length() > NAME_MAX) {
            errors.put("name", "Name must be at most " + NAME_MAX + " characters");
        } else if (name.codePoints().anyMatch(Character::isISOControl)) {
            errors.put("name", "Name contains invalid characters");
        }
    }

    private static void validateEmail(String email, Map<String, String> errors) {
        if (email == null || email.isEmpty()) {
            errors.put("email", "Email is required");
        } else if (email.length() > EMAIL_MAX || !EMAIL.matcher(email).matches()) {
            errors.put("email", "Email address is not valid");
        }
    }

    private static void validatePassword(String password, Map<String, String> errors) {
        if (password == null || password.isEmpty()) {
            errors.put("password", "Password is required");
        } else if (password.length() < PASSWORD_MIN) {
            errors.put("password", "Password must be at least " + PASSWORD_MIN + " characters");
        } else if (password.length() > PASSWORD_MAX) {
            errors.put("password", "Password must be at most " + PASSWORD_MAX + " characters");
        } else if (password.chars().noneMatch(Character::isLetter) || password.chars().noneMatch(Character::isDigit)) {
            errors.put("password", "Password must contain at least one letter and one digit");
        }
    }
}
