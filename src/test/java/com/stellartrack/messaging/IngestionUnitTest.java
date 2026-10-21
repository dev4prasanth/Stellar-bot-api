package com.stellartrack.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stellartrack.messaging.application.IngestionOutcome;
import com.stellartrack.messaging.config.MessagingProperties;
import com.stellartrack.messaging.contract.ContractViolationException;
import com.stellartrack.messaging.contract.LayerContract;
import com.stellartrack.messaging.contract.LayerContractRegistryAccess;
import com.stellartrack.messaging.domain.ErrorClass;
import com.stellartrack.messaging.domain.InboundMessage;
import com.stellartrack.messaging.domain.Layer;
import com.stellartrack.messaging.domain.NormalizedLayerRecord;
import com.stellartrack.messaging.domain.ReceiptStatus;
import com.stellartrack.messaging.listener.BackoffPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Ingestion building blocks with a test-only contract fixture (no real layer contract exists yet). */
class IngestionUnitTest {

    /** Test-only fixture contract: body "id|value". */
    static LayerContract<String[]> fixtureContract(Layer layer) {
        return new LayerContract<>(layer, "fixture-1", m -> "fixture-1".equals(m.header("x-test-version")),
                m -> {
                    String body = new String(m.body(), StandardCharsets.UTF_8);
                    String[] parts = body.split("\\|");
                    if (parts.length != 2) {
                        throw new ContractViolationException(ErrorClass.MALFORMED_PAYLOAD, "Expected id|value");
                    }
                    return parts;
                },
                parts -> parts[0].isBlank() ? List.of("id is required") : List.of(),
                (parts, m) -> new NormalizedLayerRecord(parts[0], parts[0], null, "fixture-1", Map.of("value", parts[1]), List.of()));
    }

    private static InboundMessage message(Layer layer, String body, String version) {
        return new InboundMessage(layer, body.getBytes(StandardCharsets.UTF_8), "text/plain", "m1", "q", "x", "k", false, 1,
                version == null ? Map.of() : Map.of("x-test-version", version), Instant.now(), null);
    }

    @Test
    void registryReportsContractPendingWhenNoContractRegistered() {
        var registry = LayerContractRegistryAccess.of(List.of());
        assertThatThrownBy(() -> registry.resolve(message(Layer.LAYER1, "a|b", "fixture-1")))
                .isInstanceOfSatisfying(ContractViolationException.class,
                        e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.CONTRACT_PENDING));
    }

    @Test
    void registryRejectsUnsupportedVersions() {
        var registry = LayerContractRegistryAccess.of(List.of(fixtureContract(Layer.LAYER1)));
        assertThatThrownBy(() -> registry.resolve(message(Layer.LAYER1, "a|b", "v99")))
                .isInstanceOfSatisfying(ContractViolationException.class,
                        e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.UNSUPPORTED_CONTRACT_VERSION));
    }

    @Test
    void contractValidatesAndMaps() {
        LayerContract<String[]> contract = fixtureContract(Layer.LAYER2);
        assertThat(contract.apply(message(Layer.LAYER2, "abc|42", "fixture-1")).dedupKey()).isEqualTo("abc");
        assertThatThrownBy(() -> contract.apply(message(Layer.LAYER2, "nopipe", "fixture-1")))
                .isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> contract.apply(message(Layer.LAYER2, " |42", "fixture-1")))
                .isInstanceOfSatisfying(ContractViolationException.class,
                        e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.SCHEMA_VALIDATION));
    }

    @Test
    void outcomesMapToBrokerActions() {
        UUID id = UUID.randomUUID();
        assertThat(new IngestionOutcome(id, ReceiptStatus.ACCEPTED, null, null).brokerAction())
                .isEqualTo(IngestionOutcome.BrokerAction.ACKNOWLEDGE);
        assertThat(new IngestionOutcome(id, ReceiptStatus.DUPLICATE, null, null).brokerAction())
                .isEqualTo(IngestionOutcome.BrokerAction.ACKNOWLEDGE);
        assertThat(new IngestionOutcome(id, ReceiptStatus.RETRYING, ErrorClass.TRANSIENT_DEPENDENCY, null).brokerAction())
                .isEqualTo(IngestionOutcome.BrokerAction.RETRY);
        assertThat(new IngestionOutcome(id, ReceiptStatus.INVALID, ErrorClass.SCHEMA_VALIDATION, null).brokerAction())
                .isEqualTo(IngestionOutcome.BrokerAction.DEAD_LETTER);
        assertThat(new IngestionOutcome(id, ReceiptStatus.CONTRACT_PENDING, ErrorClass.CONTRACT_PENDING, null).brokerAction())
                .isEqualTo(IngestionOutcome.BrokerAction.DEAD_LETTER);
    }

    @Test
    void backoffGrowsExponentiallyWithinBoundsAndJitter() {
        var props = new MessagingProperties(false, 1024, true,
                new MessagingProperties.Retry(4, Duration.ofSeconds(2), 2.0, Duration.ofSeconds(10), 0.2), "r", "d", Map.of());
        BackoffPolicy policy = new BackoffPolicy(props);
        assertThat(policy.delayAfter(1).toMillis()).isBetween(1600L, 2400L);
        assertThat(policy.delayAfter(2).toMillis()).isBetween(3200L, 4800L);
        assertThat(policy.delayAfter(10).toMillis()).isBetween(8000L, 12000L);
        assertThat(policy.canRetry(3)).isTrue();
        assertThat(policy.canRetry(4)).isFalse();
    }
}
