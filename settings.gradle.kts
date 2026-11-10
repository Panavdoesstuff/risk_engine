rootProject.name = "risk-engine"

// Modules are added as their milestone starts (see docs/build-map.md).
// M1: engine (reference data), replayer (loaders + seeded book generator).
// M3: loadtest (JMH benchmarks; load scripts arrive in M6).
include("engine", "replayer", "loadtest")
