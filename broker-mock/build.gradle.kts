// A stand-in for the broker / exchange, with deliberate misbehaviour (timeouts, rejects, slow and
// duplicated callbacks). Plain Java and the JDK's HTTP server.
plugins {
    application
}

dependencies {
    implementation(project(":host")) // FlatJson only
}

application {
    mainClass.set("riskengine.broker.MockBroker")
}
