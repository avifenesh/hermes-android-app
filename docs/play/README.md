# Google Play preparation

Candidate: **0.16.19 / versionCode 51**, `dev.avifenesh.hermes.play`, sent for review on 2026-11-05 on the Closed testing - Alpha track. 0.16.18 (versionCode 50) was approved and released there on 2026-11-05. Opt-in page: https://play.google.com/apps/testing/dev.avifenesh.hermes.play

**Closed test live since 2026-11-05. Not approved for production.** Play Console has the store listing, store settings, every App content declaration, Play App Signing with a Google-generated key, and the closed track (all 178 countries, testers from the Google Group herms-testers@googlegroups.com). The foreground-service declaration has its two demo videos. The full GitHub release remains separate and unchanged.

## Prepared materials

- [English listing](listing.en-US.json), as entered in Play Console: title "Herms for Hermes Agent", label "Herms", category Productivity.
- [Play-specific privacy draft](privacy-policy.md), also bundled offline through `android/app/src/play/assets/privacy/privacy-policy.md`. The full edition's policy also discloses the same voluntary publisher-report path.
- [Data Safety worksheet](data-safety.md); the answers are entered in Play Console.
- [Console declaration drafts](declarations.md) for every App content section, checked against the 0.16.19 AAB manifest, with the code changes Play policy requires (C1 to C6, all done).
- [AI-content safeguards](ai-content-safeguards.md), [account deletion classification](account-deletion.md), [closed testing plan](testing-track.md) and [demo video scripts](demo-videos.md).
- [512px icon](assets/icon-512.png) and [1024x500 feature graphic](assets/feature-1024x500.png): the Herms launcher icon (the Seal, `src/play/res/mipmap-anydpi-v26/ic_launcher.xml`), the app's colors and licensed fonts, rendered by `scripts/build_play_artwork.py`. The full edition keeps its lime H, so both apps can sit on one phone. These are artwork, not app screenshots. The six `phone-*.png` assets are 1080×1920 captures of the bundle-generated universal APK of the **0.16.17** bundle (2026-11-05), paired to the synthetic review sandbox: inbox, a chat approval, a group room, a voice note and Usage in the Night theme, and the inbox in the Day theme. 0.16.18 and 0.16.19 change none of what they show. Recapture them when a submitted build does.
- A [synthetic reviewer backend](../../review-backend/README.md). It uses production pairing/signature/replay modules, isolates each enrollment and answers every API path the Play build can reach with synthetic behaviour. It is hosted for review; its address and invitation are only in Play Console App access.

## Reproduce the candidate

Use JDK 21 and the publisher's existing ignored signing configuration. Missing release signing must fail, never fall back to a debug key. From the repository root:

```sh
JAVA_HOME=/path/to/jdk-21 python3 scripts/build_play_candidate.py
```

This runs the real Gradle test/lint/APK/AAB tasks and stops its daemon. `dist/build-provenance-play.json` binds the exact command and successful result to content hashes for the tracked/nonignored source tree, produced artifacts and every unit/lint XML report. Changed sources, replaced reports/artifacts, missing provenance and entirely skipped tests fail qualification. Gradle's normal input/output cache may be reused; this is local build provenance, not a hermetic supply-chain attestation.

Use bundletool `build-apks --mode=universal` with the trusted publisher signer and extract `universal.apk` to `dist/Hermes-Play-VERSION-from-bundle.apk`. Keep signing passwords out of command lines and logs. From the repository root, qualify the exact built APK/AAB and this bundle-generated APK. Supply paths to the installed SDK Build Tools, Java 21 and bundletool. Supply the expected **upload** certificate from the publisher's trusted record; do not obtain trust from the candidate itself. The existing full-release certificate is used only for this local candidate, not approval of the permanent Play App Signing strategy.

```sh
python3 scripts/qualify_play.py \
  --apk android/app/build/outputs/apk/play/release/app-play-release.apk \
  --bundle android/app/build/outputs/bundle/playRelease/app-play-release.aab \
  --bundle-apk dist/Hermes-Play-VERSION-from-bundle.apk \
  --provenance dist/build-provenance-play.json \
  --expected-cert-sha256 TRUSTED_UPLOAD_CERTIFICATE_SHA256 \
  --bundletool /path/to/bundletool.jar \
  --build-tools /path/to/Android/Sdk/build-tools/36.0.0 \
  --java /path/to/jdk-21/bin/java \
  --output dist/verification-play-VERSION
```

The script validates artifact identity/signing, native 16 KiB alignment, the complete ABI set, legal notices, the exact packaged Play policy and actual Gradle XML reports. It does not establish Play acceptance, runtime coverage, real push/voice behavior or public reviewer availability. Rebuild after changes and bind final receipts to the committed tree. It rejects unreviewed bundle modules and asymmetric DEX/native/asset inventories, applies the complete Play manifest boundary to the AAB, and repeats artifact checks on the bundle-generated universal APK. This does not qualify every device-specific split combination.

Regenerate draft artwork with:

```sh
uv run --with pillow --with cairosvg python scripts/build_play_artwork.py
```

## Submission blockers

Status on 2026-11-05. "Owner" marks work only the owner can do.

| Blocker | Done | Still open |
|---|---|---|
| 1. AI-content reporting ([safeguards](ai-content-safeguards.md)) | Voluntary in-app report flow to the publisher receiver, including messages in other-app sessions; first-run AI notice in Play | Rerun the on-device consent, receipt and deletion pass on the submitted build. Operate the report queue (owner). |
| 2. Reviewer access ([App access](declarations.md#app-access-sign-in-details)) | Synthetic sandbox hosted with group chats; sign-in details entered in Play Console | Keep it up through review. |
| 3. Publisher decisions | Title, label, Seal icon, Google-generated app signing key, non-trader status | None. |
| 4. Declarations ([declarations.md](declarations.md), [account-deletion.md](account-deletion.md)) | Every App content section; the release's full-screen intent, health and foreground-service declarations; `/privacy` and `/delete` name Herms and Avifen | None. |
| 5. Background behavior ([demo videos](demo-videos.md)) | C1 (`LiveService` only while a run started on this phone works) and C2 (`WakeService` systemExempted) ship; Play does not list systemExempted, so V3 is not asked for; V1 (microphone) and V2 (dataSync) re-recorded unblurred on 0.16.19 against the hosted review sandbox and linked in the declaration | None. |
| 6. Testing and production access ([plan](testing-track.md)) | Closed track with the herms-testers group, all countries, feedback to GitHub issues; 0.16.18 approved and released on 2026-11-05, 0.16.19 sent for review the same day | Keep 12 or more testers opted in for 14 days, apply for production (owner). |
| 7. AI safeguards ([safeguards](ai-content-safeguards.md)) | Covered and uncovered surfaces and the gap list are written down | Decide on further client-side safeguards and terms (owner). |
| 8. Account/deletion ([classification](account-deletion.md)) | Pairing is not account creation; Data safety deletion answers entered | Google can still disagree; the fallback change is written down. |

The receiver limits each IPv4 address or IPv6 /64 to 10 reports a minute; an attacker with many prefixes can still fill the 1000-row queue and stop reporting until the operator clears it, and nothing alerts on that yet. No automated moderation or continuous monitoring is established: the publisher reviews reports by hand.

Reporting alone does not establish compliance with Google's prohibited or deceptive AI-content requirements, and this preparation does not certify arbitrary self-hosted gateway configurations.

For personal accounts created after 2023-11-13, Google requires at least **12 testers continuously opted in for 14 days** before the production-access application. The test clock has not started.

The app shows contextual notices before attachments, wake-word listening and photo or step proofs, plus Talk destination text. The report dialog has compact and theme UI checks; there is no complete prominent-consent audit of every other surface.

Do not advertise an approved store release while any of these is open. Internal testing and a qualified GitHub APK are different milestones from closed testing or production approval.

## Current primary sources

Checked 2026-10-19, rechecked 2026-11-04 for the declaration drafts (full list with read dates in [declarations.md](declarations.md#sources)). Recheck before submitting:

- [AI-generated content](https://support.google.com/googleplay/android-developer/answer/13985936)
- [Data Safety](https://support.google.com/googleplay/android-developer/answer/10787469)
- [User Data](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Sensitive permissions and APIs](https://support.google.com/googleplay/android-developer/answer/16558241)
- [Target API](https://developer.android.com/google/play/requirements/target-sdk), currently API 36 for new standard Android apps
- [Personal-account testing](https://support.google.com/googleplay/android-developer/answer/14151465)

An independently developed Hermes client must not imply endorsement by Nous Research. No public screenshot may contain private pairing links, hosts, messages, credentials or non-English fixture text.
