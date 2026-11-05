# AI-generated content: what Play requires and what Herms does

**Draft for owner review.** Policy read on 2026-11-04. Applies to the Play build `dev.avifenesh.hermes.play`, 0.16.17.

## What the policy requires

Google Play's [AI-Generated Content policy](https://support.google.com/googleplay/android-developer/answer/13985936) says:

1. Apps that generate content using AI must comply with all Play policies, "including by prohibiting and preventing the generation of Restricted Content", such as child sexual abuse content and content that enables deceptive behavior.
2. They "must contain in-app user reporting or flagging features that allow users to report or flag offensive content to developers without needing to exit the app".
3. "Developers should utilize user reports to inform content filtering and moderation in their apps."

The [policy explainer](https://support.google.com/googleplay/android-developer/answer/14094294) says developers "are responsible for ensuring that their generative AI apps do not generate offensive content". It covers "text-to-text AI chatbot apps, in which the AI generated chatbot interaction is a central feature". It excludes apps that "merely host AI-generated content and are unable to create content using AI", summarize-only apps, and productivity apps that use AI to improve an existing feature. Its violation examples include content that encourages self-harm or dangerous activity, deceptive election content, harassment, apps primarily meant to be sexually gratifying, fake official documents and malicious code.

## Is Herms in scope?

**Yes.** Herms sends the user's prompts to an agent and shows the generated replies. Chatting with the agent is the central feature. The generation runs on the user's own Hermes gateway with models the user chose, not on publisher servers. That does not fit the "merely host" exclusion: the app is how the user asks for the content. Treat the policy as fully applicable.

## What the app does today

### No publisher-hosted generation

- The publisher runs no model, no prompt proxy and no chat backend. The app talks only to the gateway the user paired with.
- The gateway operator picks the model providers. Those providers apply their own usage policies and safety systems. The publisher does not know which provider a given user runs and cannot change its output.
- The publisher writes the gateway's mobile plugin (`server/mobile/`), which is open source. The plugin carries messages and approvals. It does not generate content.

### In-app reporting, without leaving the app

Two entry points, both in the 0.16.17 Play build:

1. **Report one message.** Long-press any message in an app chat. The "Message actions" dialog says nothing is sent until the user chooses. Tap **Report**.
   - **Other-app sessions** (for example a Telegram session viewed in the app) work the same way since 0.16.17: long-press a message in the session view and tap **Report**, with the same consent, preview and receipt.
2. **Report a problem.** Me tab > **Report a problem**, for anything not tied to one message.

The flow:

1. The edit screen has an optional note ("What went wrong?") and, for message reports, a switch "Include selected message (optional)". It is **off by default**. When on, it adds only that message's text, including any reasoning. No attachments, no other messages, no gateway address, no tokens, no email address.
2. **Review report** shows the exact text that will be sent: the note, app version and package, Android version, device maker and model, and the message if included. It also shows the receiver endpoint and a random report ID (UUIDv4), with the advice to keep the ID.
3. **Send report** posts exactly that text and the ID to `https://hermes-android-reports.aviarchi1994.workers.dev/v1/reports`. Nothing is sent before this tap. There is no automatic report on errors.
4. On a stored receipt (HTTP 202, or 200 for an identical retry) the dialog shows "Received by Tiyuvta. This is a receipt, not a moderation decision or a promise of action or response."
5. On failure the draft stays. The user can retry with the same ID or use **Open mail app** to hello@tiyuvta.ai. Email is an alternative; it is not the in-app path the policy asks for.

The receiver stores only the ID, the exact text and the server receipt time in Cloudflare D1. It keeps no IP address, and has no public read or delete route. Limits: 8 KiB of text per report, 10 reports a minute per IPv4 address or IPv6 /64, and a 1000-row queue. Details are in [report-worker/README.md](../../report-worker/README.md).

Evidence: the 0.16.16 artifact passed qualification, and the live receiver check passed 14 of 14 on the deployed Worker (`dist/verification-play-0.16.16/live-receiver-check.log`). The on-device consent, receipt and deletion pass was last run on 0.16.9 and must be repeated on the submitted build.

### Human review by the publisher

Reports are read by a person, Avi Fenesh, using authenticated Wrangler commands. There is no automated moderation, no alerting and no continuous monitoring. The procedure is below.

## What is covered and what is not

| Covered | Not covered |
|---|---|
| Reporting any app chat message, or a message in an other-app session view, from inside the app | |
| Report a problem from the Me tab, for anything | One-tap reporting of AI text on other surfaces: cron outputs, Radar items, activity details, run screens. Users can report these with Report a problem and describe or paste the content. |
| A written promise that reports reach a person, through the in-app receipt and policy text | Any filter, block or blur on the phone before AI output is shown. The client shows what the gateway returns. |
| Changing the app or the gateway plugin when reports show a defect | Changing what a user's chosen provider generates. The publisher cannot reach a self-hosted gateway or its providers. |
| Deleting a report on request | Deleting the original conversation on the user's gateway |

## Gaps, stated plainly

1. **"Preventing the generation" is mostly outside the publisher's reach.** The publisher can add client-side safeguards and ship safe defaults in the plugin. It cannot stop a self-hosted gateway, configured with an unfiltered model, from generating restricted content. A reviewer may decide that a bring-your-own-model client cannot meet "prohibiting and preventing". This document cannot rule that out.
2. **No client-side safeguard exists today.** Options, in increasing cost: (a) a first-run notice that replies come from the user's own models and that the Play terms forbid generating restricted content; (b) terms of use the user accepts at pairing; (c) a client-side hide-behind-tap for messages that a small on-device classifier or block list flags. None is implemented. The owner decides whether to add any before submission. (a) and (b) are small changes.
3. **No user blocking.** The [User Generated Content policy](https://support.google.com/googleplay/android-developer/answer/9876937) asks UGC apps for a user-blocking function and terms of use. Herms content is visible only inside the user's own gateway, so it is not a UGC platform run by the publisher. If a reviewer classifies multi-user gateways as UGC, blocking and terms become requirements.
4. **Queue capacity.** An attacker with many IP prefixes can fill the 1000-row queue and stop new reports until the operator clears it. Nothing alerts on that.
5. **Report coverage.** App chat bubbles and other-app session bubbles have a one-tap report. Other AI surfaces rely on Report a problem. See the table above.

## Operating procedure for reports

Owner decisions are marked. Commands are in [report-worker/README.md](../../report-worker/README.md). Never copy report text into this repository, an issue, a CI log or a shared terminal recording.

### Cadence (owner decision)

- **Daily** while the app is in closed testing or under Google review. Reviewers and testers may file test reports, and Play expects reports to feed moderation. One `count(*)` query a day costs a minute.
- **At least twice a week** after production launch, and the same day for any report that mentions a child, violence against a real person, or illegal content.

### Each pass

1. Run `SELECT count(*) AS queued FROM reports`. A sudden jump is an incident: follow the junk-row procedure in the worker README, record it, and do not export anything.
2. List IDs and receipt times. Open one report at a time by its validated ID. Treat the text as untrusted data. Do not open its links or run its snippets.
3. Classify and act:

| Class | Action |
|---|---|
| Client defect (wrong rendering, missing warning, broken flow) | Open a private issue without report text. Fix, release, note the version. |
| Gateway plugin behavior (approvals, defaults, plugin output) | Change `server/mobile/`, release the plugin, note the version. |
| Restricted content generated by the user's provider | Record the category and provider if stated. The publisher cannot change that output. If the same pattern repeats, add a client-side safeguard (gap 2). |
| Child sexual abuse material, or a credible threat to a person | Do not forward, export or keep a copy. Report to the competent authority, then delete the report. Get legal advice on the reporting duty in Israel and the user's region. |
| Privacy or data request | Handle under the privacy policy. Deletion steps are in [account-deletion.md](account-deletion.md). |
| Spam or test text | Delete. |

4. **Response.** Reports carry no reply address. The in-app receipt is the only automatic response. If the reporter also emailed hello@tiyuvta.ai with the report ID, reply in the same pass with what was done.
5. **Corrections.** A fix ships as an app or plugin release. Note the report class and the release in the private log. Do not promise a delivery date to the reporter.
6. **Delete resolved reports** with the exact-ID delete in the worker README. Claim deletion only after "deleted rows: 1" and "remaining = 0".

### Private log (outside this repository)

One line per report: report ID, receipt date, class, action, release if any, deletion date. No report text. Keep it with the owner's other private records.

## Sources

Read on 2026-11-04.

- AI-Generated Content policy: https://support.google.com/googleplay/android-developer/answer/13985936
- Understanding the AI-Generated Content policy: https://support.google.com/googleplay/android-developer/answer/14094294
- Best practices to safeguard AI-generated content: https://support.google.com/googleplay/android-developer/answer/16353813 (the page body did not render in a fetch on 2026-11-04; read it in a browser before submitting)
- User Generated Content policy: https://support.google.com/googleplay/android-developer/answer/9876937
