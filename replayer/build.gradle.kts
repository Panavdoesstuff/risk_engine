plugins {
    application
}

dependencies {
    implementation(project(":engine"))
    // For `ticks --redpanda`: the replayer is the only tick writer, on files or on the broker.
    implementation(project(":host"))
}

application {
    mainClass.set("riskengine.replayer.Cli")
}

tasks.named<JavaExec>("run") {
    // Resolve relative CLI paths against the repo root, not replayer/.
    workingDir = rootDir
}
