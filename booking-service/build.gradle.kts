plugins { id("roomlock.spring-boot-service") }

dependencies {
  implementation(project(":payment-api"))
  implementation(project(":common"))

  implementation(libs.spring.boot.starter.web)
  implementation(libs.spring.boot.starter.data.jpa)
  implementation(libs.spring.boot.starter.validation)
  implementation(libs.springdoc.openapi.starter.webmvc.ui)
  implementation(libs.spring.kafka)
  implementation(libs.flyway.core)
  runtimeOnly(libs.flyway.database.postgresql)
  runtimeOnly(libs.postgresql)

  testImplementation(libs.spring.boot.testcontainers)
  testImplementation(libs.testcontainers.junit.jupiter)
  testImplementation(libs.testcontainers.postgresql)
  // The outbox publisher is tested against a stub broker; its Kafka adapter against a real one.
  testImplementation(libs.testcontainers.kafka)
  // PSQLException: tests read SQLState and the violated constraint name.
  testImplementation(libs.postgresql)
}
