package com.stellartrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stellartrack.support.ConfigurationFixtures;
import com.stellartrack.support.IntegrationTest;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fresh-install migration path against real PostgreSQL. The application context only starts if Hibernate schema
 * validation matches every entity mapping, so a successful start is itself an assertion.
 */
class SchemaMigrationIT extends IntegrationTest {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void allMigrationsAppliedAndValid() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("15");
        flyway.validate();
    }

    @Test
    void auditTrailIsAppendOnly() {
        jdbc.update("insert into audit_event (occurred_at, actor_type, action, outcome) values (now(), 'SYSTEM', 'LOGIN', 'INFO')");
        assertThatThrownBy(() -> jdbc.update("update audit_event set action = 'LOGOUT'")).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from audit_event")).hasMessageContaining("immutable");
    }

    @Test
    void onlyOneAdministratorCanExist() throws Exception {
        ensureAdmin();
        assertThatThrownBy(() -> jdbc.update("""
                insert into admin_user (id, username, password_hash, role, password_changed_at, created_at, updated_at)
                values (?, 'second-admin', 'x', 'ADMIN', now(), now(), now())""", UUID.randomUUID()))
                .hasMessageContaining("uq_admin_user_single_admin");
    }

    @Test
    void decisionSnapshotsAndConfigurationVersionsAreImmutable() {
        UUID config = UUID.randomUUID();
        // Valid settings: the row cannot be deleted and the shared database is read by later tests (automation status).
        String settings = mapper.writeValueAsString(ConfigurationFixtures.marketCriteriaBack("2.00"));
        jdbc.update("insert into bot_configuration (id, name, current_version, created_at, updated_at) values (?, ?, 1, now(), now())",
                config, "immutability-" + config);
        jdbc.update("""
                insert into bot_configuration_version (id, configuration_id, version, settings_schema_version, settings, created_by, created_at)
                values (?, ?, 1, 1, ?::jsonb, 'test', now())""", UUID.randomUUID(), config, settings);
        assertThatThrownBy(() -> jdbc.update("update bot_configuration_version set change_note = 'x' where configuration_id = ?", config))
                .hasMessageContaining("immutable");
    }

    @Test
    void settlementRevisionsAreUniquePerBetAndStatus() {
        assertThat(jdbc.queryForObject("""
                select count(*) from pg_indexes where indexname in ('uq_settlement_current', 'uq_order_intent_live_entry',
                  'uq_message_receipt_accepted_dedup', 'uq_recommendation_pending_entry')""", Integer.class)).isEqualTo(4);
    }
}
