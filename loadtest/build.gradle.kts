// JMH benchmarks. Run with:
//   ./gradlew :loadtest:run --args="TickBenchmark"
// JMH is wired through its annotation processor instead of a Gradle plugin: one less moving part.
plugins {
    application
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":host"))
    // The stress harness drives shards with the engine's random-book fixtures.
    implementation(testFixtures(project(":engine")))
    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

application {
    mainClass.set("org.openjdk.jmh.Main")
}

// The M4 concurrency stress harness:
//   ./gradlew :loadtest:stress                                   (1M events, in-memory logs)
//   ./gradlew :loadtest:stress --args="--redpanda localhost:19092" (the same through a real broker)
tasks.register<JavaExec>("stress") {
    group = "verification"
    description = "Interleaves ticks, orders, fills and closes on hot accounts; compares shards with the reference engine."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("riskengine.loadtest.stress.StressMain")
    jvmArgs("-Xmx6g")
    workingDir = rootDir
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

tasks.withType<JavaCompile>().configureEach {
    // JMH generates source that trips -Xlint; our own benchmark code is still compiled with lint on.
    options.compilerArgs.remove("-Werror")
}
