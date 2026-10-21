package com.stellartrack.messaging.contract.layer3;

import static com.stellartrack.messaging.contract.PayloadFields.putIfPresent;

import com.stellartrack.messaging.contract.LayerMessageMapper;
import com.stellartrack.messaging.contract.PayloadFields;
import com.stellartrack.messaging.correlation.RaceMarketCorrelator;
import com.stellartrack.messaging.domain.InboundMessage;
import com.stellartrack.messaging.domain.NormalizedLayerRecord;
import com.stellartrack.messaging.domain.Planet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes a Layer 3 ranking ordered by {@code finalRank}. Layer 3 republishes after a replacement with a new
 * {@code generatedAt}, so the identity is race + source + generation time.
 */
public class Layer3RankingMapper implements LayerMessageMapper<Layer3Ranking> {

    private final RaceMarketCorrelator correlator;

    public Layer3RankingMapper(RaceMarketCorrelator correlator) {
        this.correlator = correlator;
    }

    @Override
    public NormalizedLayerRecord map(Layer3Ranking p, InboundMessage message) {
        String[] card = PayloadFields.splitCardId(p.cardId()).orElseThrow();
        List<Layer3Ranking.Ranking> ordered = p.rankings().stream()
                .sorted(Comparator.comparingInt(Layer3Ranking.Ranking::finalRank)).toList();
        Integer nap = ordered.stream().filter(Layer3Ranking.Ranking::nap).map(Layer3Ranking.Ranking::horseNumber)
                .findFirst().orElse(null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardId", p.cardId());
        payload.put("venueId", card[0]);
        payload.put("raceDate", card[1]);
        payload.put("raceNumber", p.raceNumber());
        payload.put("source", p.source());
        putIfPresent(payload, "rerankPolicyVersion", p.rerankPolicyVersion());
        payload.put("generatedAt", p.generatedAt().toString());
        putIfPresent(payload, "napHorseNumber", nap);
        payload.put("rankings", ordered.stream().map(Layer3RankingMapper::ranking).toList());

        String dedupKey = "L3:" + p.cardId() + ":" + p.raceNumber() + ":" + p.source() + ":" + p.generatedAt();
        List<Integer> horses = ordered.stream().map(Layer3Ranking.Ranking::horseNumber).toList();
        return new NormalizedLayerRecord(dedupKey, null, p.generatedAt(), Layer3RankingContract.VERSION, payload,
                correlator.links(p.cardId(), p.raceNumber(), horses));
    }

    private static Map<String, Object> ranking(Layer3Ranking.Ranking r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("horseNumber", r.horseNumber());
        putIfPresent(m, "horseId", r.horseId());
        Planet.ofRunner(r.horseNumber()).ifPresent(planet -> m.put("planet", planet.name()));
        m.put("finalRank", r.finalRank());
        putIfPresent(m, "confidenceScore", r.confidenceScore());
        putIfPresent(m, "selectionConfidenceScore", r.selectionConfidenceScore());
        m.put("rationaleTags", r.rationaleTags());
        m.put("top4", r.top4());
        m.put("nap", r.nap());
        putIfPresent(m, "odds", r.odds() != null && r.odds().signum() > 0 ? r.odds() : null);
        return m;
    }
}
