package com.stellartrack.messaging.api;

import com.stellartrack.auth.security.AdminPrincipal;
import com.stellartrack.common.api.ApiPaths;
import com.stellartrack.common.api.PageRequests;
import com.stellartrack.common.api.PageResponse;
import com.stellartrack.messaging.application.IngestionOutcome;
import com.stellartrack.messaging.application.LayerFeedMonitor;
import com.stellartrack.messaging.application.LayerFeedStatus;
import com.stellartrack.messaging.application.QuarantineReprocessService;
import com.stellartrack.messaging.config.MessagingProperties;
import com.stellartrack.messaging.contract.LayerContractRegistry;
import com.stellartrack.messaging.domain.Layer;
import com.stellartrack.messaging.domain.ReceiptStatus;
import com.stellartrack.messaging.health.ConsumerHealthRegistry;
import com.stellartrack.messaging.persistence.MessageReceiptEntity;
import com.stellartrack.messaging.persistence.MessageReceiptRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.INGESTION)
@Tag(name = "RabbitMQ ingestion")
public class IngestionController {

    public record LayerStatusResponse(
            Layer layer,
            String description,
            LayerFeedStatus feed,
            String queue,
            String exchange,
            List<String> routingKeys,
            boolean enabled,
            List<String> contractVersions,
            ConsumerHealthRegistry.Snapshot runtime,
            Map<ReceiptStatus, Long> last24h,
            Boolean brokerConnected) {
    }

    public record ReceiptResponse(UUID id, Layer layer, ReceiptStatus status, String errorClass, String errorDetail,
            String contractVersion, String sourceMessageId, String dedupKey, java.time.Instant sourceEventAt,
            java.time.Instant receivedAt, java.time.Instant processedAt, Integer processingMs, int attempt,
            boolean redelivered, int payloadBytes, String payloadSha256, boolean rawPayloadRetained,
            String correlationId, UUID reprocessedFrom) {

        static ReceiptResponse from(MessageReceiptEntity e) {
            return new ReceiptResponse(e.getId(), e.getLayer(), e.getStatus(),
                    e.getErrorClass() == null ? null : e.getErrorClass().name(), e.getErrorDetail(),
                    e.getContractVersion(), e.getSourceMessageId(), e.getDedupKey(), e.getSourceEventAt(),
                    e.getReceivedAt(), e.getProcessedAt(), e.getProcessingMs(), e.getAttempt(), e.isRedelivered(),
                    e.getPayloadBytes(), e.getPayloadSha256(), e.getPayloadRaw() != null, e.getCorrelationId(),
                    e.getReprocessedFrom());
        }
    }

    private final LayerFeedMonitor monitor;
    private final ConsumerHealthRegistry health;
    private final MessageReceiptRepository receipts;
    private final MessagingProperties properties;
    private final LayerContractRegistry contracts;
    private final QuarantineReprocessService reprocessService;
    private final Clock clock;

    public IngestionController(LayerFeedMonitor monitor, ConsumerHealthRegistry health,
            MessageReceiptRepository receipts, MessagingProperties properties, LayerContractRegistry contracts,
            QuarantineReprocessService reprocessService, Clock clock) {
        this.monitor = monitor;
        this.health = health;
        this.receipts = receipts;
        this.properties = properties;
        this.contracts = contracts;
        this.reprocessService = reprocessService;
        this.clock = clock;
    }

    @GetMapping("/layers")
    @Transactional(readOnly = true)
    @Operation(summary = "Consumer health, freshness and outcome counts for Layer 1/2/3")
    public List<LayerStatusResponse> layers() {
        return Arrays.stream(Layer.values()).map(layer -> {
            Map<ReceiptStatus, Long> counts = new EnumMap<>(ReceiptStatus.class);
            receipts.countByStatusSince(layer, clock.instant().minus(Duration.ofHours(24)))
                    .forEach(row -> counts.put(row.getStatus(), row.getTotal()));
            MessagingProperties.LayerBinding binding = properties.binding(layer);
            return new LayerStatusResponse(layer, layer.description(), monitor.status(layer),
                    binding.hasQueue() ? binding.queue() : null, binding.hasExchange() ? binding.exchange() : null,
                    binding.routingKeys(), binding.enabled(), contracts.versions(layer),
                    health.snapshot(layer), counts, health.brokerConnected());
        }).toList();
    }

    @GetMapping("/receipts")
    @Transactional(readOnly = true)
    @Operation(summary = "Traceable message receipts (payload bodies are never returned)")
    public PageResponse<ReceiptResponse> receipts(
            @RequestParam(required = false) Layer layer,
            @RequestParam(required = false) ReceiptStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        var pageable = PageRequests.of(page, size, Sort.by(Sort.Direction.DESC, "receivedAt"));
        Page<MessageReceiptEntity> result;
        if (layer != null && status != null) {
            result = receipts.findByLayerAndStatus(layer, status, pageable);
        } else if (layer != null) {
            result = receipts.findByLayer(layer, pageable);
        } else if (status != null) {
            result = receipts.findByStatus(status, pageable);
        } else {
            result = receipts.findAll(pageable);
        }
        return PageResponse.from(result, ReceiptResponse::from);
    }

    @PostMapping("/receipts/{id}/reprocess")
    @Operation(summary = "Re-run a quarantined/invalid/contract-pending receipt from its retained raw payload")
    public IngestionOutcome reprocess(@PathVariable UUID id) {
        return reprocessService.reprocess(id, AdminPrincipal.current().actorId());
    }
}
