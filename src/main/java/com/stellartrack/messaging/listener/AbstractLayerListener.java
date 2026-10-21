package com.stellartrack.messaging.listener;

import com.rabbitmq.client.Channel;
import com.stellartrack.common.util.SecretRedactor;
import com.stellartrack.common.web.CorrelationIds;
import com.stellartrack.messaging.application.IngestionOutcome;
import com.stellartrack.messaging.application.IngestionPipeline;
import com.stellartrack.messaging.domain.InboundMessage;
import com.stellartrack.messaging.domain.Layer;
import com.stellartrack.messaging.health.ConsumerHealthRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

/**
 * Manual-acknowledgement listener shared by the three layer listeners. At-least-once semantics:
 * <ol>
 *   <li>ACCEPTED / DUPLICATE / STALE: the receipt is committed, then the delivery is acked.</li>
 *   <li>Transient failure with budget left: a copy is published (confirmed) to the delayed retry queue, then acked.</li>
 *   <li>Permanent failure or exhausted budget: a copy is published (confirmed) to the DLQ, then acked.</li>
 *   <li>If the retry/DLQ publish itself fails, the delivery is rejected with requeue after a pause, so it is
 *       never lost and never spins in a tight loop.</li>
 * </ol>
 */
public abstract class AbstractLayerListener implements ChannelAwareMessageListener {

    private static final Logger log = LoggerFactory.getLogger(AbstractLayerListener.class);
    private static final long REQUEUE_PAUSE_MS = 1_000;

    private final IngestionPipeline pipeline;
    private final RedeliveryPublisher redelivery;
    private final BackoffPolicy backoff;
    private final ConsumerHealthRegistry health;
    private final Clock clock;

    protected AbstractLayerListener(IngestionPipeline pipeline, RedeliveryPublisher redelivery, BackoffPolicy backoff,
            ConsumerHealthRegistry health, Clock clock) {
        this.pipeline = pipeline;
        this.redelivery = redelivery;
        this.backoff = backoff;
        this.health = health;
        this.clock = clock;
    }

    public abstract Layer layer();

    @Override
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        InboundMessage inbound = toInbound(message);
        health.delivered(layer(), inbound.receivedAt());
        String correlationId = CorrelationIds.sanitizeOrNew(inbound.header(MessageHeaders.CORRELATION_ID));
        CorrelationIds.withCorrelationId(correlationId, () -> handle(message, channel, deliveryTag, inbound, correlationId));
    }

    private void handle(Message message, Channel channel, long deliveryTag, InboundMessage inbound, String correlationId) {
        try {
            IngestionOutcome outcome = pipeline.process(inbound, correlationId);
            switch (outcome.brokerAction()) {
                case ACKNOWLEDGE -> channel.basicAck(deliveryTag, false);
                case RETRY -> retryOrDeadLetter(message, channel, deliveryTag, inbound, outcome);
                case DEAD_LETTER -> {
                    redelivery.publishDeadLetter(layer(), message, inbound.attempt(), outcome);
                    health.deadLettered(layer());
                    channel.basicAck(deliveryTag, false);
                }
            }
        } catch (Exception e) {
            log.error("{} delivery could not be routed; requeueing after pause", layer(), e);
            requeue(channel, deliveryTag);
        }
    }

    private void retryOrDeadLetter(Message message, Channel channel, long deliveryTag, InboundMessage inbound,
            IngestionOutcome outcome) throws IOException {
        if (backoff.canRetry(inbound.attempt())) {
            redelivery.publishRetry(layer(), message, inbound.attempt() + 1, backoff.delayAfter(inbound.attempt()), outcome);
            health.retryScheduled(layer());
        } else {
            pipeline.markRetryExhausted(outcome.receiptId(), "Retry budget of " + backoff.maxAttempts() + " attempts exhausted");
            redelivery.publishDeadLetter(layer(), message, inbound.attempt(), outcome);
            health.deadLettered(layer());
        }
        channel.basicAck(deliveryTag, false);
    }

    private void requeue(Channel channel, long deliveryTag) {
        try {
            Thread.sleep(REQUEUE_PAUSE_MS);
            channel.basicReject(deliveryTag, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.error("{} delivery reject failed; broker will redeliver on channel recovery", layer());
        }
    }

    private InboundMessage toInbound(Message message) {
        MessageProperties props = message.getMessageProperties();
        Map<String, String> headers = new HashMap<>();
        props.getHeaders().forEach((key, value) -> {
            if (value != null && !SecretRedactor.isSensitiveKey(key)
                    && (value instanceof CharSequence || value instanceof Number || value instanceof Boolean)) {
                headers.put(key, value.toString());
            }
        });
        Object attemptHeader = props.getHeaders().get(MessageHeaders.ATTEMPT);
        int attempt = attemptHeader instanceof Number number ? number.intValue() : 1;
        Instant receivedAt = clock.instant();
        String originalRoutingKey = headers.get(MessageHeaders.ORIGINAL_ROUTING_KEY);
        String exchange = originalRoutingKey != null ? headers.get(MessageHeaders.ORIGINAL_EXCHANGE) : props.getReceivedExchange();
        String routingKey = originalRoutingKey != null ? originalRoutingKey : props.getReceivedRoutingKey();
        return new InboundMessage(layer(), message.getBody(), props.getContentType(), props.getMessageId(),
                props.getConsumerQueue(), exchange, routingKey,
                Boolean.TRUE.equals(props.getRedelivered()), attempt, headers, receivedAt, null);
    }
}
