// Everything that connects the engine to Redpanda: the EventLog implementation, the engine-out publisher,
// and the process that runs one shard thread per partition. The engine module itself stays dependency-free.
plugins {
    application
}

dependencies {
    implementation(project(":engine"))
    implementation("org.apache.kafka:kafka-clients:3.9.2")
    // kafka-clients logs through slf4j; give the standalone shard host somewhere to print it.
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(testFixtures(project(":engine")))
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:redpanda")
}

application {
    mainClass.set("riskengine.host.ShardHost")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
