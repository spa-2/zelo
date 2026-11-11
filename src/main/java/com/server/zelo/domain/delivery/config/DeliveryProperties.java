package com.server.zelo.domain.delivery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "zelo.delivery")
public record DeliveryProperties(
        Duration shipAfter,
        Duration completeAfter
) {
}
