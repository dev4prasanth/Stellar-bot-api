package com.stellartrack.messaging.contract;

import com.stellartrack.messaging.domain.ErrorClass;
import com.stellartrack.messaging.domain.InboundMessage;
import com.stellartrack.messaging.domain.Layer;
import com.stellartrack.messaging.domain.NormalizedLayerRecord;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * One approved, versioned contract for one layer, composed from small single-purpose components.
 * Register an instance as a Spring bean once the producing team supplies the schema; until then the layer
 * has no contract and its ingestion is reported as BLOCKED.
 *
 * @param versionSelector decides whether a delivery belongs to this contract version (header or envelope based,
 *                        exactly as the contract specifies)
 */
public record LayerContract<T>(
        Layer layer,
        String contractVersion,
        Predicate<InboundMessage> versionSelector,
        LayerMessageDeserializer<T> deserializer,
        LayerMessageValidator<T> validator,
        LayerMessageMapper<T> mapper) {

    public LayerContract {
        Objects.requireNonNull(layer);
        Objects.requireNonNull(contractVersion);
        Objects.requireNonNull(versionSelector);
        Objects.requireNonNull(deserializer);
        Objects.requireNonNull(validator);
        Objects.requireNonNull(mapper);
    }

    /** Deserialize → validate → map. Throws {@link ContractViolationException} on any contract failure. */
    public NormalizedLayerRecord apply(InboundMessage message) {
        T payload = deserializer.deserialize(message);
        if (payload == null) {
            throw new ContractViolationException(ErrorClass.MALFORMED_PAYLOAD, "Empty payload");
        }
        List<String> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            throw new ContractViolationException(ErrorClass.SCHEMA_VALIDATION, String.join("; ", violations));
        }
        NormalizedLayerRecord record = mapper.map(payload, message);
        if (!contractVersion.equals(record.contractVersion())) {
            throw new IllegalStateException("Mapper produced contract version " + record.contractVersion()
                    + " for contract " + contractVersion);
        }
        return record;
    }
}
