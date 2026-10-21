package com.stellartrack.staking.application;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.exception.BusinessRuleException;
import com.stellartrack.common.exception.ConflictException;
import com.stellartrack.common.exception.NotFoundException;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.common.util.Hashing;
import com.stellartrack.staking.domain.StakeCalculation;
import com.stellartrack.staking.domain.StakeCalculatorChain;
import com.stellartrack.staking.domain.StakeContext;
import com.stellartrack.staking.domain.StakingMethodType;
import com.stellartrack.staking.domain.StakingPlanStep;
import com.stellartrack.staking.domain.StakingSettings;
import com.stellartrack.staking.persistence.StakingPlanEntity;
import com.stellartrack.staking.persistence.StakingPlanRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/** Authoritative stake calculation and staking-plan management. */
@Service
public class StakingService {

    /** Outcome of a settled entry in a configuration's sequence, used to advance staking plans. */
    public enum SequenceOutcome {
        WIN,
        LOSS;

        /** Maps settled-profit signs (+1 win, −1 loss) in settlement order to a staking sequence. */
        public static List<SequenceOutcome> fromSigns(List<Integer> signs) {
            return signs.stream().map(sign -> sign > 0 ? WIN : LOSS).toList();
        }
    }

    private final StakeCalculatorChain chain = StakeCalculatorChain.standard();
    private final StakingPlanRepository plans;
    private final BankResolver bankResolver;
    private final AuditTrail auditTrail;
    private final JsonCodec json;
    private final Clock clock;
    private final BigDecimal exchangeMinimumStake;

    public StakingService(StakingPlanRepository plans, BankResolver bankResolver, AuditTrail auditTrail, JsonCodec json,
            Clock clock, @Value("${stellartrack.betfair.minimum-stake}") BigDecimal exchangeMinimumStake) {
        this.plans = plans;
        this.bankResolver = bankResolver;
        this.auditTrail = auditTrail;
        this.json = json;
        this.clock = clock;
        this.exchangeMinimumStake = exchangeMinimumStake;
    }

    /**
     * @param allocatedCapital the configuration's allocation (ALLOCATED_CAPITAL basis), may be null
     * @param sequence         settled WIN/LOSS outcomes of the configuration in settlement order (STAKING_PLAN only)
     */
    @Transactional(readOnly = true)
    public StakeCalculation calculate(BetSide side, BigDecimal price, StakingSettings settings, BigDecimal allocatedCapital,
            List<SequenceOutcome> sequence) {
        BigDecimal bank = switch (settings.method()) {
            case PERCENT_OF_BANK, PERCENT_OF_BANK_LIABILITY -> bankResolver.resolve(settings, allocatedCapital);
            default -> null;
        };
        return calculateWithBank(side, price, settings, bank, sequence);
    }

    /**
     * Same calculation with the bank supplied by the caller instead of resolved from account funds. Used by Watch
     * Mode, whose percentage stakes are based on the virtual capital.
     *
     * @param bank the bank for PERCENT_OF_BANK methods (ignored otherwise); null when it cannot be determined
     */
    @Transactional(readOnly = true)
    public StakeCalculation calculateWithBank(BetSide side, BigDecimal price, StakingSettings settings, BigDecimal bank,
            List<SequenceOutcome> sequence) {
        StakingPlanStep step = settings.method() == StakingMethodType.STAKING_PLAN
                ? currentStep(settings.stakingPlanId(), sequence == null ? List.of() : sequence)
                : null;
        return chain.calculate(new StakeContext(side, price, settings, bank, step, exchangeMinimumStake));
    }

    @Transactional(readOnly = true)
    public StakingPlanStep currentStep(UUID planId, List<SequenceOutcome> sequence) {
        List<StakingPlanStep> steps = steps(planId);
        int current = 1;
        for (SequenceOutcome outcome : sequence) {
            StakingPlanStep step = steps.get(current - 1);
            Integer next = outcome == SequenceOutcome.WIN ? step.onWin() : step.onLoss();
            current = next == null ? 1 : next;
        }
        return steps.get(current - 1);
    }

    @Transactional(readOnly = true)
    public List<StakingPlanStep> steps(UUID planId) {
        return json.read(getPlan(planId).getSteps(), new TypeReference<List<StakingPlanStep>>() { });
    }

    @Transactional(readOnly = true)
    public StakingPlanEntity getPlan(UUID planId) {
        return plans.findById(planId).orElseThrow(() -> new NotFoundException("Staking plan", planId));
    }

    @Transactional(readOnly = true)
    public List<StakingPlanEntity> listPlans() {
        return plans.findAll();
    }

    @Transactional
    public StakingPlanEntity importPlan(String name, String filename, byte[] content, String actorId) {
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new BusinessRuleException("PLAN_NAME_INVALID", "Plan name is required (max 120 characters)");
        }
        if (plans.existsByName(name.trim())) {
            throw new ConflictException("PLAN_NAME_EXISTS", "A staking plan with this name already exists");
        }
        StakingPlanParser.ParseResult result = StakingPlanParser.parse(filename, content);
        if (!result.valid()) {
            throw new BusinessRuleException("PLAN_INVALID", "Staking plan failed validation",
                    Map.of("errors", result.errors()));
        }
        StakingPlanEntity entity = plans.save(new StakingPlanEntity(UUID.randomUUID(), name.trim(),
                safeFilename(filename), Hashing.sha256Hex(content), StakingPlanParser.SCHEMA_VERSION,
                json.write(result.steps()), result.steps().size(), actorId, clock.instant()));
        auditTrail.record(AuditRecord.admin(actorId, AuditAction.STAKING_PLAN_IMPORTED).entity("staking_plan", entity.getId())
                .details(Map.of("steps", result.steps().size(), "sha256", entity.getSourceSha256())));
        return entity;
    }

    private static String safeFilename(String filename) {
        String base = filename == null ? "plan" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
        return base.length() > 255 ? base.substring(0, 255) : base;
    }
}
