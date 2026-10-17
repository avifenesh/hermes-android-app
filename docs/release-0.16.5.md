# 0.16.5 full APK verification

This client-only update stops screen-owned network polling and elapsed timers when the activity is stopped. Fleet, Pulse and Cron polling also stops beneath navigation overlays or the compose sheet. Returning to the screen restarts its work. Background alarm handling and intentional Talk and wake-word services are unchanged.

| Identity | Value |
|---|---|
| Package | `dev.avifenesh.hermes` |
| Version | `0.16.5` / versionCode `37` |
| APK file | `Hermes-0.16.5.apk` |
| APK SHA-256 | `37f658750c225eddde518d17c5da5193d2e3013d79b74ce841206853f5fdc91b` |
| Signer SHA-256 | `cdd83d31e6ea91a853a973ed8647cb3a6f5408d1301b2e1e6d06a04bd33a27ee` |

## Executed checks

- Full release build succeeded. All 36 full-debug JVM tests passed. Full-release lint reported zero errors, 65 warnings and 27 hints.
- A focused debug-only emulator regression failed with plain composition-owned polling: work continued after the retained activity stopped. With lifecycle-bound polling, the same test passed stop, resume, key replacement and disposal checks, with zero background ticks and no overlapping workers. A fresh ad-hoc rerun also passed.
- Ten distribution-boundary tests passed. The signed release passed distribution/signature, legal inventory and 16 KiB ZIP alignment guards. Debug polling fixtures are absent from release DEX.
- The signed full release installed and cold-launched to the unpaired entry screen on the emulator.
- Independent source review found no blocking lifecycle or cancellation regressions.

## Limits

These are focused emulator, source and artifact checks, not a full device-qualification suite. No physical-phone battery reduction was measured. Always-on wake-word recording and an active manual Talk session can still consume power intentionally. A stalled Talk wait can retain its existing partial wake lock until its 30-minute acquisition timeout. This update does not change those service policies or voice-note recording behavior.

The bundled server plugin and wire contract are unchanged. No live gateway deployment, Play submission or reviewer-backend deployment is included.
