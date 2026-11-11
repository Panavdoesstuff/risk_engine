// The order API. Spring Boot lives here and only here: it validates, authenticates, rate-limits and writes
// OrderRequest events. It never computes margin; the shards do.
plugins {
    id("org.springframework.boot") version "3.5.16"
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation(project(":engine"))
    implementation(project(":host")) {
        // Spring Boot brings Logback; two slf4j bindings on one classpath is an error at startup.
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    implementation("org.apache.kafka:kafka-clients")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(testFixtures(project(":engine")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:redpanda")
    testImplementation("org.testcontainers:postgresql")
    testImplementation(project(":writers")) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    testImplementation("org.flywaydb:flyway-core")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
}

tasks.withType<Test>().configureEach {
    systemProperty("migrations.dir", rootDir.resolve("db/migrations").absolutePath)
}

tasks.withType<JavaCompile>().configureEach {
    // Spring's annotations are read at run time, so javac's "no processor claimed these annotations"
    // warning is noise here. Every other lint stays on, and warnings are still errors.
    options.compilerArgs.add("-Xlint:-processing")
    options.compilerArgs.add("-parameters")
}

// ./gradlew :api:devToken --args="trader alice A0000001"   (reads JWT_SECRET from the environment)
tasks.register<JavaExec>("devToken") {
    group = "application"
    description = "Prints a signed JWT for local use."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("riskengine.api.security.DevToken")
}
