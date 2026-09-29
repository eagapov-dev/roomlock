package dev.roomlock.booking.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document of the service, served at {@code /v3/api-docs}; Swagger UI renders it at
 * {@code /swagger-ui.html}.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

  @Bean
  OpenAPI bookingOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("RoomLock booking API")
                .version("v1")
                .description("Hotel and room catalog and room bookings."));
  }
}
