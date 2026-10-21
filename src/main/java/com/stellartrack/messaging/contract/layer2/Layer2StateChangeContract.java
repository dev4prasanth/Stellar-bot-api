package com.stellartrack.messaging.contract.layer2;

import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.messaging.contract.LayerContract;
import com.stellartrack.messaging.contract.PayloadFields;
import com.stellartrack.messaging.domain.InboundMessage;
import com.stellartrack.messaging.domain.Layer;

/**
 * Contract {@code l2-planetary-state-v1}: Layer 2 publishes a {@code HashMap} on routing key {@code state-change},
 * so Spring AMQP sets {@code __TypeId__ = java.util.HashMap}; the payload shape ({@code states}) is accepted too.
 */
public final class Layer2StateChangeContract {

    public static final String VERSION = "l2-planetary-state-v1";
    static final String TYPE_ID = "java.util.HashMap";

    private Layer2StateChangeContract() {
    }

    public static LayerContract<Layer2StateChange> create(JsonCodec json) {
        return new LayerContract<>(Layer.LAYER2, VERSION, message -> selects(json, message),
                new Layer2StateChangeDeserializer(json), new Layer2StateChangeValidator(), new Layer2StateChangeMapper());
    }

    static boolean selects(JsonCodec json, InboundMessage message) {
        return TYPE_ID.equals(message.header("__TypeId__"))
                || PayloadFields.hasFields(json, message, "states").orElse(false);
    }
}
