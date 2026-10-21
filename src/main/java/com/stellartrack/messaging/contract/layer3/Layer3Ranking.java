package com.stellartrack.messaging.contract.layer3;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Layer 3 {@code Layer3RankingMessage} published to {@code layer3.reranked}: the full ordered ranking of all
 * runners (top 4 + remaining) for one race and one prediction source.
 *
 * @param source {@code LAYER1_RERANKED} or {@code STANDALONE}
 */
public record Layer3Ranking(String cardId, Integer raceNumber, String source, String rerankPolicyVersion,
        Instant generatedAt, List<Ranking> rankings) {

    public static final List<String> SOURCES = List.of("LAYER1_RERANKED", "STANDALONE");

    /**
     * @param horseId planet name, or the horse number as text when the runner has no planet
     * @param top4    Java field {@code isTop4}; serialised as {@code top4} (both spellings accepted)
     * @param nap     Java field {@code isNap}; serialised as {@code nap} (both spellings accepted)
     * @param odds    may be null
     */
    public record Ranking(String horseId, Integer horseNumber, Integer finalRank, BigDecimal confidenceScore,
            BigDecimal selectionConfidenceScore, List<String> rationaleTags, boolean top4, boolean nap,
            BigDecimal odds) {
    }
}
