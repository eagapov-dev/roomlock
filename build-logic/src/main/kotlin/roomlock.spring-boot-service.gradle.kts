// Spring Boot service: Boot BOM, actuator + Prometheus, test starter, Jib image roomlock/<module>:dev.
// A service module only adds its own dependencies on top of this plugin.
plugins {
  id("roomlock.java-conventions")
  id("org.springframework.boot")
  id("io.spring.dependency-management")
  id("com.google.cloud.tools.jib")
}

val libs = the<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
  implementation(lib("spring-boot-starter-actuator"))
  runtimeOnly(lib("micrometer-registry-prometheus"))

  testImplementation(lib("spring-boot-starter-test"))
  testRuntimeOnly(lib("junit-platform-launcher"))
}

// Local images match the host CPU; CI overrides with -Djib.from.platforms=linux/amd64.
val hostPlatform =
  if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "amd64"

jib {
  from {
    image = "eclipse-temurin:21-jre"
    platforms {
      platform {
        architecture = hostPlatform
        os = "linux"
      }
    }
  }
  to { image = "roomlock/${project.name}:dev" }
  container { jvmFlags = listOf("-XX:MaxRAMPercentage=75") }
}
