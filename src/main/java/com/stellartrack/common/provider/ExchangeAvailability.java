package com.stellartrack.common.provider;

import java.util.Optional;

/** Whether the exchange integration is configured and enabled. Lets domain modules skip work without a provider. */
public interface ExchangeAvailability {

    /** Empty when usable; otherwise the human-readable reason it is not. */
    Optional<String> unavailableReason();

    default boolean isAvailable() {
        return unavailableReason().isEmpty();
    }
}
