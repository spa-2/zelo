package com.server.zelo.domain.portone.dto.response;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.time.ZoneId;


@ConfigurationProperties(prefix = "zelo.portone")
public record PortOneProperties(
        String baseUrl,
        String apiSecret,
        String storeId,
        Duration connectTimeout,
        Duration readTimeout,
        ZoneId timeZone
) {
}
