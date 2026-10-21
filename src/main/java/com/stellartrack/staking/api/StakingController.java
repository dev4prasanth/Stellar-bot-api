package com.stellartrack.staking.api;

import com.stellartrack.auth.security.AdminPrincipal;
import com.stellartrack.common.api.ApiPaths;
import com.stellartrack.common.domain.BetSide;
import com.stellartrack.staking.application.StakingService;
import com.stellartrack.staking.domain.StakeCalculation;
import com.stellartrack.staking.domain.StakingPlanStep;
import com.stellartrack.staking.domain.StakingSettings;
import com.stellartrack.staking.persistence.StakingPlanEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(ApiPaths.STAKING)
@Tag(name = "Staking")
public class StakingController {

    public record PreviewRequest(@NotNull BetSide side, @NotNull @DecimalMin("1.01") BigDecimal price,
            @NotNull @Valid StakingSettings settings, BigDecimal allocatedCapital) {
    }

    public record PlanResponse(UUID id, String name, String sourceFilename, String sourceSha256, int schemaVersion,
            int stepCount, String createdBy, Instant createdAt, List<StakingPlanStep> steps) {
    }

    private final StakingService stakingService;

    public StakingController(StakingService stakingService) {
        this.stakingService = stakingService;
    }

    @PostMapping("/preview")
    @Operation(summary = "Authoritative stake, liability and estimated profit calculation (the UI preview uses this)")
    public StakeCalculation preview(@Valid @RequestBody PreviewRequest request) {
        return stakingService.calculate(request.side(), request.price(), request.settings(), request.allocatedCapital(),
                List.of());
    }

    @GetMapping("/plans")
    public List<PlanResponse> plans() {
        return stakingService.listPlans().stream().map(p -> toResponse(p, null)).toList();
    }

    @GetMapping("/plans/{id}")
    public PlanResponse plan(@PathVariable UUID id) {
        return toResponse(stakingService.getPlan(id), stakingService.steps(id));
    }

    @PostMapping(value = "/plans", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import a declarative CSV/XLSX staking plan (schema v1; formulas and macros are rejected)")
    public PlanResponse importPlan(@RequestParam String name, @RequestPart("file") MultipartFile file) throws IOException {
        StakingPlanEntity entity = stakingService.importPlan(name, file.getOriginalFilename(), file.getBytes(),
                AdminPrincipal.current().actorId());
        return toResponse(entity, stakingService.steps(entity.getId()));
    }

    private static PlanResponse toResponse(StakingPlanEntity p, List<StakingPlanStep> steps) {
        return new PlanResponse(p.getId(), p.getName(), p.getSourceFilename(), p.getSourceSha256(), p.getSchemaVersion(),
                p.getStepCount(), p.getCreatedBy(), p.getCreatedAt(), steps);
    }
}
