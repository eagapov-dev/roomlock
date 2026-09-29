plugins {
  `java-library`
  id("roomlock.java-conventions")
}

// Kafka contract records only; spring-core is needed solely for @Nullable (rule ST-8).
dependencies {
  compileOnly(platform(libs.spring.boot.dependencies))
  compileOnly(libs.spring.core)
}
