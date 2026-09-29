pluginManagement {
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories { mavenCentral() }
}

rootProject.name = "roomlock"

include(
  "booking-service",
  "payment-service",
  "payment-api",
  "common",
)
