# Fluxx

Android motion graphics compositor built with Jetpack Compose, Kotlin and a native C++/Vulkan engine. Layered editing/rendering and transform animation are implemented; text currently has a CPU shaping/SDF foundation. The full feature vision is broader than the current app.

- [Documentation index](docs/README.md): feature owners and verification records.
- [Roadmap](docs/architecture/FLUXX_ROADMAP.md): active phase, planned scope and gates.
- [Phase 2 status](docs/checkpoints/PHASE_2_STATUS_AND_BACKEND.md): implemented controls versus placeholders.
- [Workspace rules](AGENTS.md): build, device and editing workflow.

`app/` owns Compose UI, project state and media/export orchestration. `core-engine/` owns native rendering/audio and CPU text, plus checksummed vendored sources and licensed font assets. `tools/` contains verification/fixture utilities; generated results stay ignored. `docs/` holds the product documentation.

Update the existing feature reference for follow-ups rather than creating revision documents. Builds/packaging and device installation require explicit user authorization; the usual workflow is user builds and phone testing in Android Studio.
