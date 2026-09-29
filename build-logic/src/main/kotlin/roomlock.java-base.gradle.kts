// Shared Java setup for every module: toolchain, formatting, JUnit Platform, JaCoCo report.
// Modules with hand-written code apply roomlock.java-conventions instead (adds -Werror).
plugins {
  java
  jacoco
  id("com.diffplug.spotless")
}

val libs = the<VersionCatalogsExtension>().named("libs")

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion)
  }
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

spotless {
  java {
    target("src/*/java/**/*.java")
    googleJavaFormat(libs.findVersion("google-java-format").get().requiredVersion)
  }
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
  reports {
    xml.required = true
    html.required = true
  }
}
