package com.stellartrack.messaging.health;

import com.stellartrack.common.capability.CapabilityProvider;
import com.stellartrack.common.capability.CapabilityReport;
import com.stellartrack.common.capability.CapabilityState;
import com.stellartrack.messaging.domain.Layer;
import com.stellartrack.messaging.listener.LayerListenerRegistrar;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class MessagingCapabilityProvider implements CapabilityProvider {

    private final LayerListenerRegistrar registrar;

    public MessagingCapabilityProvider(LayerListenerRegistrar registrar) {
        this.registrar = registrar;
    }

    @Override
    public List<CapabilityReport> capabilities() {
        return Arrays.stream(Layer.values()).map(this::report).toList();
    }

    private CapabilityReport report(Layer layer) {
        Optional<LayerListenerRegistrar.ConsumerBlock> reason = registrar.blockingReason(layer);
        if (reason.isEmpty()) {
            boolean consuming = registrar.isConsuming(layer);
            return CapabilityReport.of(layer.capability(), consuming ? CapabilityState.VERIFIED : CapabilityState.IMPLEMENTED,
                    consuming ? "Consuming from the configured queue" : "Configured but not currently consuming", List.of());
        }
        boolean contractMissing = reason.get().contractPending();
        return CapabilityReport.of(layer.capability(),
                contractMissing ? CapabilityState.BLOCKED : CapabilityState.DISABLED,
                reason.get().message(),
                contractMissing
                        ? List.of(layer.description() + " payload schema & versioning",
                                "Queue/exchange/routing bindings and ownership",
                                "Message identity, timestamps and correlation keys")
                        : List.of());
    }
}
