# Autosave & Project Persistence

**Status:** Implemented in source; Android debug Kotlin compilation passed during E1b/E1c. Persistence/lifecycle phone acceptance remains pending.

---

## 1. Lifecycle Triggers & Persistence Architecture

Fluxx implements a unified, non-blocking autosave pipeline routing all project writes through `ProjectSaveQueue`:

| Trigger | Implemented Behavior |
| :--- | :--- |
| **Editor Back Arrow** | If a layer is selected, deselects the layer first. On exiting the editor, commits active gestures, captures an immutable snapshot, and awaits write completion before transitioning to Projects. Exit is blocked if saving fails. |
| **System Back Gesture** | Routes through the identical editor callback to ensure changes are committed and saved before navigation. |
| **`MainActivity.onPause`** | Pauses preview playback and queues an asynchronous snapshot write without blocking the main UI thread. |
| **`MainActivity.onStop`** | Queues a second snapshot capturing any changes committed after pause and retrying earlier write failures. |
| **`onSaveInstanceState`** | Best-effort queued write before saving navigation state. |
| **Manual Save** | Routes through the same `ProjectSaveQueue`, preventing older background writes from racing or overwriting manual saves. |
| **Project Export** | Commits any in-flight gesture and flushes the project snapshot before initializing the export service. |

---

## 2. Serialization & Atomicity (`ProjectSaveQueue`)

- **Queue Ownership:** `ProjectSaveQueue` executes inside a process-scoped coroutine scope, guaranteeing in-flight writes complete even if the Activity or ViewModel is recreated.
- **Single Persistence Writer:** All writes delegate to `ProjectManager.saveProject(file, snapshot)`.
- **Atomic Operations:** Uses Android's `AtomicFile` writer to write to a temporary file before atomic renaming, protecting against file corruption during partial writes.
- **Reloading State:** Editor reload and Home/Projects opening both use `openProject()`, which awaits pending queue flushes before reading via `ProjectManager.loadProject(file)`.

---

## 3. Reliability Boundaries

- Asynchronous saving handles backgrounding and Activity configuration changes (rotations, theme changes).
- Abrupt OS termination (SIGKILL, battery pull, force-stop) during write IO cannot be completely prevented; recovery always restores the latest successfully completed atomic snapshot.

---

## 4. Phone Verification Checklist

- [ ] Make edits, tap editor Back, and reopen the project from Projects; verify changes are intact.
- [ ] Repeat using Android's system back gesture without selecting any layers.
- [ ] Edit a composition, background the app (home gesture), return, and verify state.
- [ ] Start a transform drag, background the app during the gesture, and confirm the committed state reloads without visual corruption.
