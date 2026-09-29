// Gatling (Java DSL) scenarios in src/gatling/java; run manually with ./gradlew :load-tests:gatlingRun.
// gatlingRun is not wired into `build` (rule Q-1).
plugins {
  id("roomlock.java-conventions")
  id("io.gatling.gradle")
}
