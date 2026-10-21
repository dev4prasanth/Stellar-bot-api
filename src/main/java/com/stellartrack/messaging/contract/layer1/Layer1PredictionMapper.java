package com.stellartrack.messaging.contract.layer1;

import com.stellartrack.common.util.Hashing;
import com.stellartrack.messaging.contract.LayerMessageMapper;
import com.stellartrack.messaging.contract.PayloadFields;
import com.stellartrack.messaging.correlation.RaceMarketCorrelator;
import com.stellartrack.messaging.domain.InboundMessage;
import com.stellartrack.messaging.domain.NormalizedLayerRecord;
import com.stellartrack.messaging.domain.Planet;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes a Layer 1 prediction into one runner list ordered by {@code choice}. Odds of {@code 0} mean
 * "unknown" in the contract and are omitted. Layer 1 has no message id or event timestamp, so the identity is
 * the race plus the payload hash: an identical resend is a duplicate, any change is a new revision.
 */
public class Layer1PredictionMapper implements LayerMessageMapper<Layer1Prediction> {

    private final RaceMarketCorrelator correlator;

    public Layer1PredictionMapper(RaceMarketCorrelator correlator) {
        this.correlator = correlator;
    }

    @Override
    public NormalizedLayerRecord map(Layer1Prediction p, InboundMessage message) {
        String[] card = PayloadFields.splitCardId(p.cardId()).orElseThrow();
        String sha = Hashing.sha256Hex(message.body());
        Map<Integer, BigDecimal> odds = new HashMap<>();
        p.odds().forEach(o -> {
            if (o.odds() != null && o.odds().signum() > 0) {
                odds.putIfAbsent(o.horseNumber(), o.odds());
            }
        });
        List<Map<String, Object>> runners = new ArrayList<>();
        p.finalPrediction().forEach(r -> runners.add(runner(r, true, odds)));
        p.otherRunners().forEach(r -> runners.add(runner(r, false, odds)));
        runners.sort(Comparator.comparingInt(r -> (Integer) r.get("choice")));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardId", p.cardId());
        payload.put("venueId", card[0]);
        payload.put("raceDate", p.raceDate());
        payload.put("raceNumber", p.raceNumber());
        payload.put("runners", runners);
        payload.put("payloadSha256", sha);

        List<Integer> horses = runners.stream().map(r -> (Integer) r.get("horseNumber")).toList();
        return new NormalizedLayerRecord("L1:" + p.cardId() + ":" + p.raceNumber() + ":" + sha, null, null,
                Layer1PredictionContract.VERSION, payload, correlator.links(p.cardId(), p.raceNumber(), horses));
    }

    private static Map<String, Object> runner(Layer1Prediction.Runner r, boolean isFinal, Map<Integer, BigDecimal> odds) {
        Map<String, Object> runner = new LinkedHashMap<>();
        runner.put("horseNumber", r.horseNumber());
        runner.put("choice", r.choice());
        runner.put("planetScore", r.planetScore());
        PayloadFields.putIfPresent(runner, "odds", odds.get(r.horseNumber()));
        Planet.ofRunner(r.horseNumber()).ifPresent(planet -> runner.put("planet", planet.name()));
        runner.put("isFinal", isFinal);
        return runner;
    }
}
