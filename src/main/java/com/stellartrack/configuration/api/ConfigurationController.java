package com.stellartrack.configuration.api;

import com.stellartrack.auth.security.AdminPrincipal;
import com.stellartrack.common.api.ApiPaths;
import com.stellartrack.configuration.application.ConfigurationService;
import com.stellartrack.configuration.domain.BotConfigurationSettings;
import com.stellartrack.configuration.domain.OperationalState;
import com.stellartrack.configuration.persistence.BotConfigurationEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.CONFIGURATIONS)
@Tag(name = "Bot configurations")
public class ConfigurationController {

    public record CreateRequest(@NotBlank @Size(max = 120) String name, @NotNull @Valid BotConfigurationSettings settings) {
    }

    public record UpdateRequest(@Size(max = 120) String name, @NotNull @Valid BotConfigurationSettings settings,
            @Size(max = 500) String changeNote, long rowVersion) {
    }

    public record DuplicateRequest(@NotBlank @Size(max = 120) String name) {
    }

    public record ConfigurationSummary(UUID id, String name, OperationalState operationalState, String stateReason,
            int currentVersion, Integer activeVersion, Instant archivedAt, Instant updatedAt, long rowVersion) {

        static ConfigurationSummary from(BotConfigurationEntity e) {
            return new ConfigurationSummary(e.getId(), e.getName(), e.getOperationalState(), e.getStateReason(),
                    e.getCurrentVersion(), e.getActiveVersion(), e.getArchivedAt(), e.getUpdatedAt(), e.getRowVersion());
        }
    }

    public record ConfigurationDetail(ConfigurationSummary summary, BotConfigurationSettings settings) {
    }

    public record VersionSummary(int version, String changeNote, String createdBy, Instant createdAt) {
    }

    private final ConfigurationService service;

    public ConfigurationController(ConfigurationService service) {
        this.service = service;
    }

    @GetMapping
    public List<ConfigurationSummary> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(includeArchived).stream().map(ConfigurationSummary::from).toList();
    }

    @GetMapping("/{id}")
    public ConfigurationDetail get(@PathVariable UUID id) {
        return new ConfigurationDetail(ConfigurationSummary.from(service.require(id)), service.current(id).settings());
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "Immutable version history")
    public List<VersionSummary> versions(@PathVariable UUID id) {
        return service.versions(id).stream()
                .map(v -> new VersionSummary(v.getVersion(), v.getChangeNote(), v.getCreatedBy(), v.getCreatedAt()))
                .toList();
    }

    @GetMapping("/{id}/versions/{version}")
    public BotConfigurationSettings version(@PathVariable UUID id, @PathVariable int version) {
        return service.resolve(id, version).settings();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConfigurationSummary create(@Valid @RequestBody CreateRequest request) {
        return ConfigurationSummary.from(service.create(request.name(), request.settings(), actor()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Create a new version (configuration must be stopped)")
    public ConfigurationSummary update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return ConfigurationSummary.from(service.update(id, request.name(), request.settings(), request.changeNote(),
                request.rowVersion(), actor()));
    }

    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public ConfigurationSummary duplicate(@PathVariable UUID id, @Valid @RequestBody DuplicateRequest request) {
        return ConfigurationSummary.from(service.duplicate(id, request.name(), actor()));
    }

    @PostMapping("/{id}/archive")
    public ConfigurationSummary archive(@PathVariable UUID id) {
        return ConfigurationSummary.from(service.archive(id, actor()));
    }

    private static String actor() {
        return AdminPrincipal.current().actorId();
    }
}
