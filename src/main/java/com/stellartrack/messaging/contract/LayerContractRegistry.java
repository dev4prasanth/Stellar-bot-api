package com.stellartrack.messaging.contract;

import com.stellartrack.messaging.domain.ErrorClass;
import com.stellartrack.messaging.domain.InboundMessage;
import com.stellartrack.messaging.domain.Layer;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Resolves the approved contract version for a delivery. No registered contract means CONTRACT_PENDING. */
@Component
public class LayerContractRegistry {

    private final List<LayerContract<?>> contracts;

    /** Uses an ObjectProvider because no contract beans exist until the layer schemas are supplied. */
    @Autowired
    public LayerContractRegistry(ObjectProvider<LayerContract<?>> contracts) {
        this(contracts.orderedStream().toList());
    }

    LayerContractRegistry(List<LayerContract<?>> contracts) {
        this.contracts = List.copyOf(contracts);
    }

    public boolean hasContract(Layer layer) {
        return contracts.stream().anyMatch(contract -> contract.layer() == layer);
    }

    public List<String> versions(Layer layer) {
        return contracts.stream().filter(c -> c.layer() == layer).map(LayerContract::contractVersion).toList();
    }

    public LayerContract<?> resolve(InboundMessage message) {
        List<LayerContract<?>> forLayer = contracts.stream().filter(c -> c.layer() == message.layer()).toList();
        if (forLayer.isEmpty()) {
            throw new ContractViolationException(ErrorClass.CONTRACT_PENDING,
                    message.layer().description() + " contract has not been supplied");
        }
        List<LayerContract<?>> matching = forLayer.stream().filter(c -> c.versionSelector().test(message)).toList();
        if (matching.size() != 1) {
            throw new ContractViolationException(ErrorClass.UNSUPPORTED_CONTRACT_VERSION, matching.isEmpty()
                    ? "No supported contract version matches this message"
                    : "Message matches more than one contract version");
        }
        return matching.getFirst();
    }
}
