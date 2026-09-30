plugins { id("roomlock.spring-boot-service") }

dependencies {
  // One version for every io.grpc artifact: grpc-java releases its modules together, and the
  // client starter below would otherwise put an older transport next to the pinned stubs.
  implementation(platform(libs.grpc.bom))

  implementation(project(":payment-api"))
  implementation(project(":common"))

  implementation(libs.spring.boot.starter.web)
  implementation(libs.spring.boot.starter.data.jpa)
  implementation(libs.spring.boot.starter.validation)
  implementation(libs.springdoc.openapi.starter.webmvc.ui)
  // The only public HTTP surface of the system, so the only module that gets a filter chain: this
  // service validates the guest's access token itself (rule SP-9 style, see docs/architecture.md).
  // payment-service and notification-service deliberately stay without one - neither serves a public
  // HTTP API, the gRPC edge is protected by IAM and an ID token, and Kafka is internal.
  implementation(libs.spring.boot.starter.oauth2.resource.server)
  implementation(libs.spring.kafka)
  implementation(libs.flyway.core)
  // The ID token a Charge carries under the `cloud` profile (CloudRunAuthInterceptor).
  implementation(libs.google.auth.library.oauth2.http)
  // Spring gRPC builds the channel to payment-service from spring.grpc.client.channels. The shaded
  // transport replaces the unshaded one of the starter, so the Netty of the gRPC client is
  // independent of the Netty the Boot BOM manages - the same reason payment-service shades the
  // Netty of its server.
  implementation(libs.spring.grpc.client.spring.boot.starter) {
    exclude(group = "io.grpc", module = "grpc-netty")
  }
  runtimeOnly(libs.grpc.netty.shaded)
  runtimeOnly(libs.flyway.database.postgresql)
  runtimeOnly(libs.postgresql)
  // The SASL/OAUTHBEARER callback handler the `cloud` profile names by class: only a managed broker
  // asks for it, so nothing compiles against it. Its io.confluent transitive lives in the Confluent
  // repository, which this build does not declare and the handler does not need.
  runtimeOnly(libs.managed.kafka.auth.login.handler) { exclude(group = "io.confluent") }

  // Tests must not need an identity provider: spring-security-test authenticates a slice request,
  // and Nimbus signs a real RS256 token for the integration tests against a key pair generated in
  // the JVM (TestJwts).
  testImplementation(libs.spring.security.test)
  testImplementation(libs.nimbus.jose.jwt)
  testImplementation(libs.spring.boot.testcontainers)
  testImplementation(libs.testcontainers.junit.jupiter)
  testImplementation(libs.testcontainers.postgresql)
  // The outbox publisher is tested against a stub broker; its Kafka adapter against a real one.
  testImplementation(libs.testcontainers.kafka)
  // PSQLException: tests read SQLState and the violated constraint name.
  testImplementation(libs.postgresql)
}
