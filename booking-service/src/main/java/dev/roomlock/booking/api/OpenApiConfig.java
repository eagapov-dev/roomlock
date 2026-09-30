package dev.roomlock.booking.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document of the service, served at {@code /v3/api-docs}; Swagger UI renders it at
 * {@code /swagger-ui.html}.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

  /**
   * Name of the bearer scheme. Endpoints that need a token reference it with
   * {@code @SecurityRequirement}, one by one rather than globally, because reading the catalog is
   * open and a document that claims otherwise sends a developer looking for a token they do not
   * need.
   */
  static final String BEARER = "bearer-token";

  @Bean
  OpenAPI bookingOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("RoomLock booking API")
                .version("v1")
                .description("Hotel and room catalog and room bookings."))
        .components(
            new Components()
                .addSecuritySchemes(
                    BEARER,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description(
                            "RS256 access token of the configured issuer. The guest is the subject"
                                + " of the token; writing the catalog additionally needs the"
                                + " catalog:write scope.")));
  }
}
