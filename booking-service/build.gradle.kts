plugins { id("roomlock.spring-boot-service") }

dependencies {
  implementation(project(":payment-api"))
  implementation(project(":common"))

  implementation(libs.spring.boot.starter.web)
}
