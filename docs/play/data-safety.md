# Play Data Safety worksheet

**Draft, not submitted.** Scope: the Play edition `dev.avifenesh.hermes.play`, candidate 0.16.9. Review against the final configured artifact and every version distributed on Play, not against the full sideload edition or only the review fixture. The publisher must approve final answers.

Google defines collection as transmission off the user's device, including SDKs and third-party servers. **Self-hosting does not mean no collection.** Sharing exclusions, ephemeral-processing criteria and optional/required answers must be evaluated per actual purpose/recipient. A provider or gateway acting as a service provider is not automatically the same as an independent third party. User-directed transfers may meet an exclusion; document the facts instead of assuming one.

| Actual flow | Likely Play data types | Purpose / choice | Final decision needed |
|---|---|---|---|
| Pairing and signed API | Device/other IDs, user IDs where assigned to an identity; selected device information | Functionality; security/fraud prevention. Pairing is required to use the configured gateway. | Map gateway-assigned identities and Google SDK IDs separately; don't call public keys biometric data. |
| Messages, replies, metadata, agent activity | Other in-app messages, other user-generated content; app interactions where transmitted | App functionality; core chat use | Retention/processing is controlled by the gateway and configured providers. Do not claim ephemeral processing globally. |
| Selected uploads/photos/voice attachments | Files/documents, photos/videos as applicable, voice/sound recordings; other user-generated content | Optional attachments, camera, voice | Files can upload immediately on selection/capture, before Send. Removing a draft is not proof of remote deletion. |
| Talk recognition, transcripts and spoken replies | Voice/sound recordings; other in-app messages/content | Optional speech features; app functionality | On-device preference is not an offline guarantee. System speech/TTS engines and gateway providers determine actual destinations and retention. |
| Wake answers, photos, step proofs | User-generated content, photos, fitness information (step-event data) | Optional alarm/proof functionality | Step timestamps leave the phone. Review the health/fitness declaration even if this is not marketed as a fitness app. |
| Wake escalation | Configured phone numbers/contact information and message content, where used | Optional user-directed escalation | Gateway's bridge and chosen recipients process messages. Distinguish manually entered recipients from reading the Android contacts database. |
| Firebase Messaging / Installations | Device/other IDs; SDK installation/app metadata | Gateway-configured push; functionality and SDK service maintenance | Where configured, initialization is not gated by a universal user opt-out. Do not globally mark collection optional just because some gateways omit FCM. Notifications disabled does not mean IDs deleted; validate recipient/service-provider classification. |
| Google code scanner / ML Kit | Device/app identifiers, performance/diagnostic events and app interactions per SDK disclosure | Optional scanner and SDK diagnostics | Images are processed on-device by scanner; that does not establish zero SDK diagnostics. Paste-link pairing is an alternative. |
| Voluntary in-app content report | Submitted user content and app/device diagnostics in the exact reviewed text; random report ID | Optional developer support and safety/moderation review | Explicit Send only; receiver stores text, ID and receipt time at Cloudflare. Selected-message inclusion defaults off; no attachments or other history. No automatic telemetry. Keep network and D1 readback evidence. |
| Voluntary support email | Email address, submitted user content, app/device diagnostics | Optional developer support; user reviews draft and decides to send | Mail app/provider may store drafts. Email remains an alternative, not the in-app report transport. |
| Export/open/share | Content selected by the user | Optional, user-initiated sharing | Evaluate Google's user-initiated transfer exclusion for each actual recipient/flow. |

## Security, deletion and account questions

- **Transport:** the client disallows cleartext traffic and signs gateway API requests. The gateway/providers can read submitted content; this is not end-to-end encryption. Do not claim security of every independently operated downstream service.
- **Publisher telemetry:** no automatic publisher analytics, crash-reporting or behavioral program. This is narrower than no collection or no third-party SDK processing.
- **Deletion:** app-storage clearing removes app-private phone data, not independently operated gateways, provider records, exports or backups. Unpair/revoke/archive is not erasure. Support deletion is requested at hello@tiyuvta.ai; provider trash/backups are not immediate-erasure guarantees. Do not claim a globally available comprehensive deletion request badge without verifying its criteria.
- **Accounts:** no publisher account registration exists. Pairing creates authenticated device identities at a selected gateway. Assess Google's account-creation/deletion rules against the actual flow; architecture alone is not an exemption.
- **Absent Play flows:** no autonomous Accessibility phone control, screen-tree/screenshot capture through that service, installed-app inventory or APK installer. User-selected camera photos/files are still present and separately disclosed.
- **Advertising:** no advertising SDK or advertising purpose is implemented. Confirm the merged manifest/dependencies and final Console answer.
- **Audience/content rating:** cannot be derived from absence of ads. Confirm intended audience, generative-AI behavior, provider safeguards and rating questionnaire before submission.
- **In-app reporting:** the publisher-approved voluntary reporting service adds Cloudflare as a recipient/processor of explicitly submitted support/safety reports. Review the complete payload and retention/deletion disclosures. It is not automatic telemetry. Keep its moderation/review operation and end-to-end delivery verified before submission.

## Evidence to retain with final answers

1. Exact AAB/APK hashes, package, version, signer and merged manifest.
2. Enabled SDK inventory and current supplier disclosures.
3. Demonstration of every optional permission/disclosure and denial path.
4. Data routes, recipients, optionality, storage/retention/deletion for each row above.
5. Working private reviewer access and final policy/deletion-information URLs.
6. Publisher's approved Data Safety export, permission declarations and content rating.

Sources checked 2026-10-19:

- https://support.google.com/googleplay/android-developer/answer/10787469
- https://support.google.com/googleplay/android-developer/answer/10144311
- https://firebase.google.com/docs/android/play-data-disclosure
- https://developers.google.com/ml-kit/android-data-disclosure
- https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner
