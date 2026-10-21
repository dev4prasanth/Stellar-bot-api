package com.stellartrack.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Wires the TEST-ONLY {@link FakeExchange} in place of the Betfair adapters for integration tests. One primary
 * bean serves every exchange port it implements (market data, orders, account, availability).
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeExchangeConfig {

    @Bean
    @Primary
    FakeExchange fakeExchange() {
        return new FakeExchange();
    }
}
