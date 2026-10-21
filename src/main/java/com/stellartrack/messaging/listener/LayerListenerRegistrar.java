package com.stellartrack.messaging.listener;

import com.stellartrack.messaging.config.MessagingProperties;
import com.stellartrack.messaging.config.MessagingProperties.LayerBinding;
import com.stellartrack.messaging.contract.LayerContractRegistry;
import com.stellartrack.messaging.domain.Layer;
import com.stellartrack.messaging.health.ConsumerHealthRegistry;
import com.stellartrack.messaging.health.ConsumerHealthRegistry.ConsumerState;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Creates one listener container per layer, but only when that layer is enabled, its queue name has been
 * supplied, and an approved contract is registered. Otherwise the consumer stays off and the capability is
 * reported as disabled/blocked with the reason. Layer queues are owned by the producers and their consumers
 * (Layer 2/3); this application never consumes them, because competing consumers would split the messages.
 * Instead it declares its own subscription queue per layer (prefix {@code stellartrack.}) bound to the producers'
 * exchange, plus its own retry and dead-letter infrastructure.
 */
@Component
public class LayerListenerRegistrar implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LayerListenerRegistrar.class);

    /** Prefix of every queue this application may declare. */
    static final String OWNED_QUEUE_PREFIX = "stellartrack.";

    /** Queues declared and consumed by the producing services; consuming one would steal their messages. */
    static final Set<String> PRODUCER_OWNED_QUEUES = Set.of("layer1-predictions-queue", "race-data-queue",
            "race-results-queue", "planetary-state-queue", "reranked-predictions-queue", "race-results-dlq", "layer3-dlq");

    private final MessagingProperties properties;
    private final LayerContractRegistry contracts;
    private final CachingConnectionFactory connectionFactory;
    private final ConsumerHealthRegistry health;
    private final Map<Layer, AbstractLayerListener> listeners = new EnumMap<>(Layer.class);
    private final Map<Layer, SimpleMessageListenerContainer> containers = new EnumMap<>(Layer.class);
    private volatile boolean running;

    public LayerListenerRegistrar(MessagingProperties properties, LayerContractRegistry contracts,
            CachingConnectionFactory connectionFactory, ConsumerHealthRegistry health,
            List<AbstractLayerListener> layerListeners) {
        this.properties = properties;
        this.contracts = contracts;
        this.connectionFactory = connectionFactory;
        this.health = health;
        layerListeners.forEach(listener -> listeners.put(listener.layer(), listener));
    }

    /** Why a layer consumer may not run. {@code CONTRACT_PENDING} is a hard block; the others are configuration. */
    public record ConsumerBlock(Kind kind, String message) {
        public enum Kind {
            CONTRACT_PENDING,
            DISABLED,
            QUEUE_NOT_CONFIGURED,
            QUEUE_OWNED_BY_PRODUCER
        }

        public boolean contractPending() {
            return kind == Kind.CONTRACT_PENDING;
        }
    }

    /** Why a layer consumer is not running, or empty when it is eligible to run. */
    public Optional<ConsumerBlock> blockingReason(Layer layer) {
        LayerBinding binding = properties.binding(layer);
        if (!contracts.hasContract(layer)) {
            return Optional.of(new ConsumerBlock(ConsumerBlock.Kind.CONTRACT_PENDING,
                    "BLOCKED - contract pending: payload schema, routing and correlation rules not yet supplied"));
        }
        if (!binding.enabled()) {
            return Optional.of(new ConsumerBlock(ConsumerBlock.Kind.DISABLED, "Consumer disabled by configuration"));
        }
        if (!binding.hasQueue()) {
            return Optional.of(new ConsumerBlock(ConsumerBlock.Kind.QUEUE_NOT_CONFIGURED, "Queue name not configured"));
        }
        if (PRODUCER_OWNED_QUEUES.contains(binding.queue())) {
            return Optional.of(new ConsumerBlock(ConsumerBlock.Kind.QUEUE_OWNED_BY_PRODUCER, "Queue " + binding.queue()
                    + " belongs to another service; configure an own queue bound to the exchange instead"));
        }
        if (binding.declareSubscription() && (!binding.queue().startsWith(OWNED_QUEUE_PREFIX) || !binding.hasExchange()
                || binding.routingKeys().isEmpty())) {
            return Optional.of(new ConsumerBlock(ConsumerBlock.Kind.QUEUE_NOT_CONFIGURED, "Subscription needs an own queue ("
                    + OWNED_QUEUE_PREFIX + "*), an exchange and at least one routing key"));
        }
        return Optional.empty();
    }

    @Override
    public void start() {
        List<Layer> eligible = new ArrayList<>();
        for (Layer layer : Layer.values()) {
            Optional<ConsumerBlock> reason = blockingReason(layer);
            if (reason.isPresent()) {
                health.consumerState(layer, ConsumerState.NOT_STARTED, reason.get().message());
                log.info("{} consumer not started: {}", layer, reason.get().message());
            } else {
                eligible.add(layer);
            }
        }
        if (!eligible.isEmpty()) {
            connectionFactory.addConnectionListener(new ConnectionListener() {
                @Override
                public void onCreate(Connection connection) {
                    health.brokerConnection(true);
                }

                @Override
                public void onClose(Connection connection) {
                    health.brokerConnection(false);
                }
            });
        }
        for (Layer layer : eligible) {
            try {
                if (properties.binding(layer).declareSubscription()) {
                    declareSubscription(layer);
                }
                if (properties.declareOwnedTopology()) {
                    declareOwnedTopology(layer);
                }
                SimpleMessageListenerContainer container = createContainer(layer);
                container.start();
                containers.put(layer, container);
                health.consumerState(layer, ConsumerState.RUNNING, "Consuming " + properties.binding(layer).queue());
            } catch (RuntimeException e) {
                health.consumerState(layer, ConsumerState.FAILED, "Consumer failed to start: " + e.getClass().getSimpleName());
                log.error("{} consumer failed to start", layer, e);
            }
        }
        running = true;
    }

    private SimpleMessageListenerContainer createContainer(Layer layer) {
        LayerBinding binding = properties.binding(layer);
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(binding.queue());
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(Math.max(1, binding.prefetch()));
        container.setConcurrentConsumers(Math.max(1, binding.concurrency()));
        container.setDefaultRequeueRejected(false);
        container.setMissingQueuesFatal(false);
        container.setListenerId("stellartrack-" + layer.token());
        container.setMessageListener(listeners.get(layer));
        container.afterPropertiesSet();
        return container;
    }

    /**
     * Declares this application's durable subscription queue and binds it to the producers' exchange. The exchange
     * is only declared when it does not exist yet, and then with the producers' exact arguments (durable topic,
     * not auto-delete), so it is never altered.
     */
    private void declareSubscription(Layer layer) {
        LayerBinding binding = properties.binding(layer);
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        if (!exchangeExists(admin, binding.exchange())) {
            admin.declareExchange(new TopicExchange(binding.exchange(), true, false));
        }
        admin.declareQueue(QueueBuilder.durable(binding.queue()).build());
        for (String routingKey : binding.routingKeys()) {
            admin.declareBinding(new Binding(binding.queue(), Binding.DestinationType.QUEUE, binding.exchange(),
                    routingKey, null));
        }
        log.info("{} subscribed {} to {} with {}", layer, binding.queue(), binding.exchange(), binding.routingKeys());
    }

    private static boolean exchangeExists(RabbitAdmin admin, String exchange) {
        try {
            return Boolean.TRUE.equals(admin.getRabbitTemplate().execute(channel -> {
                channel.exchangeDeclarePassive(exchange);
                return true;
            }));
        } catch (AmqpException e) {
            return false;
        }
    }

    private void declareOwnedTopology(Layer layer) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        DirectExchange retryExchange = new DirectExchange(properties.retryExchange(), true, false);
        DirectExchange deadLetterExchange = new DirectExchange(properties.deadLetterExchange(), true, false);
        Queue retryQueue = QueueBuilder.durable(properties.retryQueue(layer))
                .deadLetterExchange("")
                .deadLetterRoutingKey(properties.binding(layer).queue())
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(properties.deadLetterQueue(layer)).build();
        admin.declareExchange(retryExchange);
        admin.declareExchange(deadLetterExchange);
        admin.declareQueue(retryQueue);
        admin.declareQueue(deadLetterQueue);
        Binding retryBinding = BindingBuilder.bind(retryQueue).to(retryExchange).with(retryQueue.getName());
        Binding dlqBinding = BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(deadLetterQueue.getName());
        admin.declareBinding(retryBinding);
        admin.declareBinding(dlqBinding);
    }

    @Override
    public void stop() {
        containers.forEach((layer, container) -> {
            container.stop();
            health.consumerState(layer, ConsumerState.STOPPED, "Application shutting down");
        });
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public boolean isConsuming(Layer layer) {
        SimpleMessageListenerContainer container = containers.get(layer);
        return container != null && container.isRunning() && container.getActiveConsumerCount() > 0;
    }
}
