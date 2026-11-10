rootProject.name = "risk-engine"

// Modules are added as their milestone starts (see docs/build-map.md).
// M1: engine (reference data), replayer (loaders + seeded book generator).
include("engine", "replayer")
