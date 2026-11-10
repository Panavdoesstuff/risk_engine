rootProject.name = "risk-engine"

// Modules are added as their milestone starts (see docs/build-map.md).
// M1: engine (reference data), replayer (loaders + seeded book generator).
// M3: loadtest (JMH benchmarks; load scripts arrive in M6).
// M4: host (Redpanda event log + the process that runs one shard per partition), api (Spring Boot order API).
include("engine", "replayer", "loadtest", "host", "api")
