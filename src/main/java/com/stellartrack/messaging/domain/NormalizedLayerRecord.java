package com.stellartrack.messaging.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validated, contract-mapped representation of a source message. Field meanings come exclusively from the
 * approved layer contract; the mapper must not rewrite the source semantics.
 *
 * @param dedupKey        contract-defined stable identity (for example source id + revision)
 * @param marketLinks     only links derived from contract-approved correlation rules
 */
public record NormalizedLayerRecord(
        String dedupKey,
        String sourceMessageId,
        Instant sourceEventAt,
        String contractVersion,
        Map<String, Object> payload,
        List<MarketLink> marketLinks) {

    public NormalizedLayerRecord {
        Objects.requireNonNull(dedupKey, "dedupKey is required by every layer contract");
        Objects.requireNonNull(contractVersion, "contractVersion");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        marketLinks = marketLinks == null ? List.of() : List.copyOf(marketLinks);
    }

    public record MarketLink(String marketId, long selectionId, String mappingRule) {
    }
}
