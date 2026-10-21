package com.stellartrack.configuration.application;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.common.capability.Capability;
import com.stellartrack.common.capability.CapabilityRegistry;
import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.exception.BusinessRuleException;
import com.stellartrack.common.exception.ConflictException;
import com.stellartrack.common.exception.NotFoundException;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.configuration.domain.BotConfigurationSettings;
import com.stellartrack.configuration.domain.OperationalState;
import com.stellartrack.configuration.persistence.BotConfigurationEntity;
import com.stellartrack.configuration.persistence.BotConfigurationRepository;
import com.stellartrack.configuration.persistence.BotConfigurationVersionEntity;
import com.stellartrack.configuration.persistence.BotConfigurationVersionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Named configurations with immutable versions. Material edits require the configuration to be stopped;
 * a running configuration keeps executing its pinned {@code activeVersion}.
 */
@Service
public class ConfigurationService {

    /** A configuration version resolved for execution or display. */
    public record ResolvedConfiguration(UUID id, String name, int version, BotConfigurationSettings settings) {
    }

    private final BotConfigurationRepository configurations;
    private final BotConfigurationVersionRepository versions;
    private final CapabilityRegistry capabilities;
    private final AuditTrail auditTrail;
    private final JsonCodec json;
    private final Clock clock;

    public ConfigurationService(BotConfigurationRepository configurations, BotConfigurationVersionRepository versions,
            CapabilityRegistry capabilities, AuditTrail auditTrail, JsonCodec json, Clock clock) {
        this.configurations = configurations;
        this.versions = versions;
        this.capabilities = capabilities;
        this.auditTrail = auditTrail;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public BotConfigurationEntity create(String name, BotConfigurationSettings settings, String actor) {
        String trimmed = requireUniqueName(name);
        validateBusinessRules(settings);
        Instant now = clock.instant();
        BotConfigurationEntity entity = configurations.save(BotConfigurationEntity.create(trimmed, now));
        saveVersion(entity, 1, settings, "Created", actor, now);
        auditTrail.record(AuditRecord.admin(actor, AuditAction.CONFIGURATION_CREATED)
                .entity("bot_configuration", entity.getId()).configuration(entity.getId(), 1));
        return entity;
    }

    @Transactional
    public BotConfigurationEntity update(UUID id, String name, BotConfigurationSettings settings, String changeNote,
            long expectedRowVersion, String actor) {
        BotConfigurationEntity entity = require(id);
        if (entity.getRowVersion() != expectedRowVersion) {
            throw new ConflictException("CONCURRENT_MODIFICATION", "The configuration was changed by another operation");
        }
        entity.requireEditable();
        validateBusinessRules(settings);
        Instant now = clock.instant();
        if (name != null && !name.trim().equalsIgnoreCase(entity.getName())) {
            entity.rename(requireUniqueName(name), now);
        }
        int version = entity.nextVersion(now);
        saveVersion(entity, version, settings, changeNote, actor, now);
        auditTrail.record(AuditRecord.admin(actor, AuditAction.CONFIGURATION_VERSION_CREATED)
                .entity("bot_configuration", id).configuration(id, version)
                .details(Map.of("changeNote", changeNote == null ? "" : changeNote)));
        return entity;
    }

    @Transactional
    public BotConfigurationEntity duplicate(UUID id, String newName, String actor) {
        ResolvedConfiguration source = current(id);
        BotConfigurationEntity copy = create(newName, source.settings(), actor);
        auditTrail.record(AuditRecord.admin(actor, AuditAction.CONFIGURATION_DUPLICATED)
                .entity("bot_configuration", copy.getId())
                .details(Map.of("sourceConfigurationId", id.toString(), "sourceVersion", source.version())));
        return copy;
    }

    @Transactional
    public BotConfigurationEntity archive(UUID id, String actor) {
        BotConfigurationEntity entity = require(id);
        entity.archive(clock.instant());
        auditTrail.record(AuditRecord.admin(actor, AuditAction.CONFIGURATION_ARCHIVED).entity("bot_configuration", id));
        return entity;
    }

    @Transactional
    public void transition(UUID id, OperationalState target, String reason) {
        require(id).transition(target, reason, clock.instant());
    }

    @Transactional(readOnly = true)
    public BotConfigurationEntity require(UUID id) {
        return configurations.findById(id).orElseThrow(() -> new NotFoundException("Configuration", id));
    }

    @Transactional(readOnly = true)
    public List<BotConfigurationEntity> list(boolean includeArchived) {
        return includeArchived ? configurations.findAllByOrderByNameAsc() : configurations.findByArchivedAtIsNullOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public List<BotConfigurationVersionEntity> versions(UUID id) {
        require(id);
        return versions.findByConfigurationIdOrderByVersionDesc(id);
    }

    @Transactional(readOnly = true)
    public ResolvedConfiguration current(UUID id) {
        return resolve(id, require(id).getCurrentVersion());
    }

    @Transactional(readOnly = true)
    public ResolvedConfiguration resolve(UUID id, int version) {
        BotConfigurationEntity entity = require(id);
        BotConfigurationVersionEntity v = versions.findByConfigurationIdAndVersion(id, version)
                .orElseThrow(() -> new NotFoundException("Configuration version", id + "@" + version));
        return new ResolvedConfiguration(id, entity.getName(), version, json.read(v.getSettings(), BotConfigurationSettings.class));
    }

    @Transactional(readOnly = true)
    public List<BotConfigurationEntity> inStates(List<OperationalState> states) {
        return configurations.findByOperationalStateIn(states);
    }

    @Transactional(readOnly = true)
    public long countRunning() {
        return configurations.countByOperationalState(OperationalState.RUNNING);
    }

    /** Rules that cross field boundaries or depend on capability status. */
    public void validateBusinessRules(BotConfigurationSettings settings) {
        if (settings.staking().method().layOnly() && settings.market().side() != BetSide.LAY) {
            throw new BusinessRuleException("STAKING_SIDE_MISMATCH",
                    settings.staking().method() + " staking applies only to LAY configurations");
        }
        if (settings.market().marketType() == com.stellartrack.common.domain.MarketType.EACH_WAY
                && settings.market().side() != BetSide.BACK) {
            throw new BusinessRuleException("EACH_WAY_BACK_ONLY", "Each-Way configurations can only place Back bets");
        }
        BotConfigurationSettings.Trading trading = settings.trading();
        if (trading.dutching()) {
            capabilities.require(Capability.DUTCHING);
        }
        if (trading.priceMovementDetection()) {
            capabilities.require(Capability.PRICE_MOVEMENT_DETECTION);
        }
        if (trading.equalisation()) {
            capabilities.require(Capability.EQUALISATION);
        }
        if (trading.closeOut()) {
            capabilities.require(Capability.CLOSE_OUT);
        }
        if (trading.freeBetPositioning()) {
            capabilities.require(Capability.FREE_BET_POSITIONING);
        }
    }

    private String requireUniqueName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 120) {
            throw new BusinessRuleException("NAME_INVALID", "Name is required (max 120 characters)");
        }
        String trimmed = name.trim();
        if (configurations.existsByNameIgnoreCaseAndArchivedAtIsNull(trimmed)) {
            throw new ConflictException("NAME_EXISTS", "A configuration with this name already exists");
        }
        return trimmed;
    }

    private void saveVersion(BotConfigurationEntity entity, int version, BotConfigurationSettings settings, String note,
            String actor, Instant now) {
        versions.save(new BotConfigurationVersionEntity(entity.getId(), version, BotConfigurationSettings.SCHEMA_VERSION,
                json.write(settings), note, actor, now));
    }
}
