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

// Where the image goes. The default is the local Docker daemon of a developer -
// roomlock/<service>:dev, which is the name infra/docker-compose.yml expects, so `jibDockerBuild`
// and the compose stack of the README keep working with no properties at all. A CI build publishes
// into Artifact Registry by naming the repository and the tag instead:
//
//   ./gradlew jib -Pimage.repository=<region>-docker.pkg.dev/<project>/<repo> -Pimage.tag=<sha>
//
// Only that one immutable tag is pushed, never a moving `latest`. Cloud Run pins a revision to the
// digest it deployed, so `latest` would never be what serves traffic: it would only make "which
// commit is running" a question with two answers and invite a deploy of whatever happens to be
// newest. Rolling back is a revision of Cloud Run, not a tag of the registry (docs/deployment.md).
//
// Credentials for a push come from JIB_TO_USERNAME / JIB_TO_PASSWORD, which the deploy workflow
// fills with a short-lived access token of Workload Identity Federation: no key file, no login.
val imageRepository = providers.gradleProperty("image.repository").orElse("roomlock")
val imageTag = providers.gradleProperty("image.tag").orElse("dev")

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
  to { image = "${imageRepository.get()}/${project.name}:${imageTag.get()}" }
  container { jvmFlags = listOf("-XX:MaxRAMPercentage=75") }
}
