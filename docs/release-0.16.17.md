# 0.16.17 Seventeen languages, voice notes and group chats

The app now speaks 17 languages besides English: Afrikaans, Arabic, Chinese (Simplified and
Traditional), French, German, Hebrew, Hungarian, Irish, Italian, Japanese, Korean,
Portuguese, Russian, Spanish, Turkish and Ukrainian. Pick one in This phone > Look > App
language, or in Android's per-app language settings; it follows the system language by
default. Dates, times, durations, counts and amounts follow the chosen language. Arabic and
Hebrew lay the app out right to left; chat content keeps its own direction.

0.16.14 to 0.16.16 were never published on their own; this release carries them.

## What's new

- **Voice notes in chat.** Sent and received voice messages play in the bubble: play and
  pause, seek on the waveform, elapsed and total time, 1x/1.5x/2x. Long or metered clips
  load on tap. Each agent run opens with a small orb, which breathes while the agent works.
- **Group chats.** Rooms that Hermes Desktop runs in Bot Mode appear in the Inbox (with a
  gateway that serves `features.rooms`): unread counts, a live dot while a Bot answers,
  each Bot's name on its messages, files, approvals with the fingerprint or PIN prompt,
  retry and discard of a deferred turn, and new, rename, delete and stop.
- **Live sessions.** Sessions from other apps open as a live, interactive viewer, and the
  Fleet screen shows sessions on remote machines.
- **Approvals in your language.** Approval buttons use the app's own labels instead of the
  gateway's.

## Fixes

- Fleet Send no longer draws as a black button while the draft is empty.
- Pairing: Cancel cancels, a re-opened link retries, retries bind to the same server, and a
  Keystore signing failure fails the pairing instead of the app.
- Mirrored group, forum and channel sessions are read-only on the phone; deliveries of taken
  chats are kept.
- "Restart to apply" is owned by the server and clears after a restart from any surface.
- Model labels stay distinct when several routes share a model ("opus 5.5 (global)").
- Markdown blocks lay out in their own text direction.
- Wake: without exact alarm access, the alarm screens say exact alarms are off instead of
  showing a next ring time.
- The cron delivery row and the Me status badge read correctly in every language.
- Hebrew and Arabic: interface text reads right to left even when a line starts with a Latin
  name; chat content, names and titles keep their own direction; code, commands and paths
  stay left to right. Usage rows, model chips, previews and approval cards no longer
  scramble, and Arabic titles no longer break before their last letter.
- Counts and badges use the app language's digits, and amounts always show "$".

## Server plugin

New in the plugin: the `/v1/rooms` routes for group chats, the voice transcript on
voice messages, profile children that stop with the adapter (Stop ends the whole process
group), per-profile inactivity limits, and pending room actions found in rooms no phone
watched.

## Herms on Google Play

A Play edition, **Herms for Hermes Agent**, with its own icon (the Seal), is being prepared
for a Google Play closed test. Join https://groups.google.com/g/herms-testers to be
invited when the test opens. The sideload app on this page stays "Hermes" and can be
installed next to it.

## Artifact

| Identity | Value |
|---|---|
| Package | `dev.avifenesh.hermes` |
| Version | `0.16.17` / versionCode `49` |
| APK | `Hermes-0.16.17.apk` |
| Bytes | `14874931` |
| APK SHA-256 | `3a2f5cf3d4dc5b382d34e12ce351427c68630171dd04431dd942eef62a194da5` |
| Signer SHA-256 | `cdd83d31e6ea91a853a973ed8647cb3a6f5408d1301b2e1e6d06a04bd33a27ee` |

## Executed focused verification

- JVM unit tests: full 201 and Play 254, all passing; release lint reported zero errors
  in both editions. New tests for the bidi rules, room previews, read-only notices, the AI
  notice, the cron delivery copy, approval labels and the Hebrew maqaf fail on the code
  before their fixes.
- Server integration suites passed: rooms 26, model control 25, replay security 25,
  mirror delivery 21, fleet terminal 16, phone control 15, voice transcript 15, hub
  catalog 13, pairing retry 12, cron reply context 11, momentum report 10, profile
  working 11, restart state 5, live session 5, skills catalog 1, self-check imports 6.
- Signed full release APK: distribution guard and legal inventory passed with the
  approved signer. The Play bundle and its bundle-generated universal APK passed Play
  qualification (identity, signer, 16 KiB alignment, ABIs, legal notices, packaged policy,
  build provenance).
- These exact release APKs on an Android 15 emulator, paired to an isolated synthetic
  sandbox: launcher icons and themed icons for both editions, pairing, a streamed reply,
  an approval, the AI notice on Play only, Arabic amounts and digits, Hebrew inbox, chat
  and Usage screens, and cold starts with no crash or ANR in logcat. Earlier passes on
  the same code covered all 17 languages' pickers and the right-to-left fixes in Hebrew
  and Arabic, plus Japanese and German layouts.

These are focused checks, not physical-phone QA. Install the complete matching
`server/mobile/` and restart through the supported gateway control; group chats and voice
transcripts need the new plugin.
