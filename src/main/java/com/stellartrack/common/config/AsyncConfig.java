package com.stellartrack.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Enables asynchronous event listeners (virtual threads are enabled via spring.threads.virtual.enabled). */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncConfig {
}
