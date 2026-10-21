package com.stellartrack.messaging.domain;

import com.stellartrack.common.capability.Capability;

/** The three external message-producing sources consumed through RabbitMQ. */
public enum Layer {
    LAYER1("Layer 1 prediction", Capability.LAYER1_INGESTION),
    LAYER2("Layer 2 planetary state", Capability.LAYER2_INGESTION),
    LAYER3("Layer 3 prediction", Capability.LAYER3_INGESTION);

    private final String description;
    private final Capability capability;

    Layer(String description, Capability capability) {
        this.description = description;
        this.capability = capability;
    }

    public String description() {
        return description;
    }

    public Capability capability() {
        return capability;
    }

    /** Lower-case token used for owned queue names, e.g. {@code stellartrack.layer1.retry}. */
    public String token() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
