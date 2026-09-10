package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentPersistenceException;

/** Uses the same time authority as database-owned intent creation timestamps. */
public final class PostgreSqlStaffProvisioningClock extends Clock {
    private final JdbcTemplate jdbc;
    private final ZoneId zone;

    public PostgreSqlStaffProvisioningClock(JdbcTemplate jdbc) {
        this(jdbc, ZoneOffset.UTC);
    }

    private PostgreSqlStaffProvisioningClock(JdbcTemplate jdbc, ZoneId zone) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    @Override public ZoneId getZone() { return zone; }

    @Override public Clock withZone(ZoneId zone) {
        return new PostgreSqlStaffProvisioningClock(jdbc, zone);
    }

    /** Reads time only, never an intent. The terminal mutation remains UPDATE RETURNING. */
    @Override public Instant instant() {
        try {
            return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class)).toInstant();
        } catch (DataAccessException exception) {
            throw new StaffProvisioningIntentPersistenceException("Staff provisioning time is unavailable", exception);
        }
    }
}
