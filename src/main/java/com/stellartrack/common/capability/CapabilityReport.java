package com.stellartrack.common.capability;

import java.util.List;

public record CapabilityReport(
        Capability capability,
        String label,
        CapabilityState state,
        String reason,
        List<String> missingDependencies) {

    public static CapabilityReport of(Capability capability, CapabilityState state, String reason, List<String> missing) {
        return new CapabilityReport(capability, capability.label(), state, reason, List.copyOf(missing));
    }

    public static CapabilityReport blocked(Capability capability, String reason, String... missing) {
        return of(capability, CapabilityState.BLOCKED, reason, List.of(missing));
    }

    public boolean usable() {
        return state == CapabilityState.IMPLEMENTED || state == CapabilityState.VERIFIED;
    }
}
