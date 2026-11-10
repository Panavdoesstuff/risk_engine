// Plain Java 21, no framework in the hot path. No runtime dependencies on purpose.

plugins {
    // Shares the random-book test fixtures (riskengine.shard.Fixtures) with other modules' tests.
    `java-test-fixtures`
}

dependencies {
    testImplementation("net.jqwik:jqwik:1.9.3")
    // Test-only: proves Partitioner matches Kafka's own key hashing. The engine itself has no Kafka dependency.
    testImplementation("org.apache.kafka:kafka-clients:3.7.1")
}

tasks.withType<JavaCompile>().configureEach {
    // jqwik reads parameter names for nicer failure reports.
    options.compilerArgs.add("-parameters")
}
