// M7: the crash-day report. Replays 4 June 2024 against the seeded book with the production shard code,
// in-process and deterministic, and writes docs/crash-day-2024-06-04.md with its chart and curve.
//   ./gradlew :report:crashDay
plugins {
    application
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":host"))
    testImplementation(testFixtures(project(":engine")))
}

application {
    mainClass.set("riskengine.report.CrashDayRun")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
    jvmArgs("-Xmx6g")
}

tasks.register<JavaExec>("crashDay") {
    group = "verification"
    description = "Replays 4 June 2024 against the seeded book and writes docs/crash-day-2024-06-04.md."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("riskengine.report.CrashDayRun")
    jvmArgs("-Xms6g", "-Xmx6g")
    workingDir = rootDir
}
