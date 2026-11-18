# TEXT FIX 9: font import regression, Playfair overlap hole, sharp imported/animated glyphs

Device-assisted pass on the A16 (SM-A165F), with the user present. Linked from `TEXT_UI_OVERHAUL.md`.

## 9a. Font import regression (fixed, device-confirmed)

### Symptom

Importing any font (static or variable, including delete then re-import) did nothing visible:

- no new family in the Fonts list;
- search found nothing;
- no error line.

### Evidence (logcat on the A16)

Debug build, with temporary `FluxxFonts` log lines on the import path:

```
18:33:55.103 D/FluxxFonts: page composed
18:33:57.723 I/ActivityTaskManager: START u0 {act=android.intent.action.OPEN_DOCUMENT … PickActivity} from uid 10352 (com.fluxx.android.debug)
18:33:59.654 D/FluxxFonts: ON_STOP cancelAll mode=Fonts
18:34:00.157 D/FluxxFonts: page disposed
```

What did not happen:

- No `picker result` line followed.
- No `:font_worker` process was started.
- `files/fonts/index.json` stayed at 24 bytes (an empty library).

In the first, uninstrumented capture the window manager logged `appStopped=true` for `MainActivity` at 18:28:37.467, while the picker was in front.

### Root cause

`TextInspectorPanel` registers an `ON_STOP` observer that calls `cancelAll()`. Since TEXT UI 5 (`769e4bf`, which turned the Fonts popup into the in-sheet Fonts page), `cancelAll()` ended with `mode = Browse`.

The system document picker is a full-screen activity, so opening it stops `MainActivity`. That leads to this chain:

1. `ON_STOP` fires `cancelAll()`.
2. The inspector leaves the Fonts page.
3. `TextFontsPage` is disposed, which unregisters its `rememberLauncherForActivityResult` launcher.
4. When the picker returns its URI there is no registered callback, so the result is dropped.

`FontLibraryRepository.import` never ran, and no error was possible. The worker, the native library, `TextIdlePrewarm` and `SdfDiskCache` were not involved; the device log shows the worker was never started.

### Fix

- `cancelAll()` still cancels every live draft (numeric, colour, Source Text, axis drag), but keeps the Fonts page open (`TextInspectorLifecycle.afterStop`).
- Imports now run in the repository's own scope (`FontImportRunner` inside `FontLibraryRepository.startImport`), not a composition scope. Leaving the page or recomposing can no longer cancel an import or lose its result.
- The outcome is published as `FontLibraryRepository.importStatus`: `Idle` / `Importing` / `Imported(family, faces)` / `Failed(message)`.
- If the picker result arrives after activity recreation, the page is restored by `rememberSaveable` and the launcher re-registers with its saved key.
- The index merge and remove arithmetic is extracted into `FontLibraryIndex`, so it can be tested on the JVM.
- The idle prewarm after import is wrapped so it can never fail or stall an import.

### Visible-error rules (`TextFontsModel.statusLines`)

1. Every status line is shown, in this order:
   1. page error (load / delete);
   2. import failure;
   3. "Importing font…" or "Imported *family* (N faces)";
   4. the requested-font-missing notice.

   Before this fix, an ordered `when` let the missing-font notice hide the error.
2. Error lines stay until tapped (dismiss) or until the next import or delete action.
3. The "Imported …" confirmation clears after 4 s (`CONFIRMATION_MS`).
4. Every `Throwable` is reported, not only `Exception`, via `FontImportErrors.message`:
   - `UnsatisfiedLinkError` → "Font engine unavailable (…)".
   - `OutOfMemoryError` → "Not enough memory…".
   - Anything without a message → "Font import failed (*class*)".
5. Worker calls (inspect / prepare) are bounded by `WORKER_TIMEOUT_MS = 60 000 ms`. On timeout the call is cancelled, its binding is released, and the status becomes "Font worker did not respond within 60 s; the font was not imported". `importing` therefore cannot stay true forever.

### Device confirmation

After installing the fix, the user imported:

- a static Playfair Display;
- the variable Playfair Display (`wght` axis).

Both appeared in the list and applied. On the device afterwards:

- `index.json` was 913 bytes and listed both files;
- the `:font_worker` process started at 19:07:43;
- there were no crashes.

### Tests

`FontImportTest`, 7 tests:

- the picker no longer closes the Fonts page;
- error-priority rendering, including an import error while a missing-font reference exists;
- worker timeout;
- non-`Exception` failures;
- the import survives the composition leaving;
- delete then re-import of the same sha256 shows the family again;
- static, variable and TTC fixtures.

Total: JVM 364 (357 previous + 7), 0 failures.
