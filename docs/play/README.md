# Google Play preparation

Candidate: **0.16.16 / versionCode 48**, `dev.avifenesh.hermes.play`.

**Not submitted, not approved, not ready for production.** The full GitHub release remains separate and unchanged. Preparing these files does not create a Play listing, choose the permanent signing setup, deploy a reviewer service or start a testing track.

## Prepared materials

- [English listing draft](listing.en-US.json). Title and descriptions are checked against Play's character limits. The permanent title/package still need the publisher's approval.
- [Play-specific privacy draft](privacy-policy.md), also bundled offline through `android/app/src/play/assets/privacy/privacy-policy.md`. The full edition's policy also discloses the same voluntary publisher-report path.
- [Data Safety worksheet](data-safety.md), not answers submitted to Google.
- [Console declaration drafts](declarations.md) for every App content section, checked against the 0.16.16 AAB manifest, with the code changes Play policy requires (C1 to C6).
- [AI-content safeguards](ai-content-safeguards.md), [account deletion classification](account-deletion.md), [closed testing plan](testing-track.md) and [demo video scripts](demo-videos.md).
- Draft [512px icon](assets/icon-512.png) and [1024x500 feature graphic](assets/feature-1024x500.png), using the existing app's icon, colors and licensed fonts. These are artwork, not app screenshots. The two `phone-*.png` assets are actual 1080×1920 captures from the signed bundle-generated **0.16.9** release (2026-10-19), with disclosed synthetic reviewer data, in light and dark themes. Recapture them from the exact build that is submitted.
- A [local synthetic reviewer backend](../../review-backend/README.md). It uses production pairing/signature/replay modules and isolates each enrollment. Its documented missing surfaces mean it is **not full reviewer access**.

## Reproduce the candidate

Use JDK 21 and the publisher's existing ignored signing configuration. Missing release signing must fail, never fall back to a debug key. From the repository root:

```sh
JAVA_HOME=/path/to/jdk-21 python3 scripts/build_play_candidate.py
```

This runs the real Gradle test/lint/APK/AAB tasks and stops its daemon. `dist/build-provenance-play.json` binds the exact command and successful result to content hashes for the tracked/nonignored source tree, produced artifacts and every unit/lint XML report. Changed sources, replaced reports/artifacts, missing provenance and entirely skipped tests fail qualification. Gradle's normal input/output cache may be reused; this is local build provenance, not a hermetic supply-chain attestation.

Use bundletool `build-apks --mode=universal` with the trusted publisher signer and extract `universal.apk` to `dist/Hermes-Play-0.16.16-from-bundle.apk`. Keep signing passwords out of command lines and logs. From the repository root, qualify the exact built APK/AAB and this bundle-generated APK. Supply paths to the installed SDK Build Tools, Java 21 and bundletool. Supply the expected **upload** certificate from the publisher's trusted record; do not obtain trust from the candidate itself. The existing full-release certificate is used only for this local candidate, not approval of the permanent Play App Signing strategy.

```sh
python3 scripts/qualify_play.py \
  --apk android/app/build/outputs/apk/play/release/app-play-release.apk \
  --bundle android/app/build/outputs/bundle/playRelease/app-play-release.aab \
  --bundle-apk dist/Hermes-Play-0.16.16-from-bundle.apk \
  --provenance dist/build-provenance-play.json \
  --expected-cert-sha256 TRUSTED_UPLOAD_CERTIFICATE_SHA256 \
  --bundletool /path/to/bundletool.jar \
  --build-tools /path/to/Android/Sdk/build-tools/36.0.0 \
  --java /path/to/jdk-21/bin/java \
  --output dist/verification-play-0.16.16
```

The script validates artifact identity/signing, native 16 KiB alignment, the complete ABI set, legal notices, the exact packaged Play policy and actual Gradle XML reports. It does not establish Play acceptance, runtime coverage, real push/voice behavior or public reviewer availability. Rebuild after changes and bind final receipts to the committed tree. It rejects unreviewed bundle modules and asymmetric DEX/native/asset inventories, applies the complete Play manifest boundary to the AAB, and repeats artifact checks on the bundle-generated universal APK. This does not qualify every device-specific split combination.

Regenerate draft artwork with:

```sh
uv run --with pillow --with cairosvg python scripts/build_play_artwork.py
```

## Submission blockers

Drafts written on 2026-11-04 cover each blocker. A draft closes the analysis, not the blocker: nothing is entered in Play Console yet. "Owner" marks work only the owner can do.

| Blocker | Draft | Closed by the draft | Still open |
|---|---|---|---|
| 1. AI-content reporting | [ai-content-safeguards.md](ai-content-safeguards.md) | Policy reading, exact report flow, review procedure | Rerun the on-device consent, receipt and deletion pass on the submitted build. Reporting from other-app session views is a gap until the `play/compliance` change lands. Operate the queue (owner). |
| 2. Reviewer access | [declarations.md](declarations.md#app-access-sign-in-details) | Sign-in details text, with `{INVITATION}` and `{REVIEW_URL}` kept private | Redeploy the hosted sandbox from `feat/review-coverage`: on 2026-11-04 it still ran the older backend (C3). Keep it up through review and enter the invitation (owner). |
| 3. Publisher decisions | [declarations.md](declarations.md) | Title "Herms for Hermes Agent", label "Herms", Google-generated app signing key, non-trader status recorded | Rebuild with the "Herms" label and requalify. Console setup (owner). |
| 4. Declarations and policy hosting | [declarations.md](declarations.md), [account-deletion.md](account-deletion.md) | Answers for privacy policy, app access, ads, content rating, target audience, news, government, financial, health, Data safety (with the sharing exception claimed for each downstream flow), advertising ID, foreground services, full-screen intent, exact alarms | Name the app or "Avifen" in `/privacy` and `/delete`, redeploy (C4). Enter every answer (owner). |
| 5. Background behavior | [declarations.md](declarations.md#foreground-service-types), [demo-videos.md](demo-videos.md) | Eligibility of every foreground service type, full-screen intent and exact alarm | **Code:** always-on `LiveService` fails Play's foreground service policy (C1). `WakeService` should be `systemExempted` (C2). Record V1 to V3 (owner). |
| 6. Testing and production access | [testing-track.md](testing-track.md) | Requirement, tester plan, draft application answers | Recruit 12 or more testers, run 14 days, apply (owner). |
| 7. AI safeguards | [ai-content-safeguards.md](ai-content-safeguards.md) | Covered and uncovered surfaces, gap list | Decide on client-side safeguards and terms (owner). |
| 8. Account/deletion | [account-deletion.md](account-deletion.md) | Classification: pairing is not account creation, with the policy text; Data safety deletion answers | `/delete` naming (C4). Google can still disagree; the fallback change is written down. |

1. **AI-content reporting.** The publisher approved an explicit voluntary in-app reporting flow, with no automatic telemetry. The candidate replaces the email-only limitation with a separate publisher receiver, while keeping email as an alternative. On **0.16.9** (2026-10-19), the signed source-built and bundle-generated releases were checked for no send before explicit consent, exact preview/body receipt, both themes, compact 320dp/200% text, and deletion readback, using synthetic reports against the deployed receiver. On **0.16.16**, the artifacts passed qualification (`dist/verification-play-0.16.16`) and the transport instrumentation and receiver tests (retry/conflict, redirects, bounded acknowledgements) pass; the runtime consent/receipt/deletion pass against the live receiver has not been repeated and must be rerun on the submitted build. The receiver limits each IPv4 address or IPv6 /64 to 10 reports a minute; an attacker with many prefixes can still fill the 1000-row queue and stop reporting until the operator clears it, and nothing alerts on that yet. The publisher must operate the manual review process; no automated moderation or continuous monitoring is established. A locally stored flag or email-app handoff is not delivery to the developer within the app. Keep the policy and Data Safety answers aligned with the deployed receiver.
2. **Reviewer access.** Deploy a dedicated HTTPS backend with repeatable isolated enrollment, no valuable execution-host mounts and no real customer data. Implement reviewable behavior for every retained surface. The local fixture currently returns explicit unsupported responses for several surfaces, rather than fabricating success. Verify the submitted artifact against the final hosted backend, not a public demo claim.
3. **Publisher decisions and Console setup.** Confirm final package/title and Play App Signing/upload-key plan; review the account's contact identity. No app existed when the Console was inspected read-only on 2026-10-19. Do not upload the full APK or change its package/signing lineage.
4. **Declarations and policy hosting.** The deployed report receiver serves the app policy at `/privacy` and report-deletion instructions at `/delete`. Finalize Data Safety, target audience/content rating, ads, app access, activity/fitness, foreground-service and full-screen declarations. Record demonstration videos where required. Reconcile every answer to the submitted artifact and enabled SDK behavior. Drafted in [declarations.md](declarations.md); `/delete` still names "Hermes Android" instead of the Play app or developer.
5. **Background behavior.** Justify and demonstrate the retained user-enabled dataSync, microphone and playback services. The dataSync timeout implementation is not a blanket approval of an indefinite live connection. Exact alarm access is user-granted in Play. Full-screen alarm eligibility and its declaration must be assessed; neither permission is categorically forbidden merely because this is an assistant. Assessed in [declarations.md](declarations.md#foreground-service-types): the always-on dataSync connection needs a code change, full-screen intent is user-granted, not pre-granted.
6. **Testing and production access.** For personal accounts created after 2023-11-13, Google currently requires at least **12 testers continuously opted in for 14 days**, followed by a production-access application. Confirm applicability in the account's actual dashboard. No testers have been recruited and no test clock has started here. Plan: [testing-track.md](testing-track.md).
7. **AI safeguards and moderation contract.** Reporting alone does not establish compliance with Google's prohibited/deceptive AI-content requirements. Document and test safeguards across supported Play gateways/providers, and how received reports inform corrections. This preparation does not prove that every provider lacks safeguards or certify arbitrary self-hosted configurations. Gaps and the review procedure: [ai-content-safeguards.md](ai-content-safeguards.md).
8. **Account/deletion classification.** Determine whether pairing only enrolls a device into an existing gateway operator account or creates an app account under Google's definition. If in scope, implement the required in-app and external account/data-deletion request paths. Device revocation, unpairing and archiving are not deletion. A report-deletion contact does not erase independent gateway/provider data. Classified in [account-deletion.md](account-deletion.md): pairing is not app account creation under Google's definition.

The candidate adds contextual notices before selecting attachments, enabling wake-word listening and choosing photo/step proofs, plus Talk destination text. They compile in the qualified release. The report dialog has compact/theme UI verification; this does not establish a complete prominent-consent audit of all other app surfaces.

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
