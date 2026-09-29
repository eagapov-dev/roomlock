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
  implementation(libs.spring.kafka)
  implementation(libs.flyway.core)
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

  testImplementation(libs.spring.boot.testcontainers)
  testImplementation(libs.testcontainers.junit.jupiter)
  testImplementation(libs.testcontainers.postgresql)
  // The outbox publisher is tested against a stub broker; its Kafka adapter against a real one.
  testImplementation(libs.testcontainers.kafka)
  // PSQLException: tests read SQLState and the violated constraint name.
  testImplementation(libs.postgresql)
}
