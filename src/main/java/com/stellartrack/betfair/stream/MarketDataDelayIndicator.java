package com.stellartrack.betfair.stream;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Remembers Betfair's own {@code isMarketDataDelayed} flag from the latest polled market book. Stream data is
 * reported as delayed unless the provider has confirmed otherwise, so delayed data is never labelled live.
 */
@Component
public class MarketDataDelayIndicator {

    private final AtomicReference<Boolean> delayed = new AtomicReference<>(null);

    public void observe(boolean providerDelayedFlag) {
        delayed.set(providerDelayedFlag);
    }

    /** True when known to be delayed or not yet known. */
    public boolean assumeDelayed() {
        return !Boolean.FALSE.equals(delayed.get());
    }

    public Boolean known() {
        return delayed.get();
    }
}
