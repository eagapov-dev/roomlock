package dev.roomlock.booking.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * The filter chain of the only public HTTP surface of the system.
 *
 * <p>booking-service is a <em>resource server</em>: it validates an RS256 access token against the
 * JWK set of the issuer it is configured with and never mints one. Minting belongs to an identity
 * provider - a resource server that signs the tokens it also accepts is its own authority on who a
 * caller is, which is precisely what an identity provider exists to stop.
 *
 * <p>Two levels of access, because the catalog and the bookings are read and written by different
 * people:
 *
 * <ul>
 *   <li>Reading the catalog is open. A room and its price are what a guest has to see
 *       <em>before</em> signing in, so a token requirement here would only make the booking flow
 *       start with a login wall for public information.
 *   <li>Writing the catalog needs the {@code catalog:write} scope. Creating a hotel or adding a
 *       room is an operator action; a guest token must not be able to do it, and scope rather than
 *       role because that is what the token carries about what a client may do.
 *   <li>Everything under {@code /api/v1/bookings} needs an authenticated caller. The guest is the
 *       token subject (see {@link Guests}), and ownership of the individual booking is enforced by
 *       the use cases, not here: a path pattern cannot know whose booking an id belongs to.
 * </ul>
 *
 * <p>{@code /actuator/health} and {@code /actuator/prometheus} stay open. Both are read by machines
 * that cannot present a token: the Cloud Run startup and liveness probes and the compose
 * healthcheck fetch health, and all three metric scrapers - Prometheus in {@code
 * infra/docker-compose.yml}, the Google Managed Prometheus collector sidecar in {@code
 * infra/gcp/observability.tf}, and the {@code prometheus.io/scrape} annotations of the Kubernetes
 * manifests ({@code infra/k8s/base}, which lives on the {@code build/kubernetes} branch and not on
 * this one) - scrape the metrics with no credential configured anywhere. A scope on the metrics
 * endpoint would not fail loudly: Prometheus would show the target as down and the sidecar would
 * silently drop the samples, which is how a dashboard goes blank for a week before anybody notices.
 * The exposure list of {@code application.yml} publishes these two endpoints and nothing else, and
 * {@code anyRequest().denyAll()} below closes the rest.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class ApiSecurityConfig {

  /** Scope an operator token has to carry to write the catalog. */
  static final String CATALOG_WRITE = "catalog:write";

  /** Authority Spring Security derives from a {@code scope} claim entry. */
  private static final String CATALOG_WRITE_AUTHORITY = "SCOPE_" + CATALOG_WRITE;

  private static final String HOTELS = "/api/v1/hotels";
  private static final String HOTEL = HOTELS + "/*";
  private static final String HOTEL_ROOMS = HOTEL + "/rooms";
  private static final String ROOM = "/api/v1/rooms/*";
  private static final String BOOKINGS = "/api/v1/bookings/**";

  /** The OpenAPI document and Swagger UI: what a developer needs to call the API at all. */
  private static final String[] API_DOCS = {
    "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
  };

  @Bean
  SecurityFilterChain apiSecurity(HttpSecurity http, Problems problems, ObjectMapper json)
      throws Exception {
    AuthenticationEntryPoint unauthenticated = unauthenticated(problems, json);
    AccessDeniedHandler forbidden = forbidden(problems, json);
    http.authorizeHttpRequests(
            requests ->
                requests
                    // An exception forwards to /error inside the same request, and an async
                    // dispatch resumes one that was already authorized: re-deciding either would
                    // answer 403 where ApiExceptionHandler has already written the real problem.
                    .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC)
                    .permitAll()
                    .requestMatchers(EndpointRequest.to("health", "prometheus"))
                    .permitAll()
                    .requestMatchers(API_DOCS)
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, HOTEL, ROOM)
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, HOTELS, HOTEL_ROOMS)
                    .hasAuthority(CATALOG_WRITE_AUTHORITY)
                    .requestMatchers(BOOKINGS)
                    .authenticated()
                    // Nothing else is part of the contract, so nothing else is reachable - a new
                    // endpoint is closed until this document says otherwise.
                    .anyRequest()
                    .denyAll())
        // No browser session and no cookie: every request carries its own bearer token, so there is
        // no session for a CSRF token to protect and nothing for a foreign page to ride on.
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // The defaults of a browser-facing application, and both wrong here: a login form would
        // answer a redirect where an API client expects a status, and basic authentication would
        // invite a password this service has no business seeing.
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .jwt(Customizer.withDefaults())
                    .authenticationEntryPoint(unauthenticated)
                    .accessDeniedHandler(forbidden))
        // The same two answers for a request that carries no token at all, which the bearer token
        // filter never sees.
        .exceptionHandling(
            handling ->
                handling.authenticationEntryPoint(unauthenticated).accessDeniedHandler(forbidden));
    return http.build();
  }

  /**
   * 401 for a request with no token, an expired one, or one this service cannot verify. The {@code
   * WWW-Authenticate} header is what RFC 6750 asks a bearer-token resource server for.
   */
  private static AuthenticationEntryPoint unauthenticated(Problems problems, ObjectMapper json) {
    return (request, response, failure) -> {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
      write(response, json, problems.unauthenticated());
    };
  }

  /** 403 for a token that is valid but does not carry the scope the endpoint asks for. */
  private static AccessDeniedHandler forbidden(Problems problems, ObjectMapper json) {
    return (request, response, denied) -> write(response, json, problems.forbidden());
  }

  /**
   * Writes the problem itself rather than letting the container produce a body: a failure of the
   * filter chain is answered in the same {@code application/problem+json} shape as every failure of
   * a handler (rules E-1, E-2), so a client has one error format and not two. No {@code charset}
   * parameter, for the same reason: Jackson writes UTF-8, JSON has no other default, and the rest
   * of this API answers with the bare media type.
   */
  private static void write(HttpServletResponse response, ObjectMapper json, ProblemDetail problem)
      throws IOException {
    response.setStatus(problem.getStatus());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    json.writeValue(response.getOutputStream(), problem);
  }
}
