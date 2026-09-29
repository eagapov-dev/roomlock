plugins { id("roomlock.spring-boot-service") }

dependencies {
  implementation(project(":common"))

  implementation(libs.spring.boot.starter.web)
}
