# Hermes for Android: Google Play privacy policy

Prepared 2026-10-19 for the **0.16.9 candidate**, package **dev.avifenesh.hermes.play**. This candidate has not been submitted to Google Play. Developer: **Avi Fenesh / Tiyuvta**. Privacy and support: **hello@tiyuvta.ai**.

## Where your information goes

Hermes connects to the gateway you choose when pairing. You or another operator control that gateway, its storage, AI providers, tools and integrations. The publisher does not provide a central chat backend. There is no automatic publisher analytics, crash-reporting or behavior-tracking program. This does not mean that no information leaves your phone.

This Play edition does not include the autonomous Android Accessibility phone-control service, installed-app inventory or its screen-reading/gesture interface. It cannot install downloaded APK attachments and does not request a direct battery-optimization exemption. These features belong to the separate full sideload edition.

## Feature data and destinations

- **Pairing and authentication:** pairing codes, device labels, public signing keys, device identifiers, authentication credentials and activity timestamps identify your phone to your selected gateway. Private signing keys stay in Android Keystore. An access proxy can also process connections.
- **Chat and agent work:** messages, replies, conversation metadata, attachments, tool arguments/results, approval decisions and usage records go to the selected gateway and any configured models, tools or integrations it invokes. Authorized gateway users or other paired devices may access shared gateway data. The gateway can execute approved actions on its host; removing Android phone control does not remove host-side agent tools.
- **Attachments:** selected files, camera photos and recorded audio, including names/types/sizes and transcripts, can be uploaded **when selected or recorded, before Send is pressed**. Removing an attachment from a draft is not proof of deletion from the gateway or processing providers. Downloads and recordings can also leave local caches.
- **Talk and spoken replies:** on-device Android speech recognition is preferred when available, but the system recognizer can be used instead and may send audio to its provider. Transcripts go to the gateway. Server speech sends text to the gateway and configured TTS provider and downloads generated audio. The phone TTS engine may also use a network service.
- **Optional wake word:** the bundled microWakeWord/TFLite Micro detector processes microphone frames locally while enabled; the inspected detector does not save or upload those frames. Detection can start Talk, whose separate recognition/transcript flows may use network services.
- **Optional Wake alarms:** schedules, timezone, answers, photo proofs, step-event timestamps, outcomes and escalation settings go to the gateway. Vision or language providers can process proofs. Configured WhatsApp escalation sends messages to chosen recipients through the operator's bridge. Step proof is not only a local step counter.
- **Sharing:** text/files you save, share or open with another app become available to that destination, whose retention and network behavior are separate.

## Google services

If your gateway supplies Firebase configuration and compatible Google Play services are available, Firebase Cloud Messaging obtains an installation/push token and registers it with that gateway. Google processes tokens, installation-related data and SDK service metadata. Push payloads carry wake/event identifiers, not chat message bodies; the client fetches content from the gateway. Identifiers are still data. Disabling Android notifications does not promise to delete Firebase identifiers. Live/periodic synchronization is available without Google push.

Google code scanner processes pairing QR images on-device through Play services. Google states that scanner images/results are not stored by that scanner. Modules may be downloaded; Google's ML Kit disclosures separately describe device/app information, identifiers, performance/configuration and feature/error diagnostics. Pasted-link pairing is an alternative. SDK behavior can depend on shipped versions and downloaded services.

## Voluntary support and content reports

**In-app content reports are voluntary.** Opening Report, writing a note or viewing the preview does not upload a report. Only the explicit **Send report** action sends it to Tiyuvta's separate reporting service, operated on Cloudflare Workers and D1. The app does not send it through your selected gateway or AI providers.

The complete bounded preview contains your note, app version/package, Android version and device manufacturer/model. Selected-message text, including reasoning, is included only if you opt in; inclusion defaults off. Attachments and other messages are not included. A random report ID supports confirmation, safe retries and deletion requests; it is not a persistent device identifier. The receiver stores that ID, the exact submitted text and the server receipt time. Report text is limited to 8 KiB of UTF-8. A confirmation means receipt, not that a moderator has reviewed the content or resolved the issue.

The app does not automatically attach a gateway address, pairing material, authentication token or email address. Review the text for secrets, private content and other people's information before sending. Information you type or choose to quote can itself contain sensitive information; no automatic redaction guarantee is made. Cloudflare processes the connection and infrastructure metadata, including the source IP used transiently for abuse throttling. The application does not add IP addresses to report records or enable report-body logging or publisher behavior analytics.

Avi Fenesh uses submitted reports for support, safety/content-moderation review and product corrections. Reports remain in the review queue until resolved or deleted. Keep your report ID and email **hello@tiyuvta.ai** to request deletion of that report. No fixed deletion-completion deadline or immediate provider-backup erasure is promised. Deleting a report does not delete the original conversation on your independent gateway or its providers.

**Email remains an alternative.** Opening a reviewed email draft shares it with your mail app, which may store or sync drafts. Sending is your choice. If sent, the developer receives your email address and submitted content. Avi Fenesh handles support through hello@tiyuvta.ai and deletes resolved support threads. Contact that address about support-data deletion. Mail-provider trash, backups and other copies may remain. No report is sent automatically when an error occurs.

## Permissions and choices

Microphone, camera, physical activity, notifications and exact-alarm access are optional and requested for their features. Voice listening/playback and synchronization can use foreground services. Alarm availability/full-screen display depends on Android permissions and settings. Device authentication protects app access and sensitive approvals; Hermes does not receive your biometric template. Deny/revoke optional permissions in Android settings; affected features may stop working. Stop Talk or disable wake-word listening when you do not want microphone capture.

## Retention, deletion and security

Phone preferences, pairing records, selected encrypted credentials, Keystore keys and media caches support operation. Not every preference or cache is encrypted by Hermes. Clear app storage to remove app-private local data; separately remove exported Downloads and copies shared with other apps. Unpairing is not complete erasure.

Your gateway and its providers may retain messages, media, sessions, device registrations, tool/approval records and backups. Ask that operator about destinations, retention and deletion. The publisher cannot erase independently operated gateways. Revoking a device stops its access but does not delete its history, media or provider copies. Archiving is not deletion. No universal gateway/provider/SDK retention deadline is promised.

Pairing is not a publisher-hosted account. This architectural statement is not a Google Play account-deletion exemption. The release disables cleartext app traffic and Android backup. API requests are signed and selected credentials use Android Keystore-backed encryption. HTTPS/signatures are not end-to-end encryption: the gateway and configured providers process submitted content. These measures and targeted test receipts are not security certification.

## Sources

- Firebase SDK disclosures: https://firebase.google.com/docs/android/play-data-disclosure
- ML Kit SDK disclosures: https://developers.google.com/ml-kit/android-data-disclosure
- Google code scanner: https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner
