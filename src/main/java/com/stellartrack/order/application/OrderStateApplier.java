package com.stellartrack.order.application;

import com.stellartrack.common.events.UiNotification;
import com.stellartrack.order.persistence.BetOrderEntity;
import com.stellartrack.order.persistence.BetOrderRepository;
import com.stellartrack.order.persistence.OrderIntentEntity;
import com.stellartrack.order.persistence.OrderIntentRepository;
import com.stellartrack.order.port.ProviderOrderState;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies provider order states from both the order stream and listCurrentOrders polling through one path.
 * Only orders attributable to this application (known betId or one of our customerOrderRefs) are stored;
 * bets placed elsewhere on the same account are never assigned to a configuration.
 */
@Component
public class OrderStateApplier {

    private final BetOrderRepository betOrders;
    private final OrderIntentRepository intents;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public OrderStateApplier(BetOrderRepository betOrders, OrderIntentRepository intents,
            ApplicationEventPublisher events, Clock clock) {
        this.betOrders = betOrders;
        this.intents = intents;
        this.events = events;
        this.clock = clock;
    }

    @EventListener
    @Transactional
    public void onStreamUpdate(ProviderOrderState state) {
        apply(state);
    }

    /** @return true when a stored order changed */
    @Transactional
    public boolean apply(ProviderOrderState state) {
        Optional<BetOrderEntity> existing = betOrders.findById(state.betId());
        BetOrderEntity order;
        if (existing.isPresent()) {
            order = existing.get();
        } else {
            Optional<OrderIntentEntity> intent = state.customerOrderRef() == null ? Optional.empty()
                    : intents.findByCustomerOrderRef(state.customerOrderRef());
            if (intent.isEmpty()) {
                return false;
            }
            order = new BetOrderEntity(state.betId(), intent.get().getId(), state.marketId(), state.selectionId(),
                    state.handicap(), state.side(), clock.instant());
        }
        boolean changed = order.apply(state, clock.instant());
        betOrders.save(order);
        if (changed) {
            events.publishEvent(UiNotification.of("orders", order.getBetId(), Map.of("status", order.getStatus(),
                    "sizeMatched", order.getSizeMatched())));
        }
        return changed;
    }
}
