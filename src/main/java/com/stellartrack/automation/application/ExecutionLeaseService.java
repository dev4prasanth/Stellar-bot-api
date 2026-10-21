package com.stellartrack.automation.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Database lease guaranteeing a single active automation worker. Acquisition is one atomic conditional UPDATE:
 * it succeeds only if this instance already owns the lease or the previous owner's lease has expired.
 */
@Service
public class ExecutionLeaseService {

    public static final String AUTOMATION_LEASE = "automation-worker";
    public static final String WATCH_LEASE = "watch-worker";

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ExecutionLeaseService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean tryAcquire(String name, String ownerId, Duration ttl) {
        Instant now = clock.instant();
        int updated = jdbc.update("""
                update execution_lease
                   set owner_id = :owner,
                       acquired_at = case when owner_id = :owner then acquired_at else :now end,
                       expires_at = :expires
                 where name = :name
                   and (owner_id = :owner or owner_id is null or expires_at is null or expires_at < :now)
                """, Map.of("owner", ownerId, "now", Timestamp.from(now), "expires", Timestamp.from(now.plus(ttl)),
                "name", name));
        return updated == 1;
    }

    public void release(String name, String ownerId) {
        jdbc.update("update execution_lease set owner_id = null, expires_at = null where name = :name and owner_id = :owner",
                Map.of("name", name, "owner", ownerId));
    }

    public Map<String, Object> describe(String name) {
        return jdbc.queryForMap("select name, owner_id, acquired_at, expires_at from execution_lease where name = :name",
                Map.of("name", name));
    }
}
