// Java module with hand-written code: strict compilation (rule Q-3).
plugins { id("roomlock.java-base") }

tasks.withType<JavaCompile>().configureEach {
  options.compilerArgs.addAll(listOf("-Xlint:unchecked,deprecation", "-Werror"))
}
