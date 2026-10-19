# Hermes Android privacy policy

**Prepared for the 0.16.9 full-client candidate. Reviewed 2026-10-19.** This policy describes the full Android client, displayed as **Hermes**, package **`dev.avifenesh.hermes`**. Published older APKs retain their own bundled policy. The separate [Play policy](play/privacy-policy.md) describes `dev.avifenesh.hermes.play`; neither candidate has been submitted to Google Play.

## Developer and contact

Hermes Android is developed by **Avi Fenesh / Tiyuvta**. Privacy and support contact: **[hello@tiyuvta.ai](mailto:hello@tiyuvta.ai)**.

Hermes connects to the gateway you choose when pairing. You may operate that gateway yourself or use one run by another operator. That operator controls its storage, AI providers, tools and integrations. This release does not provide a publisher-operated chat backend.

## Publisher analytics and voluntary support

The client has no publisher analytics, automatic publisher crash-reporting or behavior-tracking program. No automatic publisher telemetry or future “help improve” program is authorized by this policy. This is **not** a claim that no data leaves the phone: gateway requests, Google SDKs, system speech services and user-directed transfers are described below.

If you email support, the developer receives your email address, message and any information you choose to include. Your email provider and the recipient's email service also process the message. Do not send pairing links, tokens, private keys or other people's private information.

The support-email flow in the current source opens your mail app with a draft containing your note, app version/package, Android version and device manufacturer/model. General support includes no chat content. When reporting a selected message, you can opt in to include its text, including any reasoning; inclusion defaults off, and attachments or other messages are not included. The source shows the complete bounded draft for review before opening the mail app. Opening that draft shares it with the mail app; the mail app may store or sync drafts. Sending the email is your choice, not automatic reporting. This flow passed focused API 31 checks against the signed 0.16.3 candidate using a local mail sink. No real email was sent, and compatibility with every mail app is not established. See [verification scope](release-0.16.3.md).

## Voluntary in-app content reporting (0.16.9 candidate)

The candidate adds an explicit **Send report** action alongside the existing email option. Opening Report, editing or reviewing a preview does not upload anything. Only Send report sends the exact bounded preview text and a random report ID to Tiyuvta's separate Cloudflare Workers/D1 reporting service, not your selected gateway or model provider. Selected-message text defaults off and is included only if you choose it. No attachments, other messages, pairing credentials or gateway addresses are attached automatically. The preview includes the app/device diagnostics described above; information you choose to enter can contain private information, so review it before sending. Text is limited to 8 KiB of UTF-8. The random ID is for receipt, retry and deletion, not device tracking.

The receiver stores the random ID, submitted text and server receipt time. Cloudflare processes connection/infrastructure metadata and the source IP used transiently for abuse throttling; the application does not put IP addresses in report records or enable report-body logging or behavior analytics. Avi Fenesh uses reports for support, content-safety/moderation review and product corrections. Reports stay in the queue until resolved or deleted. Request deletion at **hello@tiyuvta.ai** with your report ID. No fixed completion deadline or immediate provider-backup erasure is promised. Receipt is not a moderation outcome, and deleting a report does not delete independent gateway/provider conversation copies. No report is sent automatically on an error.

## Information used by features

| Feature | Information and purpose | Destinations |
|---|---|---|
| Pairing and authentication | Pairing code, device manufacturer/model label, assigned device ID, public signing keys, authentication credentials and activity timestamps identify and authenticate your phone. | Phone and selected gateway, including any access proxy the operator configures. Private signing keys remain in Android Keystore. |
| Chat and agent work | Messages, replies, conversation metadata, attachments, tool arguments/results, approvals and usage records support conversations and agent actions. | Selected gateway and the model providers, tools or integrations it invokes. Other paired devices or authorized gateway users may access shared gateway data. |
| Photos, files and voice messages | Selected files, camera photos, recorded audio, file names/types/sizes and transcripts support attachments and analysis. **Selecting an attachment or completing camera capture can upload it before you press Send.** | Gateway media storage and configured processing providers. Removing an attachment from a draft is not proof of server deletion. Downloads and recordings can also leave local cache files. |
| Talk recognition | Microphone audio is converted to text; recognized text is sent as chat. | Android on-device recognition is preferred when available. The default system recognizer is used otherwise, or after certain language/service failures; it may send audio to its provider's servers. The transcript goes to the selected gateway. |
| Spoken replies | Reply text is converted to speech. | Server voice sends text to the gateway and its configured TTS service, and downloads generated audio. Phone TTS is an alternative/fallback; its engine may also use a network service. |
| Optional wake-word listening | Microphone frames are processed by the bundled microWakeWord/TFLite Micro detector while listening is enabled. | Detection runs locally; the inspected detector does not save or upload its microphone frames. Detection can start Talk. Subsequent recognition, transcripts and spoken replies follow the separate flows above. |
| Optional Wake alarms | Schedules, timezone, answers, photo proofs, step-event timestamps, proof results and escalation settings support wake-up checks. | Proofs go to the gateway. Photo proofs go onward to its configured vision model; reading/logic answers may also be model-processed. The gateway can retain proof outcomes and photo hashes. Step proof is not just a local counter. Configured WhatsApp escalation sends messages to configured recipients through the operator's bridge. |
| Optional phone control | UI text, app/package names, window information, screenshots, commands and results allow the agent to read and operate other apps. | Paired gateway and the agent/model processing the tool results. This can include sensitive information from other apps and about other people. See the separate control section below. |
| Sharing and external links | Files or text you share, save to Downloads, or open with another app. | The destination app/service you select. Its storage and network behavior are separate from Hermes. |

## Google services

**Push notifications:** when the selected gateway provides Firebase configuration and compatible Google Play services are available, the client initializes Firebase Cloud Messaging (FCM), obtains a token and registers it with that gateway. This does not require a separate publisher account. Google processes push tokens and installation-related data. Firebase documents automatic Firebase user-agent metadata, application-version handling and a transitive Firebase Installations identifier. These SDK operations are distinct from publisher analytics.[1]

The inspected gateway push payload contains an event kind, chat reference and sequence, not chat message bodies. The client fetches message content from the gateway. Push references are still data sent to Google. Core chat also has live/periodic synchronization paths without Google push; turning off Android notifications is not a promise to delete or disable Firebase identifiers.

**Pairing QR scanner:** Google code scanner delegates scanning to Google Play services, processes images on-device and returns the scan result; Google states that it does not store the scanner's images or results. The scanner module may be downloaded.[3] Separately, Google's ML Kit disclosure describes device/app information, identifiers, performance metrics, API configuration and feature/error events used for diagnostics and usage analytics. On-device image processing is not a claim of zero SDK diagnostics.[2] Pasted-link pairing is an alternative to QR scanning.

Google's SDK disclosures describe its latest SDKs. Exact behavior can depend on the shipped library, downloaded services and configuration. This policy does not authorize adding telemetry.

## Optional Android Accessibility phone control

Phone control is included in the **full sideload client**, not silently enabled by pairing. It requires you to enable Hermes in Android Accessibility settings and turn on **Allow agent control** in the app, with the configured device authentication approval. You can leave it disabled and use ordinary chat.

While armed, the agent can request screen/UI data and screenshots, list launchable apps/windows, tap, type, swipe, scroll and open apps. Screen content and tool results can leave the phone for the gateway and its configured model. The service checks that the screen is on and unlocked. Android can refuse protected-screen screenshots. Password-node filtering is not a guarantee that all sensitive information in screenshots or other apps is hidden.

Use **Stop control** in the app or its persistent notification, or disable the Accessibility service in Android settings. Stopping prevents further control through this service; it does not erase information already sent to the gateway or providers. Only enable control for a gateway and agent you trust.

## Permissions and choices

- **Microphone:** voice recording, Talk recognition and optional background wake-word listening. Local wake detection does not make every microphone feature local-only.
- **Camera:** chat camera attachments and Wake photo proofs. QR pairing is a separate Google Play services scanner flow and does not itself require the app's camera permission.[3]
- **Physical activity:** optional step proof, including transmission of step-event timestamps to the gateway. Reading proof is an alternative.
- **Notifications, foreground services, alarms, full-screen access, wake locks and boot handling:** message/activity notices, user-enabled listening/playback and alarm scheduling/recovery. Availability depends on Android permissions and device settings.
- **Device authentication:** app access and approval of sensitive operations through Android's authentication facilities, not collection of your biometric template by Hermes.
- **Full-client installer/battery settings:** user-directed APK attachment installation and background-operation settings. These capabilities do not make downloaded APKs safe.

You can deny or revoke optional permissions in Android settings; affected features may stop working. Stop Talk or turn off wake-word listening when you do not want microphone capture. A privacy-policy page does not replace clear disclosure at the point a sensitive feature is enabled.

## Storage, retention and deletion

**On the phone:** preferences, pairing records, selected encrypted credentials, Keystore keys and media caches support operation. Not every preference or cache is encrypted by Hermes. Android's clear-app-storage control removes app-private local data; separately remove files exported to Downloads and copies shared with other apps. Unpairing alone is not a complete local or remote erasure operation.

**On the selected gateway and its providers:** messages, media, sessions, device registrations, tool/approval records and enabled integrations can persist. Retention and backups depend on that operator and the configured providers. Ask the operator which services receive your data, where they process it, and how to delete it. The publisher does not administer an independently operated gateway and cannot erase its records for you.

Revoking a pairing disables that device's access; it does not delete gateway history, media, device rows or provider copies. Archiving a chat is not deletion. The inspected gateway has limited request-triggered cleanup for generated TTS files, not a comprehensive deletion schedule. No fixed retention deadline is promised for gateway, provider, SDK or backup data.

**Support correspondence:** Avi Fenesh personally handles messages sent to **hello@tiyuvta.ai** through his email inbox and deletes support threads when they are resolved. You can contact the same address about support-data deletion. Deleting an inbox thread does not guarantee immediate deletion from the mail provider’s trash, backups or other service copies. No deletion-completion deadline is promised. This support policy does not control data held by your gateway operator.

Pairing uses authenticated device identities on the selected gateway, rather than registration with a publisher-hosted chat service. This describes the architecture, not a legal or Google Play account-deletion exemption.

## Security and policy scope

The release manifest disallows cleartext app traffic and Android backup. Selected credentials use Android Keystore-backed encryption, and gateway API calls are signed; authenticated redirects are disabled. These measures do not establish encryption of all server storage or every downstream transfer. HTTPS and request signatures are **not end-to-end encryption**: the gateway and invoked providers can process submitted content.

The release receipts describe focused checks and their limits, not security certification or a guarantee against compromise. This policy makes no unsupported age cutoff, corporate-registration claim, or promise about provider training practices. Changes to processing must be reflected in the policy and relevant user disclosures before release.

## Sources

[1] https://firebase.google.com/docs/android/play-data-disclosure
[2] https://developers.google.com/ml-kit/android-data-disclosure
[3] https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner
