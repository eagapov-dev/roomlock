plugins { id("roomlock.spring-boot-service") }

dependencies {
  implementation(project(":common"))

  implementation(libs.spring.boot.starter.web)
  implementation(libs.spring.boot.starter.data.jpa)
  implementation(libs.spring.kafka)
  implementation(libs.flyway.core)
  runtimeOnly(libs.flyway.database.postgresql)
  runtimeOnly(libs.postgresql)
  // The SASL/OAUTHBEARER callback handler the `cloud` profile names by class: only a managed broker
  // asks for it, so nothing compiles against it. Its io.confluent transitive lives in the Confluent
  // repository, which this build does not declare and the handler does not need.
  runtimeOnly(libs.managed.kafka.auth.login.handler) { exclude(group = "io.confluent") }

  testImplementation(libs.spring.boot.testcontainers)
  testImplementation(libs.testcontainers.junit.jupiter)
  testImplementation(libs.testcontainers.postgresql)
  testImplementation(libs.testcontainers.kafka)
  testImplementation(libs.awaitility)
}
