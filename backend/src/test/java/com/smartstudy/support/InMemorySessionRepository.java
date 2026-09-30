package com.smartstudy.support;

import com.smartstudy.model.AuthSession;
import com.smartstudy.repository.SessionRepository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemorySessionRepository implements SessionRepository {

    private record Row(AuthSession session, String tokenHash) {
    }

    private final Map<Long, Row> rows = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public void create(long studentId, String tokenHash, Instant expiresAt) {
        long id = ids.incrementAndGet();
        rows.put(id, new Row(new AuthSession(id, studentId, expiresAt), tokenHash));
    }

    @Override
    public Optional<AuthSession> findByTokenHash(String tokenHash) {
        return rows.values().stream().filter(r -> r.tokenHash().equals(tokenHash)).map(Row::session).findFirst();
    }

    @Override
    public void deleteById(long sessionId) {
        rows.remove(sessionId);
    }

    public int count() {
        return rows.size();
    }

    /** Everything the "database" holds in the token_hash column, for inspection by tests. */
    public java.util.List<String> storedTokenHashes() {
        return rows.values().stream().map(Row::tokenHash).toList();
    }
}
