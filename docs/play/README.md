# Google Play preparation

Candidate: **0.16.9 / versionCode 41**, `dev.avifenesh.hermes.play`.

**Not submitted, not approved, not ready for production.** The full GitHub release remains separate and unchanged. Preparing these files does not create a Play listing, choose the permanent signing setup, deploy a reviewer service or start a testing track.

## Prepared materials

- [English listing draft](listing.en-US.json). Title and descriptions are checked against Play's character limits. The permanent title/package still need the publisher's approval.
- [Play-specific privacy draft](privacy-policy.md), also bundled offline through `android/app/src/play/assets/privacy/privacy-policy.md`. The full edition's policy also discloses the same voluntary publisher-report path.
- [Data Safety worksheet](data-safety.md), not answers submitted to Google.
- Draft [512px icon](assets/icon-512.png) and [1024x500 feature graphic](assets/feature-1024x500.png), using the existing app's icon, colors and licensed fonts. These are artwork, not app screenshots. The two `phone-*.png` assets are actual 1080×1920 captures from the signed bundle-generated release, with disclosed synthetic reviewer data, in light and dark themes.
- A [local synthetic reviewer backend](../../review-backend/README.md). It uses production pairing/signature/replay modules and isolates each enrollment. Its documented missing surfaces mean it is **not full reviewer access**.

## Reproduce the candidate

Use JDK 21 from `android/` and the publisher's existing ignored signing configuration. Missing release signing must fail, never fall back to a debug key.

```sh
./gradlew --offline --max-workers=2 \
  :app:testPlayDebugUnitTest :app:lintPlayRelease \
  :app:assemblePlayRelease :app:bundlePlayRelease
./gradlew --stop
```

Use bundletool `build-apks --mode=universal` with the trusted publisher signer and extract `universal.apk` to `dist/Hermes-Play-0.16.9-from-bundle.apk`. Keep signing passwords out of command lines and logs. From the repository root, qualify the exact built APK/AAB and this bundle-generated APK. Supply paths to the installed SDK Build Tools, Java 21 and bundletool. Supply the expected **upload** certificate from the publisher's trusted record; do not obtain trust from the candidate itself. The existing full-release certificate is used only for this local candidate, not approval of the permanent Play App Signing strategy.

```sh
python3 scripts/qualify_play.py \
  --apk android/app/build/outputs/apk/play/release/app-play-release.apk \
  --bundle android/app/build/outputs/bundle/playRelease/app-play-release.aab \
  --bundle-apk dist/Hermes-Play-0.16.9-from-bundle.apk \
  --expected-cert-sha256 TRUSTED_UPLOAD_CERTIFICATE_SHA256 \
  --bundletool /path/to/bundletool.jar \
  --build-tools /path/to/Android/Sdk/build-tools/36.0.0 \
  --java /path/to/jdk-21/bin/java \
  --output dist/verification-play-0.16.9
```

The script validates artifact identity/signing, native 16 KiB alignment, the complete ABI set, legal notices, the exact packaged Play policy and actual Gradle XML reports. It does not establish Play acceptance, runtime coverage, real push/voice behavior or public reviewer availability. Rebuild after changes and bind final receipts to the committed tree. It rejects unreviewed bundle modules and asymmetric DEX/native/asset inventories, applies the complete Play manifest boundary to the AAB, and repeats artifact checks on the bundle-generated universal APK. This does not qualify every device-specific split combination.

Regenerate draft artwork with:

```sh
uv run --with pillow --with cairosvg python scripts/build_play_artwork.py
```

## Submission blockers

1. **AI-content reporting.** The publisher approved an explicit voluntary in-app reporting flow, with no automatic telemetry. The 0.16.9 candidate replaces the email-only limitation with a separate publisher receiver, while keeping email as an alternative. Signed source-built and bundle-generated release checks now verify no send before explicit consent, exact preview/body receipt, both themes, compact 320dp/200% text, and deletion readback using synthetic reports. Transport and receiver tests cover retry/conflict behavior. The publisher must operate the manual review process; no automated moderation or continuous monitoring is established. A locally stored flag or email-app handoff is not delivery to the developer within the app. Keep the policy and Data Safety answers aligned with the deployed receiver.
2. **Reviewer access.** Deploy a dedicated HTTPS backend with repeatable isolated enrollment, no valuable execution-host mounts and no real customer data. Implement reviewable behavior for every retained surface. The local fixture currently returns explicit unsupported responses for several surfaces, rather than fabricating success. Verify the submitted artifact against the final hosted backend, not a public demo claim.
3. **Publisher decisions and Console setup.** Confirm final package/title and Play App Signing/upload-key plan; review the account's contact identity. No app existed when the Console was inspected read-only on 2026-10-19. Do not upload the full APK or change its package/signing lineage.
4. **Declarations and policy hosting.** The deployed report receiver serves the app policy at `/privacy` and report-deletion instructions at `/delete`. Finalize Data Safety, target audience/content rating, ads, app access, activity/fitness, foreground-service and full-screen declarations. Record demonstration videos where required. Reconcile every answer to the submitted artifact and enabled SDK behavior.
5. **Background behavior.** Justify and demonstrate the retained user-enabled dataSync, microphone and playback services. The dataSync timeout implementation is not a blanket approval of an indefinite live connection. Exact alarm access is user-granted in Play. Full-screen alarm eligibility and its declaration must be assessed; neither permission is categorically forbidden merely because this is an assistant.
6. **Testing and production access.** For personal accounts created after 2023-11-13, Google currently requires at least **12 testers continuously opted in for 14 days**, followed by a production-access application. Confirm applicability in the account's actual dashboard. No testers have been recruited and no test clock has started here.
7. **AI safeguards and moderation contract.** Reporting alone does not establish compliance with Google's prohibited/deceptive AI-content requirements. Document and test safeguards across supported Play gateways/providers, and how received reports inform corrections. This preparation does not prove that every provider lacks safeguards or certify arbitrary self-hosted configurations.
8. **Account/deletion classification.** Determine whether pairing only enrolls a device into an existing gateway operator account or creates an app account under Google's definition. If in scope, implement the required in-app and external account/data-deletion request paths. Device revocation, unpairing and archiving are not deletion. A report-deletion contact does not erase independent gateway/provider data.

The candidate adds contextual notices before selecting attachments, enabling wake-word listening and choosing photo/step proofs, plus Talk destination text. They compile in the qualified release. The report dialog has compact/theme UI verification; this does not establish a complete prominent-consent audit of all other app surfaces.

Do not advertise an approved store release while any of these is open. Internal testing and a qualified GitHub APK are different milestones from closed testing or production approval.

## Current primary sources

Checked 2026-10-19. Recheck before submitting:

- [AI-generated content](https://support.google.com/googleplay/android-developer/answer/13985936)
- [Data Safety](https://support.google.com/googleplay/android-developer/answer/10787469)
- [User Data](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Sensitive permissions and APIs](https://support.google.com/googleplay/android-developer/answer/16558241)
- [Target API](https://developer.android.com/google/play/requirements/target-sdk), currently API 36 for new standard Android apps
- [Personal-account testing](https://support.google.com/googleplay/android-developer/answer/14151465)

An independently developed Hermes client must not imply endorsement by Nous Research. No public screenshot may contain private pairing links, hosts, messages, credentials or non-English fixture text.
