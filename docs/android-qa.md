# Android client verification

## Supported build

- Android 12+ (API 31 minimum), target API 36, compile SDK 37; the 0.16.4 / versionCode 36 candidate has [its own focused verification](release-0.16.4.md). The historical checks below apply only to their named versions.
- Universal release APK: ARM64, ARMv7, x86, x86-64.
- Camera, microphone and step sensor are optional. Text chat and pasted-link pairing do not require them.
- Google Play services are optional for the core client. QR scanning and Firebase push depend on them. Live sync is subject to Android foreground-service limits; periodic WorkManager sync remains available.
- GitHub full APK is the release target. Full and Play have historical development builds; [source-set boundaries](distribution-flavors.md) are not a Play Store compliance/approval claim. Play and reviewer hosting are deferred.

## Public phone harness and Hermes baseline

The self-contained `scripts/phone_control_integration.py` passed **15 tests, zero failures** against Hermes source revision `9706739432c599bb04eb970eac9229c4b7a1f2e1`, using the installed Hermes managed Python **3.14.7**. The checkout's tracked sources were unchanged; one unrelated untracked test file was present. This establishes a focused source/runtime baseline, not compatibility with a named Hermes release tag or every plugin API. No minimum tagged Hermes version has been established.

The harness was invoked with an inherited production-home value and project plugins enabled in the parent environment to test its override. Before bootstrap or any application import, it unconditionally replaces `HERMES_HOME` with a temporary home, disables project-plugin discovery and lazy dependency installation/relaunch, and rejects a pre-bootstrapped process. Its Store path must resolve under that temporary home. It never invokes plugin discovery or a gateway. Cleanup was checked after process exit: no test scratch remained.

From the repository root, set `HERMES_PYTHON` to the installed Hermes managed-runtime executable, then run:

```sh
"$HERMES_PYTHON" scripts/phone_control_integration.py
```

The source checkout is expected at `~/.hermes/hermes-agent`. Do not wrap the script in an already bootstrapped Python process. A stale checkout `venv/bin/python` may have a different Python ABI from the managed dependencies; use the actual managed runtime, not system Python. The script intentionally does not install/repair dependencies automatically.

Coverage is real SQLite storage, loopback aiohttp routes, P-256 request signatures, biometric arm guards, target-device hints/results, command expiry/serialization/cancellation, revocation and tool-thread dispatch. FCM credentials/network are substituted. This is not physical Android control, real FCM delivery, a production security/deployment check, or fresh-clone installation certification. Prior 15-test phone and 25-test replay logs remain historical evidence, not an independently identified Hermes release baseline. See [replay checks](request-replay-security.md#offline-verification) and [security requirements](../SECURITY.md).

## Local checks

From `android`, with JDK 21:

```sh
./gradlew --max-workers=2 :app:assembleFullDebug :app:assemblePlayDebug :app:assembleFullRelease :app:assemblePlayRelease :app:testFullDebugUnitTest :app:testPlayDebugUnitTest :app:lintFullRelease :app:lintPlayRelease
./gradlew --stop
```

Use the pinned NDK/CMake tools in the [migration guide](wake-engine-migration.md). First native configuration needs network for pinned archives; Gradle `--offline` alone does not populate CMake FetchContent. Lint errors fail the build. The JVM tests cover existing chat-list logic, capability/session generations and cancellation of real loopback HTTP requests. They do not cover all Android lifecycle behavior.

## UI coverage

The 0.16.0 audit used an isolated Android 15 emulator and a separate test server/home. No production chat, phone or plugin deployment was used for testing.

| Area | Screens inspected |
| --- | --- |
| Main | Inbox, Pulse, Cron, Me |
| Conversations | Chat, Run, tool Activity, Session, Elsewhere, Search, Talk |
| Scheduling | Job output, Cron editor, Wake settings |
| Agent | Settings, All settings, Extensions, Skill, Memory, Usage |
| Workspace | Fleet agent, Projects, repository detail, Radar |
| Hub | Catalog, skill detail, plugin detail, MCP detail |
| Additional | Pairing, new-work composer, permissions and confirmation surfaces |

All 25 routed screens, four main tabs and the composer were captured in both themes. Targeted checks also used 320dp width, 200% text, landscape and a wide viewport. The inventory includes loading, empty and error states. A rendered fixture screen is not proof that its external integration works.

Focused emulator checks exercised:

- Signed pairing and chat sends through the real isolated adapter/store, with an explicitly synthetic reply handler rather than a model call.
- Feature-off and legacy-server behavior, stopped Fleet polling, shortcut fallback and hidden WhatsApp controls without removing plain alarms.
- Chat draft preservation over rotation and a Chat → Run → Chat round trip.
- Accessible controls, non-overlapping compact navigation, scrollable large-text composer actions, and microphone denial.
- Retry after a failed usage request and repository diff switching to an empty file without stale content. These responses came from fixtures.
- First-pair notification prompting and retaining newly committed pairing credentials after screen disposal/rotation.
- Android 15 foreground-service timeout: reproduced the process crash before the fix, then verified clean shutdown with an emulator-only shortened timeout. The override was removed afterward.
- Alarm ownership reset using real Android PendingIntents: cancel old alarm, reject stale intent, clear cache and reject an old-generation request.
- Fresh installation, pairing and chat send/receive with the signed universal release APK.

`UiAuditActivity` and `WakeIsolationProbeActivity` exist only in debug builds. The latter requires an emulator and exact-alarm permission, and clears local app/alarm state. Use them only with a disposable test server. Confirm these classes are absent from release DEX.

## 0.16.1 security and compatibility follow-up

AOSP Android 12 (API 31) and Android 16 (API 36), both without Google Play services, exercised signed pairing, chat send/reply and the live-sync fallback against an isolated fixture. The first-run screen now leads with link pairing when QR scanning is unavailable.

Focused checks on both emulators verified:

- External `file://` and Hermes-owned provider shares are rejected. A separate-UID test app proves that a granted external `content://` file uploads only after confirmation. Cancel, absent grants and revoked grants produce no upload.
- Parked machines migrate from plaintext JSON to Android Keystore-backed AES-GCM storage. Checks cover field preservation, update/forget, atomic plaintext removal, disk scans and refusal to overwrite unreadable storage.
- Download integrity uses real loopback HTTP: 35 cases cover matching/missing metadata, wrong metadata lengths, truncated HTTP, unknown/chunked lengths, stale cache and partial-file cleanup. An invalid download cannot replace an existing destination.

An additional API 36 regression reproduced a corrupt-registry startup failure before the fix. After the fix, startup and the Machines error panel/retry preserve the unreadable registry without crashing. This does not certify data recovery from a lost Keystore key.

`MachineStorageProbeActivity` and `DownloadIntegrityProbeActivity` are debug-only and require freshly cleared disposable emulators. Neither belongs in the release DEX. The standalone sender under `scripts/share_fixture/` supplies a real second Android UID:

```sh
JAVA_HOME=/path/to/jdk21 python3 scripts/share_fixture/build.py /path/to/test-output
adb install /path/to/test-output/share-fixture.apk
adb shell am start -n dev.avifenesh.hermes.sharefixture/.ShareActivity --es mode valid
```

Other sender modes are `ungranted` and `revoked`. Use only with a disposable emulator and isolated test server. Compare upload request counts before the share, before confirmation and after confirmation; rendering a dialog alone does not establish that no upload happened.

## 0.16.2 focused integration checks

These are historical 0.16.2 results (versionCode 34), followed by artifact-specific receipts. This documentation/harness pass did not rerun Gradle, devices or deploy anything. Do not apply the historical receipts to changed 0.16.3 bytes.

- **Build/JVM/lint:** full and Play builds completed with the replacement native engine; 31 full and 34 Play JVM tests passed, zero failures. Both release lint runs reported zero errors. These suites do not cover the whole platform or every UI/lifecycle path.
- **Artifact guards:** checked APKs passed legal and distribution guards, including 19 packaged legal assets, pinned replacement model bytes, absence of all three old ONNX models/ORT, and full/Play capability boundaries. Final post-change APK/AAB hashes, signer, ELF LOAD/ZIP alignment and generated-APK qualification remain the release owner's responsibility; these results are not approval of a later artifact.
- **Native runtime:** on an API 36 emulator with actual 16 KiB pages, both flavors executed four native parity/reset/silence/migration cases. The optional configured-WAV case was initially skipped without a fixture, then explicitly executed with a synthetic TTS “Hey Jarvis” positive for each flavor; positive/reference parity passed. This proves the tested native paths run on that emulator, not all supported ABIs/Android versions or recognition quality.
- **Full debug live-service handoff:** a synthetic WAV injected into the live microphone foreground service triggered native wake detection → private `VoiceLaunch` → `TalkService` → on-device recognizer startup. The empty emulator microphone then produced the expected `NoSpeech` error. This is a service/handoff probe, not acoustic microphone pickup, successful spoken transcription or physical-phone proof; it was not run as a Play live-service test.
- **Security review:** independent review passed the combined client source at the reviewed point. Server replay remediation at `36800db` passed independent review, 25 focused security tests and 15 existing phone-transport tests in isolation. Those receipts made no deployment claim; current gateway state is outside this verification.

Later 0.16.2 receipts recorded API 31 native instrumentation (5 tests per flavor), legal assets, distribution/signing guards and eight aligned 64-bit native libraries per flavor. The receipt at source commit `4269d9518fd5c8482c0e77f00db0c28ffb249a61` binds the full APK SHA-256 `616025739c9cc9dba3e218fbd748d49431861107459a7b2aefa4b080a3828f19`; these completed historical checks are not reopened here. The earlier 0.16.0/0.16.1 results are not reruns of all scenarios on 0.16.2. The separate [0.16.3 verification](release-0.16.3.md) records the new artifact and scope. No physical-phone multi-speaker/near-miss/long-negative study, latency, sustained CPU/RSS/power measurement, complete platform QA or Play review is established.

See [native migration and reproducible checks](wake-engine-migration.md), [distribution guards](distribution-flavors.md) and [public installation/security requirements](../README.md).

## Remaining device qualification

Not certified by this audit: physical camera/microphone quality, speech recognition or wake-word accuracy, biometric hardware, audible alarm delivery through OEM battery/DND policies, real FCM delivery, WhatsApp escalation, live Fleet control, real repository mutations, Hub installs, and every Android version/vendor. Server-side actions need their own isolated integration tests. Review Play policy separately before submitting the built Play flavor.

Keep per-build evidence, screenshots, APK hash and signer/native-alignment checks with the release artifact. Report focused ad-hoc verification separately from the Gradle tests; do not call the whole application fully tested.
