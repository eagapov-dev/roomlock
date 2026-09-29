package dev.roomlock.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.roomlock.booking.Profiles;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Where the authentication of a charge exists and where it does not. The local path is the absence
 * of the interceptor, so that absence is worth a test of its own: a bean that leaked into a run
 * without the profile would send every charge to a metadata server that is not there.
 */
class CloudRunAuthConfigTest {

  private static final String AUDIENCE =
      "roomlock.cloud.payment-audience=https://payment-service.example.run.app";

  private final ApplicationContextRunner services = authentication().withPropertyValues(AUDIENCE);

  @Test
  void shouldAuthenticateChargesWhenProfileIsCloud() {
    // given
    ApplicationContextRunner revision = cloud(services);

    // when
    // then
    revision.run(context -> assertThat(context).hasSingleBean(CloudRunAuthInterceptor.class));
  }

  @Test
  void shouldAuthenticateNothingWhenProfileIsNotCloud() {
    // given
    // when
    // then
    services.run(context -> assertThat(context).doesNotHaveBean(CloudRunAuthInterceptor.class));
  }

  /**
   * The audience is a name of the deployment, and a deployment that forgot it has to fail loudly.
   */
  @Test
  void shouldNotStartWhenTheAudienceIsNotConfigured() {
    // given
    ApplicationContextRunner withoutAudience = cloud(authentication());

    // when
    // then
    withoutAudience.run(
        context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .hasMessageContaining("roomlock.cloud.payment-audience"));
  }

  /**
   * The configuration under test, with the placeholder resolution of a Boot application: without it
   * a property that is not set stays the literal placeholder instead of failing.
   */
  private static ApplicationContextRunner authentication() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
        .withUserConfiguration(CloudRunAuthConfig.class);
  }

  private static ApplicationContextRunner cloud(ApplicationContextRunner runner) {
    return runner.withInitializer(
        context -> context.getEnvironment().setActiveProfiles(Profiles.CLOUD));
  }
}
