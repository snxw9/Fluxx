# Fluxx

Android motion graphics compositor built with Jetpack Compose, Kotlin and a native C++/Vulkan engine. Layered editing/rendering and transform animation are implemented, and the text phase is complete (native shaping, SDF rendering, rich and paragraph text, the animator engine and the Edit Text / Animate UI; see the [text record](docs/checkpoints/TEXT.md)). The graph editor is in progress: G1 (the easing backend with Hold, Custom bezier, Bounce and Elastic, and the Ease view) is implemented in source, with the page layout reworked in G1.1 a curve library (pinned curves, user curves, import/export) added in G2L, the Value graph (handles, pan/zoom) in G2, and the A16 acceptance fixes in G2.1 (page units, live value, transport timecode, library drag reflow, magnetic Transform detents, hex entry); TL1 made timeline scrubbing frame-exact with a tiered ruler ([TL1](docs/checkpoints/TL1.md)); and G3 added the Speed graph with Smooth through key ([G3](docs/checkpoints/GRAPH_G3.md)), made stable and snappier in [G3.1](docs/checkpoints/GRAPH_G3_1.md); see the [GRAPH G1](docs/checkpoints/GRAPH_G1.md), [G1.1](docs/checkpoints/GRAPH_G1_1.md), [G2L](docs/checkpoints/GRAPH_G2L.md), [G2](docs/checkpoints/GRAPH_G2.md) and [G2.1](docs/checkpoints/GRAPH_G2_1.md) records. The full feature vision is broader than the current app.

- [Documentation index](docs/README.md): feature owners and verification records.
- [Roadmap](docs/architecture/FLUXX_ROADMAP.md): active phase, planned scope and gates.
- [Phase 2 status](docs/checkpoints/PHASE_2_STATUS_AND_BACKEND.md): implemented controls versus placeholders.
- [Workspace rules](AGENTS.md): build, device and editing workflow.

`app/` owns Compose UI, project state and media/export orchestration. `core-engine/` owns native rendering/audio and CPU text, plus checksummed vendored sources and licensed font assets. `tools/` contains verification/fixture utilities; generated results stay ignored. `docs/` holds the product documentation.

Update the existing feature reference for follow-ups rather than creating revision documents. Builds/packaging and device installation require explicit user authorization; the usual workflow is user builds and phone testing in Android Studio.
