plugins { id("roomlock.spring-boot-service") }

dependencies {
  // One version for every io.grpc artifact: grpc-java releases its modules together, and the
  // starter below would otherwise put an older transport next to the pinned stubs.
  implementation(platform(libs.grpc.bom))

  implementation(project(":payment-api"))
  implementation(project(":common"))

  implementation(libs.spring.boot.starter.web)
  implementation(libs.spring.boot.starter.data.jpa)
  implementation(libs.spring.kafka)
  implementation(libs.flyway.core)
  // Spring gRPC serves the contract. The shaded transport replaces the unshaded one of the starter,
  // so the Netty of the gRPC server is independent of the Netty the Boot BOM manages for the web
  // server.
  implementation(libs.spring.grpc.server.spring.boot.starter) {
    exclude(group = "io.grpc", module = "grpc-netty")
  }
  runtimeOnly(libs.grpc.netty.shaded)
  runtimeOnly(libs.flyway.database.postgresql)
  runtimeOnly(libs.postgresql)

  // @LocalGrpcPort: the port the server bound after being started on port 0.
  testImplementation(libs.spring.grpc.test)
  testImplementation(libs.spring.boot.testcontainers)
  testImplementation(libs.testcontainers.junit.jupiter)
  testImplementation(libs.testcontainers.postgresql)
  testImplementation(libs.testcontainers.kafka)
  testImplementation(libs.awaitility)
}
