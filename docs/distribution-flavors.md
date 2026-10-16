# Full and Play development distributions

**GitHub full APK first: 0.16.4 / versionCode 36.** See [artifact-specific checks](release-0.16.4.md). Both 0.16.2 development flavors have historical focused receipts; those do not qualify a new APK. Play distribution and reviewer hosting are deferred, not GitHub installation requirements.
See [installation](../README.md), [security](../SECURITY.md), [privacy](privacy-policy.md) and [verification scope](android-qa.md). This document does not establish publication or Play approval.

## Source-set contract

| Surface | `full` | `play` |
|---|---|---|
| Application identity | `dev.avifenesh.hermes`, existing signing configuration and update lineage | `dev.avifenesh.hermes.play`, owner-approved coexistence scope; final listing identity/branding and Play signing strategy still require confirmation before Console |
| Source version | 0.16.4 / versionCode 36 | Same source version code/name, independent package; distribution deferred |
| Autonomous phone control | Original `PhoneService.kt`, `PhoneControl.kt`, Accessibility manifest/resource and debug-only fixture moved unchanged to `full` / `fullDebug` | No Android phone service/receiver, Accessibility resource, command fetch/executor, arming request or arming UI compiled. Shared startup/preferences/SSE/FCM calls resolve via a source-only typealias to `UnsupportedPhoneControl`; it clears stale local armed state and performs no network or Android control action |
| Phone settings | Existing Control tab and arming panel | No Control tab; a stale restored tab renders a non-actionable unsupported state |
| APK attachments | Original installer + explicit unknown-sources grant flow | Installer absent; file open rejects APK extension/MIME and renamed ZIPs containing AndroidManifest.xml, explains unsupported installation. Save/download and ordinary file viewing retained |
| Battery | Original direct exemption permission and prompt | No direct exemption permission or request; delivery text explains optimized/possibly delayed background delivery |
| Wake exact alarms | Existing `USE_EXACT_ALARM` plus API-32-capped schedule permission unchanged | Only user-granted `SCHEDULE_EXACT_ALARM`, **uncapped**. Existing `Wake.canExact` checks, cancellation on denial, explicit permission UI and permission-change/return refresh remain the fallback; no false promise of local alarms while denied |
| Chat, approvals, files, user-started Talk, Wake | Retained | Retained |
| Wake-word models/interface | Native microWakeWord v2 Hey Jarvis / TFLite Micro; all old ONNX assets and ORT removed | Same common replacement engine; explicit Apache-2.0 weights/manifest grant, but unaudited training provenance is not broad commercial clearance. See [migration](wake-engine-migration.md) and [provenance](../licenses/MODEL-PROVENANCE.txt) |

The `play` flavor is not `full` with a runtime/server feature flag. AGP's ordinary source sets compile `main + play` instead of `main + full`. Shared network hints cannot manufacture an absent execution implementation. No edits to App, Net, Talk, VoiceLaunch or WakeListen are required by this split.

FileProvider authorities, Wake task affinity and debug instrumentation target use `${applicationId}`. Static shortcut resources do **not** expand manifest placeholders: Play has its own shortcuts XML targeting the Play package while keeping the Kotlin namespace/class names unchanged. The pairing URI scheme remains shared; with both apps installed Android may ask which app should handle `hermes://pair`. Each package has isolated app data/Android keystore state and must pair separately. Check Firebase client registration/configuration for the new package before claiming real Play push delivery.

## Build/test entry points

From `android/`, using JDK 21. For GitHub full-only qualification:

```sh
./gradlew :app:assembleFullDebug :app:testFullDebugUnitTest :app:lintFullRelease :app:assembleFullRelease
./gradlew --stop
```

Both-flavor development tasks follow. Play/AAB work is not required to install the GitHub full APK:

```sh
./gradlew :app:assembleFullDebug :app:assemblePlayDebug
./gradlew :app:testFullDebugUnitTest :app:testPlayDebugUnitTest
./gradlew :app:lintFullRelease :app:lintPlayRelease
./gradlew :app:assembleFullRelease :app:assemblePlayRelease
./gradlew :app:bundleFullRelease :app:bundlePlayRelease
```

Release task dependency `pre.*ReleaseBuild -> verifyReleaseSigning` applies to **both** release flavors. Missing/incomplete signing properties or unavailable configured keystore must fail; neither release falls back to debug signing. No signing secrets or key paths belong in public receipts. Re-test both absent/incomplete signing inputs and configured-key behavior before accepting artifacts.

Expected AGP output paths (relative to `android/`; verify actual outputs):

- `app/build/outputs/apk/full/debug/app-full-debug.apk`
- `app/build/outputs/apk/play/debug/app-play-debug.apk`
- `app/build/outputs/apk/full/release/app-full-release.apk`
- `app/build/outputs/apk/play/release/app-play-release.apk`
- `app/build/outputs/bundle/fullRelease/app-full-release.aab`
- `app/build/outputs/bundle/playRelease/app-play-release.aab`

**Migration:** `assembleRelease` / `assembleDebug` now aggregate both flavors; they are no longer a synonym for a full-only build. Old `outputs/apk/release/app-release.apk` and unflavored test/report task paths are stale. Use flavor-specific tasks and explicit artifacts in automation. Debug builds keep each flavor's package identity, so they cannot update an installed release signed by another key; do not uninstall a real user's full app merely to test a debug APK.

## Focused checks (no Gradle)

From repository root:

```sh
python3 scripts/check_distribution.py
python3 -m unittest discover -s scripts -p test_distribution.py -v
```

The source checker verifies source-set boundaries, absence of installer/exemption/control APIs in Play input, signing-gate presence, manifest permission overlays and identity-sensitive resources. Python tests exercise positive and negative **synthetic** manifest/DEX inputs; they do not substitute for a built APK. `testPlay/ChannelFilesTest.kt` exercises APK detection and ordinary-file preservation in the Play unit suite; these are JVM checks, not real file-viewer/installer UI tests.

After building, pass the **approved** signing certificate SHA-256, not a fingerprint guessed from whatever artifact happens to exist. The approved full signer is recorded below; a later Play signing strategy must be confirmed independently:

```sh
APPROVED_CERT_SHA256=cdd83d31e6ea91a853a973ed8647cb3a6f5408d1301b2e1e6d06a04bd33a27ee
python3 scripts/check_distribution.py --flavor full \
  --apk android/app/build/outputs/apk/full/release/app-full-release.apk \
  --expected-cert-sha256 "$APPROVED_CERT_SHA256"
sha256sum android/app/build/outputs/apk/full/release/app-full-release.apk
apksigner verify --verbose --print-certs android/app/build/outputs/apk/full/release/app-full-release.apk
```

The guard uses SDK `apkanalyzer` for the packaged manifest, parses every DEX's defined classes/string tables, checks the packaged phone XML boundary, and invokes `apksigner verify --print-certs`. It rejects release debug components, wrong identities, full-only Play permissions/classes/transport/arming/settings markers and an unexpected signer. `--debug` is an explicit alternative for debug artifacts; it is not a distribution-signing bypass. Save JSON output beside the exact artifact. Run the same checks on bundletool-generated universal APKs; the script does not inspect AAB signatures, split APK coverage, native 16 KB behavior, model licenses, privacy or Google policy eligibility.

## Executed scope and remaining acceptance

For historical 0.16.2, both flavors built; JVM tests passed (31 full / 34 Play, zero failures), release lint reported zero errors, and checked APKs passed legal/distribution guards with 19 legal assets. Native debug parity/reset/silence/migration plus synthetic-positive WAV checks passed on API 36 with true 16 KiB pages for both flavors. Later receipts also cover API 31 native instrumentation and artifact alignment. See [precise QA scope](android-qa.md): this is not whole-platform QA, performance/accuracy measurement, approval of later bytes or Play approval. Completed 0.16.2 evidence remains valid for its recorded artifacts.

## Remaining full-release acceptance

- The [0.16.4 receipt](release-0.16.4.md) identifies the APK that passed focused tests/lint, package/version/signer checks, component and legal guards, and native ELF/ZIP alignment. Repeat affected checks when bytes change.
- Verify fresh installation and a signed update over the previous full release without deleting data. Exercise pairing/chat and the changed support/security surfaces against the secure bundled plugin. Require `security.signature_replay: 2`; no live gateway-state claim is made here.
- Verify the sanitized public snapshot separately from this private development history. Publish only reviewed public material, not old history, design assets, operational backups or private readiness records.
- Keep Android/OEM limits explicit: permissions, alarms/reboot, background services, biometrics, microphone quality and real FCM are distinct from source/loopback checks. Remaining wake-model training-provenance limits are documented in the legal inventory.

## Deferred Play work

If Play distribution is resumed, independently qualify that flavor's artifact and generated APKs, separate pairing/coexistence, absence of control routes/components, APK rejection and ordinary-file preservation, Wake permission/lifecycle behavior, signing strategy, reviewer access and current store declarations/policies. A built Play source set is not store approval. None of these deferred store-only tasks blocks a qualified GitHub full APK.

The approved contact is **hello@tiyuvta.ai**. No publisher-operated chat backend or automatic publisher telemetry is part of the approved architecture; this does not mean zero phone, gateway, provider, Google SDK or voluntary support-email processing. See [privacy](privacy-policy.md).
