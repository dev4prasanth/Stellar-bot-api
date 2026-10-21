package com.stellartrack.decision.application;

import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.decision.domain.OutcomeClassifier;
import com.stellartrack.decision.domain.RecommendationStatus;
import com.stellartrack.decision.persistence.DecisionOutcomeEvaluationEntity;
import com.stellartrack.decision.persistence.DecisionOutcomeEvaluationRepository;
import com.stellartrack.decision.persistence.RecommendationEntity;
import com.stellartrack.decision.persistence.RecommendationRepository;
import com.stellartrack.market.application.MarketResultService;
import com.stellartrack.market.domain.MarketResult;
import com.stellartrack.market.domain.MarketResultCapturedEvent;
import java.time.Clock;
import java.util.Optional;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Correlates every recommendation of a market with the official result once it is captured, writing a
 * separate post-outcome record (decision snapshots are never touched). Idempotent per rule version, so
 * re-running after a correction or replay never duplicates rows. No order is ever placed here.
 */
@Service
public class OutcomeEvaluationService {

    private final RecommendationRepository recommendations;
    private final DecisionOutcomeEvaluationRepository evaluations;
    private final MarketResultService marketResults;
    private final JsonCodec json;
    private final Clock clock;

    public OutcomeEvaluationService(RecommendationRepository recommendations,
            DecisionOutcomeEvaluationRepository evaluations, MarketResultService marketResults, JsonCodec json,
            Clock clock) {
        this.recommendations = recommendations;
        this.evaluations = evaluations;
        this.marketResults = marketResults;
        this.json = json;
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onMarketResult(MarketResultCapturedEvent event) {
        evaluateMarket(event.marketId());
    }

    @Transactional
    public int evaluateMarket(String marketId) {
        Optional<MarketResult> result = marketResults.result(marketId);
        int written = 0;
        for (RecommendationEntity r : recommendations.findByMarketId(marketId)) {
            if (r.getStatus() == RecommendationStatus.PENDING_APPROVAL
                    || evaluations.existsByRecommendationIdAndRuleVersion(r.getId(), OutcomeClassifier.RULE_VERSION)) {
                continue;
            }
            OutcomeClassifier.Result classified = OutcomeClassifier.classify(r.getSide(), r.getMarketType(),
                    r.getSelectionId(), result);
            evaluations.save(new DecisionOutcomeEvaluationEntity(r.getId(), OutcomeClassifier.RULE_VERSION,
                    clock.instant(), classified.classification(), classified.runnerStatus(), classified.deadHeat(),
                    json.write(classified.evidence())));
            written++;
        }
        return written;
    }
}
