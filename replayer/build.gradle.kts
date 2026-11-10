plugins {
    application
}

dependencies {
    implementation(project(":engine"))
}

application {
    mainClass.set("riskengine.replayer.Cli")
}

tasks.named<JavaExec>("run") {
    // Resolve relative CLI paths against the repo root, not replayer/.
    workingDir = rootDir
}
