package com.stellartrack.messaging.contract;

import com.stellartrack.messaging.domain.InboundMessage;
import com.stellartrack.messaging.domain.NormalizedLayerRecord;

/** Maps a validated payload to the internal record without changing its meaning. */
@FunctionalInterface
public interface LayerMessageMapper<T> {

    NormalizedLayerRecord map(T payload, InboundMessage message);
}
