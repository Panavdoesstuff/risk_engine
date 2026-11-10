// JMH benchmarks. Run with:
//   ./gradlew :loadtest:run --args="TickBenchmark"
// JMH is wired through its annotation processor instead of a Gradle plugin: one less moving part.
plugins {
    application
}

dependencies {
    implementation(project(":engine"))
    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

application {
    mainClass.set("org.openjdk.jmh.Main")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

tasks.withType<JavaCompile>().configureEach {
    // JMH generates source that trips -Xlint; our own benchmark code is still compiled with lint on.
    options.compilerArgs.remove("-Werror")
}
