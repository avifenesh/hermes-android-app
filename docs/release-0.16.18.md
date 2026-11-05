# 0.16.18 Fixes before the Herms closed test

The first build sent to Google Play review as Herms for Hermes Agent. Same features as
0.16.17; these fixes came out of preparing the store listing.

## Fixes

- **Group chats.** Approve and Deny on a Bot's approval card draw filled again. They lost
  their background when the card finished loading and showed only their labels. Other
  buttons and switches that turn on after loading are fixed the same way.
- **Chat header.** The token count and cost stay whole. A long model name shortens
  instead, and on a narrow screen or with large text the numbers move under the model.
- **Herms (Play edition).** "Got it" on the AI notice is saved before the dialog closes,
  so the notice does not come back after a quick restart.

## Known issue

Turning the wake word off from its notification while Me > This phone > Voice is open
leaves the switch on, showing "Starting…". Nothing is listening. Leaving the tab and
coming back shows the switch off. The fix ships in the next release.

## Server plugin

No changes from 0.16.17 apart from the version. A gateway already on the 0.16.17 plugin
works with this app.

## Herms on Google Play

This build is in Google's review for the closed test. Join
https://groups.google.com/g/herms-testers to be invited when it opens. The sideload app on
this page stays "Hermes" and can be installed next to it.

## Artifact

| Identity | Value |
|---|---|
| Package | `dev.avifenesh.hermes` |
| Version | `0.16.18` / versionCode `50` |
| APK | `Hermes-0.16.18.apk` |
| Bytes | `14891319` |
| APK SHA-256 | `5d118f640b741414fd63381ba3e2623f525e224c6260b084fd70d50073352d8a` |
| Signer SHA-256 | `cdd83d31e6ea91a853a973ed8647cb3a6f5408d1301b2e1e6d06a04bd33a27ee` |

## Executed focused verification

- JVM unit tests: full 205 and Play 259, all passing; release lint reported zero errors
  in both editions. New tests cover the header's model and metrics split, when the
  metrics move under the model, and the AI notice's synchronous save. The AI notice test
  fails on the code before its fix.
- Instrumented tests on the full debug build passed, including a new pixel comparison of
  a room approval card that turns ready after loading against one that is ready from the
  start. The wake-word reference parity test now skips, instead of failing, when the
  optional reference library is not built. The configured-recording test skips when no
  recording is supplied.
- Script suites passed: Play qualification 23, distribution 22, legal inventory 24, and
  the report Worker's 16.
- Signed full release APK: distribution guard and legal inventory passed with the
  approved signer. The Play bundle and its bundle-generated universal APK passed Play
  qualification (identity, signer, 16 KiB alignment, ABIs, legal notices, packaged policy,
  build provenance).
- These exact release APKs on an Android 15 emulator, paired to an isolated synthetic
  sandbox: the room approval card from a cold start in the Night and Day themes on both
  editions; the chat header on a normal screen and at 320 dp with font scale 2.0, in
  English and Hebrew, idle and running, where the wrapped line ends with the separator
  and the amount starts the next line; the Play AI notice stays acknowledged after a
  force stop 89 ms after "Got it"; Play pairing, streamed replies and an approval through
  the PIN prompt; 16 cold starts with no crash or ANR in logcat.
- The Play foreground-service demo videos were recorded on the bundle-generated Play
  APK: Talk and the wake word with their notifications and stop actions, and a run's
  progress notification through an approval until it ends by itself.

These are focused checks, not physical-phone QA. Install the complete matching
`server/mobile/` and restart through the supported gateway control.
