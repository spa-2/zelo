package com.server.zelo.domain.portone.dto.response;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.time.ZoneId;


@ConfigurationProperties(prefix = "zelo.portone")
public record PortOneProperties(
      @NotBlank String baseUrl,
      @NotBlank  String apiSecret,
      @NotBlank  String storeId,
      @NotNull  Duration connectTimeout,
      @NotNull Duration readTimeout,
      @NotNull ZoneId timeZone
) {
}
