// Plain Java 21, no framework in the hot path. No runtime dependencies on purpose.

dependencies {
    testImplementation("net.jqwik:jqwik:1.9.3")
}

tasks.withType<JavaCompile>().configureEach {
    // jqwik reads parameter names for nicer failure reports.
    options.compilerArgs.add("-parameters")
}
