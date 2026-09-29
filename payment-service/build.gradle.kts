plugins { id("roomlock.spring-boot-service") }

dependencies {
  implementation(project(":payment-api"))

  implementation(libs.spring.boot.starter.web)
}
