package com.stellartrack.betfair.health;

import com.stellartrack.betfair.session.BetfairConnectionStatus;
import com.stellartrack.betfair.session.BetfairSessionManager;
import com.stellartrack.betfair.stream.ExchangeStreamClient;
import com.stellartrack.betfair.stream.StreamConnectionState;
import com.stellartrack.common.capability.Capability;
import com.stellartrack.common.capability.CapabilityProvider;
import com.stellartrack.common.capability.CapabilityReport;
import com.stellartrack.common.capability.CapabilityState;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Reports Betfair integration status truthfully: DISABLED without configuration, IMPLEMENTED when configured
 * but not yet observed working, VERIFIED once a real call has succeeded in this runtime.
 */
@Component
public class BetfairCapabilityProvider implements CapabilityProvider {

    private final BetfairSessionManager session;
    private final BetfairConnectionStatus status;
    private final ExchangeStreamClient stream;
    private final StreamConnectionState streamState;

    public BetfairCapabilityProvider(BetfairSessionManager session, BetfairConnectionStatus status,
            ExchangeStreamClient stream, StreamConnectionState streamState) {
        this.session = session;
        this.status = status;
        this.stream = stream;
        this.streamState = streamState;
    }

    @Override
    public List<CapabilityReport> capabilities() {
        Optional<String> unavailable = session.unavailableReason();
        // Only what is actually missing; nothing is listed when the connection is simply turned off.
        List<String> missing = session.missingCredentials();
        if (unavailable.isPresent()) {
            return List.of(
                    disabled(Capability.BETFAIR_SESSION, unavailable.get(), missing),
                    disabled(Capability.BETFAIR_MARKET_DATA, unavailable.get(), missing),
                    disabled(Capability.BETFAIR_ORDER_EXECUTION, unavailable.get(), missing),
                    disabled(Capability.BETFAIR_ACCOUNT, unavailable.get(), missing),
                    disabled(Capability.BETFAIR_STREAM, unavailable.get(), missing));
        }
        CapabilityState apiState = status.verified() ? CapabilityState.VERIFIED : CapabilityState.IMPLEMENTED;
        String apiReason = status.verified() ? "Real Betfair calls succeeded in this runtime"
                : "Configured; awaiting first successful call";
        CapabilityReport streamReport = !stream.enabled()
                ? CapabilityReport.of(Capability.BETFAIR_STREAM, CapabilityState.DISABLED,
                        "Exchange Stream disabled by configuration; controlled polling is used", List.of())
                : CapabilityReport.of(Capability.BETFAIR_STREAM,
                        streamState.snapshot().phase() == StreamConnectionState.Phase.SUBSCRIBED
                                ? CapabilityState.VERIFIED : CapabilityState.IMPLEMENTED,
                        "Stream phase: " + streamState.snapshot().phase(), List.of());
        return List.of(
                CapabilityReport.of(Capability.BETFAIR_SESSION, apiState, apiReason, List.of()),
                CapabilityReport.of(Capability.BETFAIR_MARKET_DATA, apiState, apiReason, List.of()),
                CapabilityReport.of(Capability.BETFAIR_ORDER_EXECUTION, CapabilityState.IMPLEMENTED,
                        "Implemented; real-money execution requires explicit human-controlled verification", List.of()),
                CapabilityReport.of(Capability.BETFAIR_ACCOUNT, apiState, apiReason, List.of()),
                streamReport);
    }

    private static CapabilityReport disabled(Capability capability, String reason, List<String> missing) {
        return CapabilityReport.of(capability, CapabilityState.DISABLED, reason, missing);
    }
}
