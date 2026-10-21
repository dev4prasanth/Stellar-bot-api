package com.stellartrack.common.capability;

import com.stellartrack.common.exception.CapabilityBlockedException;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Aggregates every module's capability status so the API and guards share one source of truth. */
@Component
public class CapabilityRegistry {

    private final List<CapabilityProvider> providers;

    public CapabilityRegistry(ObjectProvider<CapabilityProvider> providers) {
        this.providers = providers.orderedStream().toList();
    }

    public List<CapabilityReport> all() {
        return providers.stream()
                .flatMap(provider -> provider.capabilities().stream())
                .sorted(Comparator.comparing(CapabilityReport::capability))
                .toList();
    }

    public CapabilityReport get(Capability capability) {
        Map<Capability, CapabilityReport> byCapability = new EnumMap<>(Capability.class);
        all().forEach(report -> byCapability.put(report.capability(), report));
        CapabilityReport report = byCapability.get(capability);
        return report != null
                ? report
                : CapabilityReport.blocked(capability, "No module reports this capability");
    }

    /** Throws when the capability cannot currently be used, carrying the real reason. */
    public void require(Capability capability) {
        CapabilityReport report = get(capability);
        if (!report.usable()) {
            throw new CapabilityBlockedException(capability, report.reason());
        }
    }
}
