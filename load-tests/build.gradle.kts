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
// The API takes no anonymous callers, so a run fetches one access token before it starts and sends it
// with every request (dev.roomlock.loadtest.LoadTestToken). The defaults point at the Keycloak of
// infra/docker-compose.yml; -Droomlock.tokenUrl / -Droomlock.clientId / -Droomlock.clientSecret /
// -Droomlock.scope point it at any other issuer, and -Droomlock.token skips the exchange with a token
// that was minted elsewhere.
//
// Reports land in load-tests/build/reports/gatling/<simulation>-<timestamp>/index.html.
plugins { id("roomlock.load-tests") }
