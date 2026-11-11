// engine-out -> Postgres. Plain Java: a Kafka consumer, JDBC batches, no framework.
plugins {
    application
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":host"))
    implementation("org.apache.kafka:kafka-clients:3.9.2")
    implementation("org.postgresql:postgresql:42.7.8")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(testFixtures(project(":engine")))
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:redpanda")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.flywaydb:flyway-core:10.20.1")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql:10.20.1")
}

application {
    mainClass.set("riskengine.writers.Main")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

tasks.withType<Test>().configureEach {
    // Tests apply the real migrations.
    systemProperty("migrations.dir", rootDir.resolve("db/migrations").absolutePath)
}
