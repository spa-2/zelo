package com.server.zelo.global.config;

import com.server.zelo.domain.delivery.config.DeliveryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "zelo.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
