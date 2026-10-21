package com.stellartrack.messaging.contract.layer3;

import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.messaging.contract.LayerContract;
import com.stellartrack.messaging.contract.PayloadFields;
import com.stellartrack.messaging.correlation.RaceMarketCorrelator;
import com.stellartrack.messaging.domain.InboundMessage;
import com.stellartrack.messaging.domain.Layer;

/**
 * Contract {@code l3-ranking-v1}: Layer 3 publishes on {@code layer3.reranked} with
 * {@code __TypeId__ = com.stellartrack.layer3.event.dto.Layer3RankingMessage}; the payload shape is accepted too.
 */
public final class Layer3RankingContract {

    public static final String VERSION = "l3-ranking-v1";
    static final String TYPE_SUFFIX = "Layer3RankingMessage";

    private Layer3RankingContract() {
    }

    public static LayerContract<Layer3Ranking> create(JsonCodec json, RaceMarketCorrelator correlator) {
        return new LayerContract<>(Layer.LAYER3, VERSION, message -> selects(json, message),
                new Layer3RankingDeserializer(json), new Layer3RankingValidator(), new Layer3RankingMapper(correlator));
    }

    static boolean selects(JsonCodec json, InboundMessage message) {
        String typeId = message.header("__TypeId__");
        return (typeId != null && typeId.endsWith(TYPE_SUFFIX))
                || PayloadFields.hasFields(json, message, "rankings", "rerankPolicyVersion").orElse(false);
    }
}
