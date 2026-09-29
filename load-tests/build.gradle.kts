// Gatling scenarios live in src/gatling/java; the Gatling Java DSL comes with the plugin
// (gatling-charts-highcharts pulls gatling-core-java and gatling-http-java), so the module
// declares no dependencies of its own and the Gatling version stays pinned in one place.
//
// Run parameters are plain system properties of the Gradle invocation; the plugin propagates
// every non-JVM property to the forked Gatling JVM. See dev.roomlock.loadtest.LoadTestSettings:
//
//   ./gradlew :load-tests:gatlingRun --all \
//     -Droomlock.baseUrl=http://localhost:8090 -Droomlock.strategy=constraint \
//     -Droomlock.users=50 -Droomlock.duration=30 -Droomlock.rooms=20
//
// Reports land in load-tests/build/reports/gatling/<simulation>-<timestamp>/index.html.
plugins { id("roomlock.load-tests") }
