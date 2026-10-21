package com.stellartrack.messaging.listener;

import com.stellartrack.messaging.application.IngestionOutcome;
import com.stellartrack.messaging.config.MessagingProperties;
import com.stellartrack.messaging.domain.Layer;
import java.time.Duration;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes a copy of a delivery to the layer's delayed-retry queue or dead-letter queue and waits for the
 * broker's publisher confirm, so the original delivery is acknowledged only after the copy is durable.
 */
@Component
public class RedeliveryPublisher {

    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;

    public RedeliveryPublisher(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    public void publishRetry(Layer layer, Message original, int nextAttempt, Duration delay, IngestionOutcome outcome) {
        Message copy = copyOf(original, nextAttempt, outcome);
        copy.getMessageProperties().setExpiration(Long.toString(delay.toMillis()));
        publishConfirmed(properties.retryExchange(), properties.retryQueue(layer), copy);
    }

    public void publishDeadLetter(Layer layer, Message original, int attempt, IngestionOutcome outcome) {
        Message copy = copyOf(original, attempt, outcome);
        publishConfirmed(properties.deadLetterExchange(), properties.deadLetterQueue(layer), copy);
    }

    private void publishConfirmed(String exchange, String routingKey, Message message) {
        rabbitTemplate.invoke(operations -> {
            operations.send(exchange, routingKey, message);
            operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            return null;
        });
    }

    private static Message copyOf(Message original, int attempt, IngestionOutcome outcome) {
        MessageProperties source = original.getMessageProperties();
        MessageProperties props = new MessageProperties();
        props.setContentType(source.getContentType());
        props.setContentEncoding(source.getContentEncoding());
        props.setMessageId(source.getMessageId());
        props.setTimestamp(source.getTimestamp());
        props.setType(source.getType());
        props.setCorrelationId(source.getCorrelationId());
        props.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        source.getHeaders().forEach(props::setHeader);
        // Layer contracts select on the producer's routing key, which a retry through the default exchange loses.
        if (props.getHeader(MessageHeaders.ORIGINAL_ROUTING_KEY) == null && source.getReceivedRoutingKey() != null) {
            props.setHeader(MessageHeaders.ORIGINAL_ROUTING_KEY, source.getReceivedRoutingKey());
            props.setHeader(MessageHeaders.ORIGINAL_EXCHANGE, source.getReceivedExchange());
        }
        props.setHeader(MessageHeaders.ATTEMPT, attempt);
        if (outcome.receiptId() != null) {
            props.setHeader(MessageHeaders.RECEIPT_ID, outcome.receiptId().toString());
        }
        if (outcome.errorClass() != null) {
            props.setHeader(MessageHeaders.ERROR_CLASS, outcome.errorClass().name());
        }
        return new Message(original.getBody(), props);
    }
}
