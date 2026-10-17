# 0.16.7 terminal controls and leaner APK

Kitty sessions now have a labelled Send button, Esc/Enter/Tab/arrows/page keys,
one-shot Shift/Ctrl/Alt modifiers, numbered choices, and explicit remote scroll
controls. Dragging the preview only pans locally. The preview preserves terminal
rows instead of soft-wrapping them, uses platform Unicode font fallback, and
removes colon-form CSI color codes and OSC sequences without corrupting text.

The key strip collapses to one horizontally scrollable row when the keyboard is
open. Send and part of the terminal remain visible at 320dp width with 200% text.
Mouse clicks use terminal cell coordinates and Kitty's native protocol encoder.
A terminal app that has not enabled mouse tracking rejects clicks without
receiving escape-sequence text; use arrows and Enter instead.

## Artifact

| Identity | Value |
|---|---|
| Package | `dev.avifenesh.hermes` |
| Version | `0.16.7` / versionCode `39` |
| APK | `Hermes-0.16.7.apk` |
| Bytes | `12751883` |
| APK SHA-256 | `b4388d46bc943a79ac0b5705eb79b9cd0987d97ff7fe2a35d26299c5a0ee2021` |
| Signer SHA-256 | `cdd83d31e6ea91a853a973ed8647cb3a6f5408d1301b2e1e6d06a04bd33a27ee` |

R8 and resource shrinking reduce the full APK from 40,211,429 bytes in 0.16.6 to
12,751,883 bytes, **68.29% smaller**. ARM64, ARMv7, x86 and x86_64 remain included.
No wake models, licenses or application features were removed. Native JNI and
distribution-boundary class identities have focused keep rules, not package-wide
exemptions.

Uploads now snapshot attachments to private temporary files and stream both the
request hash and wire body, rather than making whole-attachment byte-array copies.
The decoded-image cache counts bitmap backing allocation bytes and uses a
heap-derived budget. Memory-pressure callbacks evict entries without recycling
bitmaps still displayed by Compose. These are structural memory improvements,
not a claim of measured phone-wide RAM or battery savings.

## Gateway update and security

Install the matching complete bundled `server/mobile/` and activate it through
the gateway's supported restart control. Existing pairing and replay storage are
preserved. An older gateway still supports the existing reply path, but the new
terminal controls require the updated plugin. Native terminal interaction was
checked with the installed Kitty 0.45 API, not every Kitty version or TUI.

Every new terminal input requires a fresh biometric/device-credential approval,
even on a session that normally asks before commands. Its P-256 proof binds the
agent ID and SHA-256 of the exact raw JSON body under a separate `fleet-input`
domain. Existing persistent request and approval replay protection remains in use.
The backend rechecks the foreground process, PID start time and command identity.
Mouse and scroll requests bind a screen frame and reject stale geometry. A changing
screen can require refreshing and confirming again. No raw key sequence, shell
command, helper path or remote-control match is accepted from the API.

## Executed checks

- 69 full-debug JVM tests passed, including the existing 23 Markdown cases,
  streaming-body golden hashes, snapshot cleanup/cancellation and cell accounting.
- Four real Android tests passed for allocation-byte accounting, safe cache trim,
  real ICU grapheme boundaries, common wide emoji and narrow astral symbols.
- Fourteen intercepted-callback UI checks passed on an unpaired emulator: labelled
  disabled/enabled Send, named keys, one-shot Shift, numeric choice, mouse cell
  hit, local panning without terminal input, preserved reading position across
  refresh, remote scroll, actual keyboard send, unsupported mouse feedback, and
  320dp/200% text with the keyboard open, retained quick replies, legacy tmux/older
  gateway interruption, and interrupt visibility matching `can_stop`. Screenshots
  were checked in both themes. The legacy interrupt stays on its existing endpoint;
  the new Kitty key controls use the separately guarded input endpoint.
- Eight isolated aiohttp/P-256/SQLite/Kitty integration tests passed. Disposable
  Xvfb/Kitty children received real encoded input, including mouse-wheel events
  for both alternate-screen and main-buffer TUIs. Unsigned, wrong-body, replayed,
  malformed, stale-frame, read-only and wrong-foreground actions were rejected.
  The added main-buffer wheel regression failed before the routing correction.
- Both minified release flavors built. Full lint: zero errors, 67 warnings and
  27 hints. Play lint: zero errors, 64 warnings and 27 hints.
- Both signed release APKs passed flavor-boundary, signer, legal inventory and
  16 KiB ZIP alignment guards. Ten distribution-checker regression tests passed.
- The final signed full release was freshly installed and cold-launched
  successfully, then passed native create/process/snapshot/destroy linkage plus
  retained WorkManager reflection-constructor checks. The silence input is a JNI
  lifecycle fixture, not a wake-detection accuracy benchmark or a worker-job test.

## Limits

No physical-phone battery, biometric hardware, OEM, real FCM delivery or whole-app
security claim follows from these checks. UI callback fixtures and signed server
integration are separate checks, not one end-to-end physical-phone test. Play
submission and reviewer-backend hosting are not included. Release availability and
live gateway activation must be checked separately from this source report.
