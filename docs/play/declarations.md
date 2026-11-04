# Play Console declarations: Herms for Hermes Agent

**Drafts for owner review. Nothing here has been entered in Play Console.** Policy text was read on 2026-11-04 from the sources at the end. Recheck any source older than a week before you submit.

- Store title: **Herms for Hermes Agent**. Play app label: **Herms**. Package `dev.avifenesh.hermes.play`.
- Developer account: personal, **Avifen**, non-trader (legal name Avraham Fenesh, Israel, hello@tiyuvta.ai).
- Herms is an independent client. It is not affiliated with or endorsed by Nous Research.

## Artifact these answers describe

`dist/Hermes-Play-0.16.16.aab`, SHA-256 `3a5fd902bb8db3bc624028385ba0cc7c4d954ce4986e7a0deef2be97038b2a7d`, versionName 0.16.16, versionCode 48, built from commit `0f9fd88`. The manifest below was read with `bundletool 1.18.3 dump manifest` on 2026-11-04. It matches `dist/verification-play-0.16.16/distribution.json`.

- `minSdkVersion` 31, `targetSdkVersion` 36. API 36 meets the requirement for new apps from 2026-08-31.
- Requested permissions: `SCHEDULE_EXACT_ALARM`, `INTERNET`, `POST_NOTIFICATIONS`, `USE_BIOMETRIC`, `USE_FINGERPRINT`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_MICROPHONE`, `CAMERA`, `USE_FULL_SCREEN_INTENT`, `WAKE_LOCK`, `VIBRATE`, `RECEIVE_BOOT_COMPLETED`, `ACTIVITY_RECOGNITION`, `ACCESS_NETWORK_STATE`, `com.google.android.c2dm.permission.RECEIVE`, and the AndroidX dynamic-receiver signature permission.
- Foreground services: `LiveService` dataSync, `WakeService` mediaPlayback, `TalkService` microphone, `WakeListenService` microphone. WorkManager's `SystemForegroundService` is merged but has no type and is never promoted: no worker calls `setForeground`.
- Absent: Accessibility service, `QUERY_ALL_PACKAGES` (the `<queries>` block names only speech recognition, TTS and `mailto`), `REQUEST_INSTALL_PACKAGES`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `USE_EXACT_ALARM`, `AD_ID`, location, SMS, call log, contacts, `READ_MEDIA_*`, body sensors.
- SDKs that send data: Firebase Messaging 25.1.3 (with Firebase Installations and datatransport), Google code scanner 16.1.0 (ML Kit). No ads, analytics or crash-reporting SDK.

The application label in this artifact is still "Hermes Play preview". The owner's branding change sets it to "Herms". Rebuild and requalify before upload; these answers do not depend on the label.

## Changes needed before submission

Each item says what blocks what. Details are in the section named.

| # | Change | Why | Blocks |
|---|---|---|---|
| C1 | **`LiveService` (dataSync) must stop being an always-on connection in the Play build.** Run it only while a run the user started from this phone is in progress, and stop it when the run ends. Do not set `Prefs.live = true` on pairing. Remove or reword the "Stay connected in background" toggle in Play. | Play allows a foreground service only if it "runs only for as long as necessary to complete the task". Today the service starts automatically after pairing whenever the gateway has no push setup, then shows "Hermes · Connected" with no task and no end. That fails the policy. The reviewer sandbox has push disabled, so every reviewer sees it. See [dataSync](#datasync-liveservice). | Foreground service declaration, review |
| C2 | **Switch `WakeService` from `mediaPlayback` to `systemExempted` in the Play build.** Play manifest overlay: `tools:replace="android:foregroundServiceType"` with `systemExempted`, add `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, remove `FOREGROUND_SERVICE_MEDIA_PLAYBACK`. Pass `FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED` from a Play source-set constant. When exact alarms are not granted, keep the existing "Exact alarms are off" message and do not ring. | Android documents `systemExempted` for "Apps holding SCHEDULE_EXACT_ALARM or USE_EXACT_ALARM permission and are using Foreground Service to continue alarms in the background". Play's mediaPlayback use case is user-started media playback. Play exempts `systemExempted` from its foreground service criteria. Verify on API 31 to 33, where the type bit is unknown to the platform. See [alarm service](#alarm-sound-wakeservice). | Strongly recommended. If not done, declare mediaPlayback as written below and accept the rejection risk. |
| C3 | **Redeploy the hosted reviewer sandbox from `feat/review-coverage`** (or main after it lands). | On 2026-11-04 the hosted `/review` page did not show the alarm password, and `/v1/health` reported `fleet`, `radar`, `projects` and `wake_wa` as false. That is the older backend, which answers the alarm clock, server voice and several settings surfaces with HTTP 501. Reviewers must reach every feature, and the demo videos need the alarm flow. | App access, demo videos |
| C4 | **Name the app and the Play developer in the hosted policy and the `/delete` page.** The policy must name "Herms for Hermes Agent" or the developer "Avifen". `/delete` must reference the app or developer name as it appears on Play. | Developer Program Policy: "The entity (for example, developer, company) named in the app's Google Play store listing must appear in the privacy policy or the app must be named in the privacy policy." Account deletion help: the web link must "reference the app or developer name (that is, as it appears on your store listing in Google Play)". The policy is being rebranded on the owner's branch; `report-worker/delete.html` still says "Hermes Android" and needs a redeploy. | Privacy policy, Data safety deletion URL |
| C5 | Owner decision: in-app publisher and product names. The report dialog says "Voluntary report to Tiyuvta, the app publisher" while the Play developer is "Avifen". The pairing screen title, the version line and the report body still say "Hermes" / "Hermes for Android". | Data safety: the first party "has an obligation to make reasonably clear to users which organization is primarily responsible for processing data". Impersonation policy: no in-app elements that could mislead users about the app's relationship to someone else. "Hermes" alone on the pairing screen reads like the official Hermes Agent app. | Not a hard blocker; lowers review risk |
| C6 | Optional: drop `CAMERA` from the Play build **and** launch the system camera without asking for it. Being implemented on `play/compliance`. Three call sites gate `TakePicture` on a `CAMERA` grant today: the composer menu's Camera item (`Screens.kt` about line 457), the compose sheet's Camera button (`Ui.kt` about line 547) and the Wake photo proof (`WakeUi.kt` lines 300 to 301). In the Play build each must launch `TakePicture` directly, and Wake's optional-proof permission list (`WakeUi.kt` about line 512) must stop adding `CAMERA`. | `ActivityResultContracts.TakePicture` uses the system camera, which needs no permission; Android only demands the grant because the manifest declares `CAMERA`. Removing the manifest entry alone would break capture: every `RequestPermission(CAMERA)` would return denied, so the camera would never open. The manifest change and the three call-site changes must ship together. | Nothing. Removes a runtime prompt |

## Privacy policy

- **URL:** `https://hermes-android-reports.aviarchi1994.workers.dev/privacy`
- **In-app:** Me > Privacy policy (bundled offline copy).
- Requirements met by the hosting: public HTTPS, no login, no geofence, not a PDF. The policy also has to name the app or the developer shown on Play (C4).

Source: [Prepare your app for review](https://support.google.com/googleplay/android-developer/answer/9859455), [Developer Program Policy](https://support.google.com/googleplay/android-developer/answer/18265346).

## App access (Sign in details)

The Console calls this section "Sign in details". Play requires sign-in details that are "accessible at all times, reusable, and valid regardless of user location", and asks for a static URL when access is a QR code or barcode. Here everything is copyable text. The invitation is reusable. Each pairing link is one-use and expires after ten minutes, but the reviewer can make a new one at any time. Nothing here bypasses pairing security.

`{REVIEW_URL}` is the hosted sandbox base URL. It is kept out of this public repository because [review-backend/README.md](../../review-backend/README.md) says to give the page address through the submission's private access instructions. `{INVITATION}` is the invitation stored privately by the owner. Never commit or print it.

**Is any part of the app restricted?** Yes. Play's helper text lists QR codes, biometric authentication and "actions to be carried out on another device". The app does nothing until it is paired with a gateway.

**Instructions set 1**

- **Name:** Synthetic review sandbox
- **Username, email address, or phone number:** leave empty
- **Password:** `{INVITATION}`
- **Any other information required to access the app** (475 characters with the placeholder, about 490 with the real address):

```text
No account. The app pairs with a self-hosted server; we host a synthetic sandbox for review. 1) On the test phone open {REVIEW_URL}/review in Chrome. 2) Enter the invitation from the Password field, tap Get fresh pairing link. 3) Tap Open Hermes / copy this pairing link (or copy it, open Herms, tap Paste). Each link works once for 10 minutes; repeat for a fresh sandbox. Alarm emergency-stop password: review-sandbox. Set a screen lock to test approvals. Nothing real runs.
```

- **Sign in details in this declaration provide full access to all features:** tick, but only after C3. Before C3 the alarm clock and several settings screens answer "Unsupported in synthetic review sandbox".
- **Allow Android to use the provided sign in details for performance and app compatibility testing:** leave unticked (owner decision). Automated runs cannot complete the browser step, and leaving it off keeps the invitation with human reviewers only.

**Longer walkthrough** (for the review notes field of any declaration that has one, or for the reviewer if Google asks):

1. Install Herms from the test track. Set a screen lock (PIN or fingerprint) so approvals can be tested.
2. On the same phone, open Chrome at `{REVIEW_URL}/review`. Enter `{INVITATION}` in "Reviewer invitation" and tap **Get fresh pairing link**.
3. Tap **Open Hermes / copy this pairing link**. Herms opens and pairs. If Android asks which app to use, pick Herms. If the link does not open the app, long-press it, copy it, open Herms and tap **Paste** on the pairing screen.
4. Allow notifications when asked. The inbox shows synthetic chats; "Welcome · synthetic review sandbox" lists what to try.
5. Chat: open any chat and send a message. The reply is scripted and streams like a real one. Nothing executes anywhere.
6. Approvals: send a message containing the word "approval". Approve the card with the fingerprint or PIN prompt, or deny it.
7. Group chats: tap **Rooms** in the inbox and open "Release review". Two Bots answer a message one after another. Approve the waiting command with the fingerprint or PIN prompt; in "Weekly digest", run the deferred turn again or discard the one with an unknown outcome.
8. Alarm clock: open the Cron tab and switch to **Alarm clock**. Allow exact alarms and full-screen alarms when the screen asks. Tap **Test the alarm now**. Pass a proof, or use **Emergency stop** with the password `review-sandbox`.
9. Voice: tap the microphone on the inbox header to open Talk. Allow the microphone. Wake word: Me > This phone > Voice > Wake word.
10. Content report: long-press any reply in a chat, tap **Report**, then **Review report**, then **Send report**. Me > Report a problem works without a message.
11. A fresh sandbox: repeat steps 2 and 3. Each enrollment is a separate synthetic tenant. Do not enter personal data.

Source: [Requirements for providing sign in details](https://support.google.com/googleplay/android-developer/answer/15748846), [Policy declarations question text](https://developers.google.com/android-publisher/app-store-review/policy-declarations).

## Ads

**Does the app contain ads?** No. The merged manifest has no ads SDK and no `AD_ID` permission. There is no house promotion either.

Source: [Prepare your app for review: Ads](https://support.google.com/googleplay/android-developer/answer/9859455#ads).

## Content rating

The questionnaire is IARC's and its current wording is only visible inside the Console. Google's help page does not list the questions. Answer by topic as below, and match each question as worded on screen.

- **Category:** the utility, productivity, communication or other apps category. The app is a client for the user's own agent. It is not a game, social network, news app or store.
- **Content the app itself ships** (violence, fear, sexual content, nudity, profanity, crude humor, drugs, alcohol, tobacco, gambling or simulated gambling, hate symbols): **No** for each. The app bundles no such content. The seeded sandbox content is neutral.
- **Users can interact or exchange content with other people:** **Yes**. The gateway can relay messages: Wake escalation sends WhatsApp messages to contacts the user enters, and other-app sessions (for example a Telegram session) can be steered from the phone. Answering No would misstate that.
- **Unrestricted access to content / the internet:** **Yes**, if the question asks whether users can reach unfiltered content. The app is not a browser, but the agent can fetch and relay any web content, and the publisher cannot filter the user's providers. If the question is narrowly "is the app a web browser or search engine", answer No.
- **AI-generated content**, if the questionnaire asks: **Yes**. Replies come from models chosen on the user's gateway, with no publisher filter.
- **User-generated content can contain mature material**, if asked: **Yes**, for the same reason.
- **Shares the user's location with other users:** No. **Digital purchases:** No.

Reasoning: misrepresenting content can get the app removed. The target audience is 18 and over (next section), so the conservative answers cost nothing. The resulting ratings cannot be predicted here.

Source: [Content rating requirements](https://support.google.com/googleplay/android-developer/answer/9859655), [AI-Generated Content policy](https://support.google.com/googleplay/android-developer/answer/13985936).

## Target audience and content

**Target age groups:** **18 and over** only.

**Restrict users that Google has determined to be minors:** tick (owner decision, recommended).

Why 18 and over, not 13 and over:

- The app drives a self-hosted agent that can run commands, edit files, push to git and drive a terminal on the user's machine, behind biometric approvals. That is an adult tool.
- Replies come from models the user configures. The publisher cannot apply age-appropriate filters to them.
- Using the app requires running a Hermes gateway. The real audience is adult operators.
- Play says to select an age group only "if you have designed your app for and ensured that your app is appropriate for users within each of the selected age group(s)". Nothing in the app is designed for 13 to 17.
- Play's mandatory minor block covers gambling, dating, random-stranger chat and anonymous chat. This app is none of those, so the block is optional. It is still the honest match for an unfiltered AI client.

The store listing must not appeal to children. The current artwork does not.

Source: [Target audience and content](https://support.google.com/googleplay/android-developer/answer/9867159), [Age-Restricted Content and Functionality](https://support.google.com/googleplay/android-developer/answer/16302250).

## News apps

**Is the app a news app?** No. It does not declare itself as news and will not be listed in "News and Magazines". Policy: a news app is one that declares itself as news or lists itself in that category and describes itself as news.

Source: [News policy](https://support.google.com/googleplay/android-developer/answer/9935326).

## Government apps

**Is the app developed by or on behalf of a government?** No.

Source: [Policy declarations question text](https://developers.google.com/android-publisher/app-store-review/policy-declarations).

## Financial features

**Financial features:** "The app does not provide any financial features". The usage screens show model spend reported by the user's own gateway. That is display of the user's own cost data, not a financial service. Every app must complete this declaration.

Source: [Financial features declaration](https://support.google.com/googleplay/android-developer/answer/13849271).

## Health apps

Every app completes this form. Play puts `ACTIVITY_RECOGNITION` in scope of the health apps policy, and the form asks a free-text question for that permission. The policy covers apps that access "health data to support non-health features".

- **Health features:** **Activity and fitness**. The only health data is step-detector events for one optional alarm proof. "The app does not have any health features" would be false, because the app accesses physical activity data. If the owner prefers, "Other" with the same text below is the alternative.
- **ACTIVITY_RECOGNITION use:**

```text
Optional step proof for the Wake alarm. When an alarm rings and the user picks "Walk N steps", the app counts step-detector events only while that proof screen is open, then sends the step timestamps to the user's own Hermes gateway to confirm the user is out of bed. Steps are not counted at any other time and are not used for fitness tracking, ads or analytics. Reading and photo proofs work without this permission.
```

- The privacy policy states this limited use. The app shows a contextual notice before the user picks photo or step proofs.
- No medical disclaimer is needed: there is no medical function.

Alternative: remove `ACTIVITY_RECOGNITION` and the step proof from the Play build. The app then leaves health scope and declares no health features. Not required.

Source: [Health apps declaration](https://support.google.com/googleplay/android-developer/answer/14738291), [Health Content and Services](https://support.google.com/googleplay/android-developer/answer/16679511), [Health app categories](https://support.google.com/googleplay/android-developer/answer/13996367).

## Data safety

Google defines collection as "transmitting data from your app off a user's device", including SDK traffic, "irrespective of whether data is transmitted to you or a third-party server". Self-hosting is therefore collection. [data-safety.md](data-safety.md) is the worksheet behind this answer.

### What Google counts as sharing

- "'Sharing' refers to transferring user data collected from your app to a third party." It includes "server to server transfers. For example, if you transfer user data collected from your app from your server to a third-party server."
- "'Third party' means any organization other than the first party or its service providers." A "service provider" is "an entity that processes user data on behalf of the developer and based on the developer's instructions."
- Transfers that need not be disclosed as sharing: to service providers; for legal purposes; "based on a specific user-initiated action, where the user reasonably expects the data to be shared, or based on a prominent in-app disclosure and consent that meets the requirements described in our User Data policy"; and fully anonymized data.
- Google's pages say nothing about an app whose server is run by the user. No primary source found on 2026-11-04 addresses that case. The answers below therefore treat the user's gateway as a possible third party, and the gateway's onward transfers as transfers of data collected from the app. The forwarding code is partly the publisher's own: the mobile plugin sends Wake photo proofs to the gateway's vision model (`server/mobile/wake.py` 501 to 504).

### Each flow and the exception claimed

| Flow | Recipient | Exception | Evidence in the app | Result |
|---|---|---|---|---|
| Phone to the paired gateway (all types) | The gateway the user paired with; it may be run by another organization | User-initiated action | The user runs `hermes mobile pair` on their gateway and pastes or scans the link, or taps **Pair with this machine**; every later upload is a send, attach, record or proof action toward that gateway | Not sharing |
| Chat text, Talk transcripts, steer text to the agent's model and tool providers | Model, TTS and tool providers configured on the gateway; other-app platforms such as Telegram when the user steers those sessions | User-initiated action, arguable | The model picker shows the model and provider, but nothing names the providers when the user sends, and tools are invisible to the phone user | **Declared shared** |
| Attached photos, videos, audio, music and files to the model and tools | Same | User-initiated action, arguable | The attachment notice says only "Files and photos upload to your paired gateway when selected, before Send" | **Declared shared** |
| Voice notes to speech recognition | The gateway's speech-to-text provider | User-initiated action, arguable | No notice names the provider | **Declared shared** |
| Wake photo proof to the vision model | The gateway's vision provider | User-initiated action | The proof card says "The photo uploads to your gateway and its vision provider for checking" | Covered. Photos are declared shared anyway because chat attachments are not covered |
| Wake reading answers and check-in puzzle answers to the judging model; cron prompts and memory entries to the model | The gateway's model provider | None demonstrated | No notice names the provider | **Declared shared** (Other user-generated content) |
| Wake escalation contacts and the user's own test number | WhatsApp, through the operator's send-only bridge, and the contacts themselves | User-initiated action, arguable | The section is labelled Escalation with "whatsapp", and the user types each contact to be messaged | **Declared shared** (Contacts, Phone number) |
| Step timestamps | Judged on the gateway only (`wake.py` `_p_steps`), not forwarded | Not a transfer | | Not shared |
| Search queries, read state, approvals, toggles | Stay on the gateway | Not a transfer | | Not shared |
| Firebase installation ID and push token | Google, for the gateway operator's own Firebase project | Service provider does not fit: Google acts for the operator, not the developer | | **Declared shared** (Device or other IDs) |
| ML Kit diagnostics and IDs | Google | Service provider. Google states ML Kit "does not transfer this data to third-parties" | | Not shared |
| Voluntary content reports | The publisher's Cloudflare Worker and D1 | Service provider | | Not shared |
| Support email, share, export, open with | Another app on the phone, then its own service | User-initiated action | The user picks the target app each time | Not shared |

Every "Declared shared" uses the purpose **App functionality**.

**Alternative (owner decision, code change):** show a prominent disclosure at pairing that names the destinations: this gateway, the AI, speech and vision providers it is configured with, its tools, and its messaging bridges. Require a tap on an explicit accept button. The User Data policy requires the disclosure to be "within the app itself", "displayed in the normal usage of the app", to "describe the data being accessed or collected", to "require affirmative user action", and not to treat navigating away as consent. With that in place the "prominent in-app disclosure and consent" exception covers the gateway's onward transfers, and those rows can return to "not shared". Firebase stays shared either way.

### Top-level answers

- **Does your app collect or share any of the required user data types?** Yes.
- **Is all of the user data collected by your app encrypted in transit?** Yes. The app sets `usesCleartextTraffic="false"`, so every app connection is HTTPS: the gateway, Firebase, ML Kit and the report receiver. Requests to the gateway are also signed. This is not end-to-end encryption, and the form does not claim it.
- **Do you provide a way for users to request that their data is deleted?** Yes. See [account-deletion.md](account-deletion.md).
- **Account creation:** the app does not allow users to create an account. Pairing enrolls a device into a gateway the user runs. See [account-deletion.md](account-deletion.md).
- **Data deletion URL** (if the form offers one without account deletion): `https://hermes-android-reports.aviarchi1994.workers.dev/delete`, after C4.
- **Independent security review:** No.
- **Ephemeral processing:** No, for every type. The gateway, Firebase, ML Kit and the report store keep data beyond a single request.

### Data types

| Category > type | Collected | Shared | Required or optional | Purposes | What and where |
|---|---|---|---|---|---|
| Personal info > Phone number | Yes | **Yes** | Optional | App functionality | The user's own number for Wake test escalations; the gateway's WhatsApp bridge messages it |
| Personal info > Email address | Yes | No | Optional | App functionality | Only when the user chooses to email support from their mail app. Declared conservatively; the app itself never sends it |
| Messages > Other in-app messages | Yes | **Yes** | Required | App functionality | Chat messages, replies, voice transcripts, steer text, sent to the gateway and on to its model, TTS and tool providers; and, if the user opts in, one message's text in a content report |
| Photos and videos > Photos | Yes | **Yes** | Optional | App functionality | Attached and camera photos and Wake photo proofs, sent to the gateway and on to its model or vision provider |
| Photos and videos > Videos | Yes | **Yes** | Optional | App functionality | Any video the user attaches through the file picker |
| Audio > Voice or sound recordings | Yes | **Yes** | Optional | App functionality | Recorded voice notes, sent to the gateway and its speech-to-text provider |
| Audio > Music files | Yes | **Yes** | Optional | App functionality | The File action accepts any type (`*/*`) and `uploadUri` uploads audio, music included |
| Audio > Other audio files | Yes | **Yes** | Optional | App functionality | Other audio files the user attaches |
| Files and docs | Yes | **Yes** | Optional | App functionality | Attached files, names, types and sizes. Uploads start when selected, before Send |
| Contacts | Yes | **Yes** | Optional | App functionality | Names and phone numbers the user types as Wake escalation contacts, messaged through the gateway's WhatsApp bridge. The app does not read the Android contacts database |
| Health and fitness > Fitness info | Yes | No | Optional | App functionality | Step timestamps from the Wake step proof, judged on the gateway |
| App activity > App interactions | Yes | No | Required | App functionality | Read state, sync position and the device's last-seen time at the gateway |
| App activity > In-app search history | Yes | No | Optional | App functionality | "Search everything" queries, answered by the gateway |
| App activity > Other user-generated content | Yes | **Yes** | Optional | App functionality; Fraud prevention, security, and compliance | Memory entries, cron prompts, Wake answers, settings values, report notes. Wake answers, cron prompts and memory reach the gateway's model. The report note serves content moderation |
| App activity > Other actions | Yes | No | Required | App functionality | Approval and denial decisions, proof results, votes, toggles |
| App info and performance > Diagnostics | Yes | No | Optional | Analytics | ML Kit diagnostics, only when the user scans a QR code; in a voluntary report, app version, package, Android version and device model |
| Device or other IDs | Yes | **Yes** (Firebase) | Required | App functionality; Fraud prevention, security, and compliance; Analytics | Gateway device ID, device label (manufacturer and model) and public signing keys; Firebase installation ID and push token when the gateway enables push; ML Kit per-installation IDs when scanning |

Not collected: name, address, user IDs, race, political or religious beliefs, sexual orientation, other personal info, financial info, health info, location, emails, SMS, calendar, installed apps, web browsing, crash logs, other app performance data. The timezone ID goes to the gateway for alarm scheduling. Play's approximate location means an area of at least 3 square kilometers, such as a city; a timezone is far coarser and is not used to locate the user. Camera photos can carry EXIF location if the camera app adds it. The app does not read it. Stripping EXIF before upload would remove the question.

"Optional" is used only where every user can use the app without that data type. Diagnostics are optional: a user can paste the pairing link instead of scanning a QR code, and reports are voluntary. Collection inside an optional feature does not make the type required. Device or other IDs stay required, because pairing always creates a device ID and signing keys at the gateway.

Sources: [Data safety form](https://support.google.com/googleplay/android-developer/answer/10787469), [User Data policy, prominent disclosure and consent](https://support.google.com/googleplay/android-developer/answer/10144311), [Firebase data disclosure](https://firebase.google.com/docs/android/play-data-disclosure), [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).

## Advertising ID

**Does the app use advertising ID?** No. The merged manifest has no `com.google.android.gms.permission.AD_ID`, and no SDK in the build uses it. Apps targeting Android 13 or later must declare that permission to read the ID, so the ID is not readable.

Source: [Advertising ID](https://support.google.com/googleplay/android-developer/answer/6048248), [Policy declarations question text](https://developers.google.com/android-publisher/app-store-review/policy-declarations).

## Foreground service types

For each type the Console asks for a feature description, the user impact if the system defers or interrupts the task, a video link and a use case. Play allows a foreground service only if the use is "beneficial to the user and relevant to the core functionality", "initiated by the user or is user perceptible", "can be terminated or stopped by the user", "can't be interrupted or deferred by the system without causing a negative user experience", and "runs only for as long as necessary to complete the task". Videos are scripted in [demo-videos.md](demo-videos.md).

### Microphone (TalkService, WakeListenService)

- **Use case:** Background Audio Access.
- **Description:**

```text
Two user-started voice features. Talk: the user opens Talk and speaks with their own Hermes agent; the service keeps the microphone open when the screen turns off, shows "Talking with <agent>" with an End action, and stops when the conversation ends. Wake word: the user turns on "Say Hey Jarvis" in Me > This phone > Voice; the service listens on-device only (nothing leaves the phone until the phrase is heard), shows "Listening for Hey Jarvis" with a Turn off action, and stops when turned off. Neither starts from boot or from the background.
```

- **Impact if deferred:**

```text
Talk would stop listening as soon as the screen turns off, cutting off the user mid-sentence. The wake word would not be heard, so saying it does nothing.
```

- **Impact if interrupted:**

```text
Talk drops the conversation in progress. Wake-word listening stops, and Android does not allow a microphone service to restart from the background, so it stays off until the user opens the app.
```

- **Video:** V1.

### dataSync (LiveService)

This entry is written for the behavior after C1. **Do not submit it for the current artifact.** Today the service also runs as an always-on push substitute, which this text does not describe and Play does not allow.

- **Use case:** Network transfer: Upload or download (data fetch, server-side processing).
- **Description:**

```text
When the user sends a message or starts a run from this phone, the agent works on the user's own server, often for several minutes. While that run is in progress, the service keeps the connection that streams the reply, tool activity and approval requests, and shows "<agent> is working" with the chat title and elapsed time. It stops by itself when the run finishes or the user stops it. It never runs without a run the user started.
```

- **Impact if deferred:**

```text
The reply and any approval request the run is waiting for arrive only at the next background sync, up to 15 minutes later, so the run can sit blocked on an approval the user never saw.
```

- **Impact if interrupted:**

```text
Streaming stops mid-reply and progress disappears from the notification; the user sees the result only after reopening the app or at the next periodic sync.
```

- **Video:** V2.

Without C1 the honest description would be "keeps a live connection open in the background whenever the server has no push". That is a persistent connection with no task and no end, which fails "runs only for as long as necessary". Android's own guidance sends periodic server sync to WorkManager, which the app already schedules every 15 minutes.

### Alarm sound (WakeService)

**After C2 (recommended):** the service is `systemExempted`. Play's foreground service policy exempts that type from its criteria. Android limits it to apps that hold exact-alarm access and use the service "to continue alarms in the background". If the Console still lists it, describe it as below and attach V3.

**Without C2:** declare mediaPlayback.

- **Use case:** Media Playback. If the Console offers "Other", use "Alarm sound for a user-scheduled alarm".
- **Description:**

```text
Wake is an optional alarm clock. The user sets an alarm time in Cron > Alarm clock. At that time an exact alarm starts the service, which plays the device alarm sound with vibration and shows "Prove you're awake" until the user passes a proof (reading, photo or steps) or uses the password-protected emergency stop. A check-in about 15 minutes later rings again if needed. Nothing plays unless the user set an alarm or tapped Test the alarm now.
```

- **Impact if deferred:**

```text
The alarm rings late and the user oversleeps, which is the failure the feature exists to prevent.
```

- **Impact if interrupted:**

```text
The alarm goes silent before the user has proved they are awake, so they can fall back asleep.
```

- **Video:** V3.

Risk without C2: Play's mediaPlayback video requirement says "Playback must be user-initiated". An alarm is user-scheduled, not user-started playback. A reviewer can read that either way.

## Full-screen intent

**Does the app's core functionality qualify for automatic access (alarm or calling)?** No.

Play grants `USE_FULL_SCREEN_INTENT` by default only when the app's core functionality is setting alarms or receiving calls, where core means "the main purpose of your app, without which, the app is broken or rendered unusable". Herms is an agent client. The Wake alarm is one optional feature, and the app works without it. Claiming alarm-clock core functionality would not survive review.

The app already handles the user-granted path:

- `Wake.canFullScreen` checks `NotificationManager.canUseFullScreenIntent()` and only then calls `setFullScreenIntent`.
- The Wake screen shows "Full-screen alarms are off" with an **Allow** button that opens `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`.
- Without the grant the alarm still rings as a high-priority alarm notification, and opening the app shows the proof screen.

No code change is needed. Video V3 shows the grant and the full-screen alarm.

Source: [Foreground service and full-screen intent requirements](https://support.google.com/googleplay/android-developer/answer/13392821), [Full-Screen Intent Permission](https://support.google.com/googleplay/android-developer/answer/9888170#full_screen_intent).

## Exact alarms

The Play build declares only `SCHEDULE_EXACT_ALARM`, uncapped. `USE_EXACT_ALARM` is removed by the Play manifest overlay.

- Play restricts `USE_EXACT_ALARM` to apps whose core function is an alarm, a timer or a calendar with event notifications. Herms is none of those, so it must not declare it, and does not.
- `SCHEDULE_EXACT_ALARM` is granted by the user and is "not pre-granted to fresh installs of apps targeting Android 13". Android asks apps to explain the need in their UI, then open `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, check `canScheduleExactAlarms()` and react to `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`. The app does all four: "Exact alarms are off" with **Allow**, `Wake.canExact`, and `WakeBootReceiver` re-arming on the state change.
- Exact alarms serve only Wake: `setAlarmClock` for the alarm and `setExactAndAllowWhileIdle` for the check-in.
- **If the Console asks whether the app uses exact alarm permissions:** Yes, `SCHEDULE_EXACT_ALARM` only, for the user-set Wake alarm. The app is not an alarm or calendar app as its core function. No video is required for this permission.

Source: [Exact Alarm Permission](https://support.google.com/googleplay/android-developer/answer/9888170#exact_alarm), [Schedule alarms](https://developer.android.com/develop/background-work/services/alarms/schedule).

## Other permissions in the artifact

None of these has a Play Console declaration. Each is disclosed in the privacy policy and requested in context.

| Permission | Use | Play status |
|---|---|---|
| `POST_NOTIFICATIONS` | Replies, approvals, cron digests, alarm and service notifications. Requested once on first open. | Runtime permission; no declaration |
| `RECORD_AUDIO` | Talk, voice notes, on-device wake word | Runtime; counted in health-policy scope but not a health feature; covered by the microphone service entry |
| `CAMERA` | Camera attachments and photo proofs, through the system camera | Runtime; no declaration. Removable together with the three permission gates (C6) |
| `ACTIVITY_RECOGNITION` | Wake step proof | Runtime; answered in the Health apps declaration |
| `RECEIVE_BOOT_COMPLETED` | Re-arm Wake alarms after reboot, update, time or timezone change | Normal. The receiver only reschedules alarms and enqueues WorkManager. It starts no foreground service: Android 14 forbids microphone services from boot, and Android 15 adds dataSync and mediaPlayback |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | Approval signatures with Keystore keys | Normal |
| `WAKE_LOCK`, `VIBRATE`, `MODIFY_AUDIO_SETTINGS` | Alarm and Talk | Normal |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `c2dm.RECEIVE` | Gateway connection and optional push | Normal |
| Battery optimization | The Wake screen opens the system list `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`. The Play build does not request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. | No declaration |
| `QUERY_ALL_PACKAGES` | Absent. Package visibility is limited to speech recognition, TTS and `mailto` intents. | No declaration |
| Accessibility service | Absent. Play forbids using it to "power autonomous decision-making agents". Phone control stays in the sideload build. | Must stay absent |

**Permissions Declaration Form:** not triggered. The artifact requests none of the restricted permissions that need it (SMS, call log, `QUERY_ALL_PACKAGES`, all-files access, background location, `REQUEST_INSTALL_PACKAGES`, `USE_EXACT_ALARM`, photo and video media).

**Child Safety Standards:** required for social and dating apps. Not expected here. If the Console asks for it anyway, it needs a public CSAE standards page and a contact; that is owner work.

## Sources

All read on 2026-11-04.

- Foreground service and full-screen intent requirements: https://support.google.com/googleplay/android-developer/answer/13392821
- Device and Network Abuse, foreground services: https://support.google.com/googleplay/android-developer/answer/13315670#fgs
- Permissions and APIs that Access Sensitive Information (exact alarm, full-screen intent, accessibility): https://support.google.com/googleplay/android-developer/answer/9888170
- Declare permissions for your app: https://support.google.com/googleplay/android-developer/answer/9214102
- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Foreground service types are required (systemExempted for alarms): https://developer.android.com/about/versions/14/changes/fgs-types-required
- Alternatives to data sync foreground services: https://developer.android.com/about/versions/15/changes/datasync-migration
- Schedule alarms: https://developer.android.com/develop/background-work/services/alarms/schedule
- Target API level: https://developer.android.com/google/play/requirements/target-sdk
- Prepare your app for review: https://support.google.com/googleplay/android-developer/answer/9859455
- Sign in details requirements: https://support.google.com/googleplay/android-developer/answer/15748846
- Policy declarations question text (App Store Review API): https://developers.google.com/android-publisher/app-store-review/policy-declarations
- Content rating requirements: https://support.google.com/googleplay/android-developer/answer/9859655
- Target audience and content: https://support.google.com/googleplay/android-developer/answer/9867159
- Age-Restricted Content and Functionality: https://support.google.com/googleplay/android-developer/answer/16302250
- News: https://support.google.com/googleplay/android-developer/answer/9935326
- Financial features declaration: https://support.google.com/googleplay/android-developer/answer/13849271
- Health apps declaration: https://support.google.com/googleplay/android-developer/answer/14738291
- Health Content and Services: https://support.google.com/googleplay/android-developer/answer/16679511
- Health app categories and permissions in scope: https://support.google.com/googleplay/android-developer/answer/13996367
- Data safety: https://support.google.com/googleplay/android-developer/answer/10787469
- Advertising ID: https://support.google.com/googleplay/android-developer/answer/6048248
- Developer Program Policy (entity named in privacy policy): https://support.google.com/googleplay/android-developer/answer/18265346
- Impersonation: https://support.google.com/googleplay/android-developer/answer/9888374
- Firebase Play data disclosure (Google SDK documentation): https://firebase.google.com/docs/android/play-data-disclosure
- ML Kit Play data disclosure (Google SDK documentation): https://developers.google.com/ml-kit/android-data-disclosure
