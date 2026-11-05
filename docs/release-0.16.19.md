# 0.16.19 Wake-word switch follows Turn off

One fix on top of 0.16.18, for both the full app and Herms on Google Play.

## Fix

- **Wake word.** Turning the wake word off from its notification now turns the switch in
  Me > This phone > Voice off too, also when that tab is open. Before, the switch stayed
  on and read "Starting…" although nothing was listening.

## Server plugin

No changes from 0.16.18 apart from the version. A gateway already on the 0.16.17 or
0.16.18 plugin works with this app.

## Herms on Google Play

The closed test is open: join https://groups.google.com/g/herms-testers, then open
https://play.google.com/apps/testing/dev.avifenesh.hermes.play and tap Become a tester.
Details and feedback: https://github.com/avifenesh/hermes-android-app/issues/41. The
sideload app on this page stays "Hermes" and can be installed next to it.

## Artifact

| Identity | Value |
|---|---|
| Package | `dev.avifenesh.hermes` |
| Version | `0.16.19` / versionCode `51` |
| APK | `Hermes-0.16.19.apk` |
| Bytes | `14891319` |
| APK SHA-256 | `0a81b87c7f4b2440feaeef1325f249ab0599245e29c572618fd85cf841ed9df8` |
| Signer SHA-256 | `cdd83d31e6ea91a853a973ed8647cb3a6f5408d1301b2e1e6d06a04bd33a27ee` |

## Executed focused verification

- A new instrumented test sends the wake-word notification's own Turn off to the service
  with the Voice tab open, once while listening and once while starting, and reads the
  switch from the accessibility tree. The listening case fails on 0.16.18's panel, the
  starting case fails on the first version of the fix, and both pass on the full and Play
  debug builds.
- JVM unit tests: full 205 and Play 259, all passing; lint reported zero errors in both
  editions. The instrumented suites ran 24 tests per edition: 21 pass and 3 skip by
  assumption (the opt-in live receiver, the spoken-recording fixture and reference parity).
- Signed full release APK: distribution guard and legal inventory passed with the
  approved signer. The Play bundle and its bundle-generated universal APK passed Play
  qualification. The bundle manifest's permissions and service types are unchanged from
  0.16.18.
- These exact release APKs on an Android 15 emulator, paired to the hosted synthetic
  review sandbox: Turn off from the notification with the Voice tab open, while listening
  and 2 s after enabling, on both editions; the room approval card filled in the Night and
  Day themes; an approval through the PIN prompt; streamed replies; the Play AI notice
  stays acknowledged after a force stop; 18 cold starts with no crash or ANR in logcat.

These are focused checks, not physical-phone QA. Install the complete matching
`server/mobile/` and restart through the supported gateway control.
