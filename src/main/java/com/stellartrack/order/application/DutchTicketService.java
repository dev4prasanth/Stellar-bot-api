package com.stellartrack.order.application;

import com.stellartrack.capital.application.CapitalService;
import com.stellartrack.common.api.Freshness;
import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.common.exception.BusinessRuleException;
import com.stellartrack.market.application.MarketQueryService;
import com.stellartrack.market.application.MarketQueryService.MarketDetail;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.filter.RunnerFilterCriteria;
import com.stellartrack.order.application.ManualTicketService.ConfirmRequest;
import com.stellartrack.order.application.ManualTicketService.TicketRequest;
import com.stellartrack.order.application.ManualTicketService.TicketReview;
import com.stellartrack.order.persistence.BetOrderEntity;
import com.stellartrack.order.persistence.BetOrderRepository;
import com.stellartrack.order.persistence.OrderIntentEntity;
import com.stellartrack.order.persistence.OrderIntentRepository;
import com.stellartrack.order.port.CancellationResult;
import com.stellartrack.order.port.PersistenceType;
import com.stellartrack.staking.domain.DutchMath;
import com.stellartrack.staking.domain.StakingMethodType;
import com.stellartrack.staking.domain.StakingSettings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Back Dutch bets from Trading → Race markets: several runners of one WIN market backed together so the profit is the
 * same whichever of them wins ({@link DutchMath}). Every leg is reviewed and placed through the single-ticket path, so
 * all pre-trade checks apply to each leg; the group is checked as a whole too (minimum stake per leg, a profit being
 * possible, funds for the total). Nothing is placed unless every leg passes review. Legs share a Dutch group id; if a
 * leg is not placed or not fully matched, the others stand and the unmatched remainder can be revoked for the group.
 */
@Service
public class DutchTicketService {

    /** One runner of the Dutch; {@code price} null means the best back price now. */
    public record DutchSelection(long selectionId, BigDecimal price) {
    }

    /** Exactly one of {@code totalStake} and {@code targetProfit}. */
    public record DutchRequest(String marketId, List<DutchSelection> selections, BigDecimal totalStake,
            BigDecimal targetProfit, PersistenceType persistenceType) {
    }

    /** @param profitIfWins gross profit of the whole Dutch if this runner wins */
    public record DutchLeg(long selectionId, String runnerName, BigDecimal price, BigDecimal stake, BigDecimal profitIfWins,
            TicketReview review) {
    }

    /**
     * @param impliedPercent combined chance of the selections at these odds; a profit needs it below 100
     * @param problems       group-level reasons it cannot be placed (each leg's own checks are in its review)
     * @param placeable      every leg passed review and the group has no problem
     */
    public record DutchReview(String marketId, String marketName, String venue, Instant startTime, Freshness freshness,
            List<DutchLeg> legs, BigDecimal totalStake, BigDecimal impliedPercent, BigDecimal minProfit,
            BigDecimal maxProfit, List<String> problems, boolean placeable) {
    }

    public record LegConfirm(String reviewToken, String idempotencyKey) {
    }

    public record DutchConfirm(List<LegConfirm> legs, boolean acceptDelayedData) {
    }

    public record LegOutcome(long selectionId, PlacementOutcome outcome) {
    }

    /** @param complete every leg was placed */
    public record DutchOutcome(UUID groupId, List<LegOutcome> legs, boolean complete, String message) {
    }

    public record LegRevoke(String betId, long selectionId, String outcome, String message) {
    }

    private final ManualTicketService tickets;
    private final MarketQueryService marketQuery;
    private final ReviewTokenSigner signer;
    private final CapitalService capital;
    private final OrderIntentRepository intents;
    private final BetOrderRepository betOrders;
    private final OrderCancellationService cancellation;
    private final BigDecimal exchangeMinimumStake;

    public DutchTicketService(ManualTicketService tickets, MarketQueryService marketQuery, ReviewTokenSigner signer,
            CapitalService capital, OrderIntentRepository intents, BetOrderRepository betOrders,
            OrderCancellationService cancellation,
            @Value("${stellartrack.betfair.minimum-stake}") BigDecimal exchangeMinimumStake) {
        this.tickets = tickets;
        this.marketQuery = marketQuery;
        this.signer = signer;
        this.capital = capital;
        this.intents = intents;
        this.betOrders = betOrders;
        this.cancellation = cancellation;
        this.exchangeMinimumStake = exchangeMinimumStake;
    }

    public DutchReview review(DutchRequest request, String actor) {
        if ((request.totalStake() == null) == (request.targetProfit() == null)) {
            throw new BusinessRuleException("DUTCH_AMOUNT_REQUIRED", "Enter either a total stake or a target profit");
        }
        List<DutchSelection> selections = request.selections() == null ? List.of() : request.selections();
        if (selections.size() < 2) {
            throw new BusinessRuleException("DUTCH_SELECTIONS", "Choose at least two runners to Dutch");
        }
        if (new HashSet<>(selections.stream().map(DutchSelection::selectionId).toList()).size() != selections.size()) {
            throw new BusinessRuleException("DUTCH_SELECTIONS", "Each runner can be chosen only once");
        }
        MarketDetail detail = marketQuery.detail(request.marketId(), BetSide.BACK, RunnerFilterCriteria.none());
        if (detail.market().marketType() != MarketType.WIN) {
            throw new BusinessRuleException("DUTCH_WIN_ONLY", "Dutch bets are available on Win markets only");
        }
        List<BigDecimal> prices = new ArrayList<>();
        for (DutchSelection s : selections) {
            BigDecimal price = s.price() != null ? s.price() : bestBack(detail, s.selectionId());
            if (price == null) {
                throw new BusinessRuleException("NO_PRICE", runnerName(detail, s.selectionId())
                        + " has no back price available; enter a price");
            }
            prices.add(price);
        }
        List<String> problems = new ArrayList<>();
        BigDecimal book = DutchMath.impliedProbability(prices);
        if (book.compareTo(BigDecimal.ONE) >= 0) {
            problems.add("These runners cannot return a profit together: their combined chance at these odds is "
                    + percent(book) + "%, and it must be below 100%.");
        }
        BigDecimal total;
        try {
            total = request.totalStake() != null ? request.totalStake()
                    : DutchMath.totalForTargetProfit(request.targetProfit(), prices);
        } catch (IllegalArgumentException e) {
            total = BigDecimal.ZERO;
        }
        List<BigDecimal> stakes = total.signum() > 0 ? DutchMath.backStakes(total, prices)
                : prices.stream().map(p -> BigDecimal.ZERO).toList();
        BigDecimal staked = stakes.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> belowMinimum = new ArrayList<>();
        for (int i = 0; i < selections.size(); i++) {
            if (stakes.get(i).compareTo(exchangeMinimumStake) < 0) {
                belowMinimum.add(runnerName(detail, selections.get(i).selectionId()) + " (" + stakes.get(i).toPlainString() + ")");
            }
        }
        if (!belowMinimum.isEmpty() && staked.signum() > 0) {
            problems.add("The stake for " + String.join(", ", belowMinimum) + " is below Betfair's minimum stake of "
                    + exchangeMinimumStake.toPlainString() + ". Increase the total stake or choose fewer runners.");
        }
        capital.latest().ifPresent(funds -> {
            if (funds.availableToBet() != null && funds.availableToBet().compareTo(staked) < 0) {
                problems.add("The total stake " + staked.toPlainString() + " is more than the available balance "
                        + funds.availableToBet().toPlainString() + ".");
            }
        });
        List<DutchLeg> legs = new ArrayList<>();
        PersistenceType persistence = request.persistenceType() != null ? request.persistenceType() : PersistenceType.LAPSE;
        for (int i = 0; i < selections.size(); i++) {
            long selectionId = selections.get(i).selectionId();
            BigDecimal stake = stakes.get(i);
            TicketReview review = stake.signum() > 0 && stake.compareTo(exchangeMinimumStake) >= 0
                    ? tickets.review(new TicketRequest(request.marketId(), selectionId, BetSide.BACK, prices.get(i), null,
                            fixedStake(stake), persistence), actor)
                    : null;
            legs.add(new DutchLeg(selectionId, runnerName(detail, selectionId), prices.get(i), stake,
                    DutchMath.profitIfWins(stake, prices.get(i), staked), review));
        }
        boolean legsOk = legs.stream().allMatch(l -> l.review() != null && l.review().reviewToken() != null
                && l.review().violations().stream().allMatch(v -> v.requiresReconfirmation()));
        BigDecimal min = legs.stream().map(DutchLeg::profitIfWins).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal max = legs.stream().map(DutchLeg::profitIfWins).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        return new DutchReview(request.marketId(), detail.market().marketName(), detail.market().venue(),
                detail.market().startTime(), detail.freshness(), List.copyOf(legs), staked, percent(book), min, max,
                List.copyOf(problems), problems.isEmpty() && legsOk);
    }

    /**
     * Places every reviewed leg under one Dutch group. The group id derives from the legs' idempotency keys, so a
     * retried request maps to the same group and never places a leg twice.
     */
    public DutchOutcome place(DutchConfirm request, String actor) {
        List<LegConfirm> legs = request.legs() == null ? List.of() : request.legs();
        if (legs.size() < 2) {
            throw new BusinessRuleException("DUTCH_SELECTIONS", "A Dutch bet needs at least two reviewed runners");
        }
        List<ReviewTokenSigner.ReviewedTerms> terms = legs.stream().map(l -> signer.verify(l.reviewToken())).toList();
        if (terms.stream().map(ReviewTokenSigner.ReviewedTerms::marketId).distinct().count() != 1
                || terms.stream().anyMatch(t -> !BetSide.BACK.name().equals(t.side()))) {
            throw new BusinessRuleException("DUTCH_SELECTIONS", "All runners of a Dutch bet must be Back bets in one market");
        }
        UUID groupId = UUID.nameUUIDFromBytes(("dutch:" + String.join("|", legs.stream().map(LegConfirm::idempotencyKey)
                .toList())).getBytes(StandardCharsets.UTF_8));
        List<LegOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            PlacementOutcome outcome = tickets.confirm(new ConfirmRequest(legs.get(i).reviewToken(),
                    legs.get(i).idempotencyKey(), request.acceptDelayedData(), fixedStake(terms.get(i).stake()), groupId), actor);
            outcomes.add(new LegOutcome(terms.get(i).selectionId(), outcome));
        }
        long placed = outcomes.stream().filter(o -> o.outcome().status() == PlacementOutcome.Status.PLACED).count();
        boolean complete = placed == legs.size();
        String message = complete ? "All " + placed + " runners were placed."
                : placed + " of " + legs.size() + " runners were placed. The Dutch is incomplete, so the profit is no longer"
                        + " equal across runners; review the placed bets and revoke any unmatched stake if needed.";
        return new DutchOutcome(groupId, List.copyOf(outcomes), complete, message);
    }

    /** Revokes the unmatched stake of every leg of a Dutch bet; each leg is re-checked with Betfair first. */
    public List<LegRevoke> revokeUnmatched(UUID groupId, String actor) {
        List<OrderIntentEntity> group = intents.findByDutchGroupId(groupId);
        if (group.isEmpty()) {
            throw new BusinessRuleException("DUTCH_NOT_FOUND", "No Dutch bet with this id");
        }
        List<LegRevoke> results = new ArrayList<>();
        for (OrderIntentEntity intent : group) {
            BetOrderEntity order = betOrders.findByIntentId(intent.getId()).orElse(null);
            if (order == null || !BetOrderEntity.EXECUTABLE.equals(order.getStatus()) || order.getSizeRemaining().signum() <= 0) {
                continue;
            }
            try {
                CancellationResult result = cancellation.cancel(order.getBetId(), null, actor);
                results.add(new LegRevoke(order.getBetId(), intent.getSelectionId(), result.outcome().name(), null));
            } catch (BusinessRuleException e) {
                results.add(new LegRevoke(order.getBetId(), intent.getSelectionId(), "REFUSED", e.getMessage()));
            }
        }
        return results;
    }

    private static StakingSettings fixedStake(BigDecimal stake) {
        return new StakingSettings(StakingMethodType.FIXED_STAKE, stake, null, null, null, null, null, null);
    }

    private static BigDecimal bestBack(MarketDetail detail, long selectionId) {
        return detail.prices() == null ? null : detail.prices().runner(selectionId)
                .flatMap(MarketPrices.RunnerPrices::bestBack).map(MarketPrices.PriceLevel::price).orElse(null);
    }

    private static String runnerName(MarketDetail detail, long selectionId) {
        return detail.market().runners().stream().filter(r -> r.selectionId() == selectionId).findFirst()
                .map(r -> r.name()).orElse(String.valueOf(selectionId));
    }

    private static BigDecimal percent(BigDecimal book) {
        return book.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_EVEN);
    }
}
