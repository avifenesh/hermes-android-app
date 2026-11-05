# Demo video scripts

**Draft for owner review.** Record these on the exact Play build that is submitted, installed from the closed or internal test track, paired with the hosted reviewer sandbox. Requirements read on 2026-11-04.

## Which declarations need a video

| Video | Declaration | Required? |
|---|---|---|
| V1 | Foreground service: microphone (Talk and wake word) | Yes. Play asks for "a link to a video demonstrating each foreground service feature". |
| V2 | Foreground service: dataSync (run in progress) | Yes, after code change C1 and the sandbox's approval-gated scenario. Do not record it before both. |
| V3 | Foreground service for the alarm (systemExempted after C2, or mediaPlayback), plus the full-screen alarm | Yes for mediaPlayback. If C2 lands and the Console still lists the alarm service, attach it there too. Also shows the full-screen intent grant. |
| None | Exact alarm (`SCHEDULE_EXACT_ALARM`) | No. Play reviews only `USE_EXACT_ALARM`, which the Play build does not declare. V3 shows the grant anyway. |
| None | Full-screen intent | No video is listed for it. The app does not claim automatic access. V3 covers the behavior if Google asks. |

Play's video rules for the types used:

- Microphone: "Show the user initiate the microphone actively recording audio or receiving voice commands."
- dataSync, upload or download: "Show a user-initiating an upload, download, data sync, or server side processing."
- mediaPlayback: "Show a user starting video or audio playback. Playback must be user-initiated."
- All: "The video should demonstrate the steps the user needs to take in your app in order to trigger the feature."

## Setup for every video

1. Install the submitted build from the Play test track on a physical phone running Android 14 or later, with a screen lock set. Apply C3 first, so the sandbox serves the alarm clock.
2. **Pair before you start recording.** Open `{REVIEW_URL}/review`, enter `{INVITATION}`, tap **Get fresh pairing link**, then **Open Hermes / copy this pairing link**. The invitation must never appear in a video.
3. Turn on Do Not Disturb for other apps, clear old notifications, set the phone to English, and use light theme.
4. Record with the phone's own screen recorder, with audio for V1 and V3. Turn on "show touches" if the recorder offers it.
5. Keep each video under 2 minutes. Host it where anyone with the link can watch without signing in, and check the link signed out. The current V1 and V2 (0.16.19, recorded against the hosted review sandbox) are Google Drive files shared to anyone with the link. Their links are only in Play Console, because the videos show the sandbox address.
6. Start every video with two seconds of the app's Me tab, showing the version line, so the build is identifiable.

## V1: microphone (Talk and wake word)

Shows two user-started microphone features, their notifications and how each stops.

1. Inbox. Tap the **microphone** in the header. Talk opens.
2. Accept the microphone permission prompt (record the prompt once). Talk starts listening; if it shows idle, tap the orb.
3. Say: "What is on my schedule today?" Talk shows the transcript and the scripted reply plays.
4. Press the power button to turn the screen off, then on. Pull down the notification shade: "Talking with Hermes Demo" with **End** is visible. Say another sentence and show that Talk still hears it.
5. Tap **End** in the notification. The notification disappears.
6. Go to Me > This phone > Voice > Wake word. Read the on-screen notice, then turn on **Say "Hey Jarvis"**.
7. Pull down the shade: "Listening for 'Hey Jarvis'" with "On-device. Nothing leaves the phone until you say it." and **Turn off**.
8. Go to the home screen. Say "Hey Jarvis". The phone vibrates and a "Wake word heard" notification appears; tap it and Talk opens, listening. (With Herms on screen, Talk opens directly.)
9. End Talk. Pull down the shade and tap **Turn off** on the wake-word notification. It disappears and the toggle shows off.

## V2: dataSync, while a run is in progress

**Prerequisites:** C1, and an approval-gated scenario in the reviewer sandbox, redeployed on the hosted sandbox. Today's `feat/review-coverage` sandbox writes the final reply first, then creates the approval card and clears the working state, and approving does not resume anything. A run that waits for approval and finishes afterwards cannot be shown on it. A backend change being written adds that scenario: the prompt text "approval please" starts a run that stops at an approval card, stays working, and finishes its reply only after **Approve**. Record V2 only after both are live, and check the scenario once by hand first.

It shows the service starting from a user action, staying only while the run works, and ending by itself.

1. Me > This phone > Alerts: show the background setting as it reads after C1.
2. Open "Launch checklist · example project". Type `approval please` and send.
3. Press Home right away. Pull down the shade: "Hermes Demo is working" with the chat title and elapsed time.
4. Wait for the approval notification. Show that the working notification is still there: the run is waiting on the approval.
5. Approve from the notification (biometric prompt), or open the app and tap **Approve** on the card.
6. Keep the shade open until the run finishes its reply. The working notification disappears by itself.
7. Show that nothing stays in the shade after the run, and that the full reply is in the chat.

## V3: alarm sound and full-screen alarm

Shows that sound plays only for an alarm the user set, the exact-alarm and full-screen grants, and the ways the alarm ends.

1. Cron tab > **Alarm clock**. The screen shows "Exact alarms are off" and "Full-screen alarms are off" on a fresh install.
2. Tap **Allow** next to exact alarms. In system settings turn on "Allow setting alarms and reminders". Go back; the warning is gone.
3. Tap **Allow** next to full-screen alarms. Turn it on. Go back; the warning is gone.
4. Tap **Add alarm** and set it for two minutes from now. Show the next-alarm line.
5. Lock the phone and wait. When it fires, the alarm sound plays and the "Prove you're awake" screen opens over the lock screen.
6. Pull down the shade briefly: the ongoing "Wake" notification.
7. Pick **Emergency stop**, enter `review-sandbox`, confirm. The sound stops and the notification goes away.
8. Back in Alarm clock, tap **Test the alarm now**. It rings at once. Pick the reading proof, answer the questions, and show the sound stopping when the proof passes.
9. Optional, for the full-screen intent: turn full-screen alarms off again in system settings, run **Test the alarm now** with the phone locked, and show that it still rings as a high-priority notification that opens the proof screen.

## Before attaching links

- Watch each video once for the invitation, private host names, personal notifications or non-English text. Re-record if any appear.
- Put each link in the matching foreground service entry in [declarations.md](declarations.md).
- Re-record if a later build changes any step shown.

## Sources

Read on 2026-11-04.

- Foreground service and full-screen intent requirements (video rules per type): https://support.google.com/googleplay/android-developer/answer/13392821
- Declare permissions for your app (video formats): https://support.google.com/googleplay/android-developer/answer/9214102
- Exact Alarm Permission: https://support.google.com/googleplay/android-developer/answer/9888170#exact_alarm
