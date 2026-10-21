package com.stellartrack.betfair.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.stellartrack.betfair.config.BetfairProperties;
import com.stellartrack.common.domain.BetSide;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.order.port.ProviderOrderState;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StreamProtocolTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private MarketStreamCache cache(StreamConnectionState state, MarketDataDelayIndicator delay) {
        BetfairProperties props = new BetfairProperties(true, null, null, null, null, null, null, null, null, null, null,
                "en", null, null, null, null, null,
                new BetfairProperties.Stream(true, "h", 443, Duration.ofSeconds(5), Duration.ZERO, Duration.ofSeconds(60)),
                null, "ST", null);
        return new MarketStreamCache(state, delay, props);
    }

    @Test
    void appliesImageThenLevelDeltas() {
        StreamConnectionState state = new StreamConnectionState(Clock.systemUTC());
        state.phase(StreamConnectionState.Phase.SUBSCRIBED, null);
        state.messageReceived(false);
        MarketDataDelayIndicator delay = new MarketDataDelayIndicator();
        delay.observe(false);
        MarketStreamCache cache = cache(state, delay);
        cache.apply(mapper.readTree("""
                {"id":"1.1","img":true,"marketDefinition":{"status":"OPEN","inPlay":false,"numberOfActiveRunners":2,
                 "version":7,"runners":[{"id":10,"status":"ACTIVE"},{"id":11,"status":"ACTIVE"}]},
                 "rc":[{"id":10,"batb":[[0,3.5,20],[1,3.45,15]],"batl":[[0,3.6,10]],"ltp":3.55}]}"""));
        cache.apply(mapper.readTree("""
                {"id":"1.1","rc":[{"id":10,"batb":[[0,3.55,5],[1,0,0]]}]}"""));
        MarketPrices prices = cache.freshPrices("1.1").orElseThrow();
        MarketPrices.RunnerPrices runner = prices.runner(10).orElseThrow();
        assertThat(runner.bestBack().orElseThrow().price()).isEqualByComparingTo("3.55");
        assertThat(runner.availableToBack()).hasSize(1);
        assertThat(prices.version()).isEqualTo(7L);
        assertThat(prices.dataDelayed()).isFalse();
        assertThat(prices.source()).isEqualTo(MarketPrices.PriceSource.STREAM);
    }

    @Test
    void notFreshWithoutSubscriptionAndDelayedUnlessConfirmed() {
        StreamConnectionState state = new StreamConnectionState(Clock.systemUTC());
        MarketStreamCache cache = cache(state, new MarketDataDelayIndicator());
        cache.apply(mapper.readTree("{\"id\":\"1.2\",\"img\":true,\"marketDefinition\":{\"status\":\"OPEN\"}}"));
        assertThat(cache.freshPrices("1.2")).isEmpty();
        state.phase(StreamConnectionState.Phase.SUBSCRIBED, null);
        state.messageReceived(false);
        assertThat(cache.freshPrices("1.2").orElseThrow().dataDelayed()).isTrue();
    }

    @Test
    void providerStaleFlagMakesCacheUnusable() {
        StreamConnectionState state = new StreamConnectionState(Clock.systemUTC());
        state.phase(StreamConnectionState.Phase.SUBSCRIBED, null);
        state.messageReceived(true);
        assertThat(state.isFresh(Duration.ofSeconds(15))).isFalse();
    }

    @Test
    void translatesOrderChanges() {
        List<ProviderOrderState> states = OrderStreamTranslator.translate(mapper.readTree("""
                {"op":"ocm","oc":[{"id":"1.1","orc":[{"id":10,"uo":[{"id":"222","p":3.5,"s":10,"side":"L","status":"E",
                 "pt":"L","ot":"L","pd":1760270400000,"sm":4,"sr":6,"sl":0,"sc":0,"sv":0,"avp":3.5,"rfo":"STabc"}]}]}]}"""));
        assertThat(states).singleElement().satisfies(s -> {
            assertThat(s.betId()).isEqualTo("222");
            assertThat(s.side()).isEqualTo(BetSide.LAY);
            assertThat(s.sizeMatched()).isEqualByComparingTo("4");
            assertThat(s.sizeRemaining()).isEqualByComparingTo("6");
            assertThat(s.executionComplete()).isFalse();
            assertThat(s.persistenceType()).isEqualTo("LAPSE");
            assertThat(s.customerOrderRef()).isEqualTo("STabc");
            assertThat(s.source()).isEqualTo(ProviderOrderState.Source.STREAM);
        });
    }
}
