# Closed testing and production access

**Draft for owner review.** Requirements read on 2026-11-04. No test track, tester list or Google Group exists yet, and no clock has started.

## The requirement as it applies today

Google requires personal developer accounts created after 2023-11-13 to "run a closed test for their app with a minimum of 12 testers who have been opted in continuously for at least 14 days" before applying for production access. Production and open testing stay disabled until then.

- **Applies to Avifen if** the account was created after 2023-11-13. The owner confirms this on the Play Console Dashboard; it shows the production access steps when they apply.
- **Continuous means consecutive.** "Testers who opt in, test for fewer than 14 days, and then opt out do not count." Opting out and back in restarts that tester's 14 days.
- **Count at application time.** At least 12 testers must be opted in on the day you apply, each for the preceding 14 days.
- **Engagement is judged.** Google may ask for more testing for "fewer than 12 opted-in testers or insufficient tester engagement".
- **Order.** Closed testing starts only after app setup is complete: every App content declaration in [declarations.md](declarations.md) is done. The closed release is reviewed before testers can join. The opt-in link appears only once the release is "Published".
- **Internal testing does not count, and it blocks.** "A user who opts into your app's internal test is no longer eligible to receive an open or closed test." Do not add closed testers to an internal test.
- **Review time.** Production access review "usually takes seven days or less".

## Plan

### 1. Before the clock starts

1. Land C1 to C4 from [declarations.md](declarations.md), rebuild, requalify, and upload the AAB to the closed testing track.
2. Complete every App content declaration. Record the demo videos ([demo-videos.md](demo-videos.md)).
3. Create a Google Group for testers, for example `herms-testers@googlegroups.com` (owner picks the name). Settings: anyone can ask to join; the owner approves; members cannot see each other's addresses.
4. In Play Console: Test and release > Testing > Closed testing > Manage track > Testers > **Google Groups**, enter the group address. Set the feedback channel to `hello@tiyuvta.ai`. It appears on the opt-in page.
5. Set the closed track's countries: Closed testing > **Manage track** > **Countries / regions**. The track syncs with production by default; click **Unsync** to target it on its own (allowed while no bundle is in production, as before launch), then **Edit countries / regions**. Include the country each tester's Google Play account is registered in. Targeting follows that account country, not where the tester is. This does not widen production availability. Internal testing has no country targeting.
6. Send the release for review. Wait for "Published".

### 2. Recruit

- **Target 20 testers.** That leaves room for 8 to drop out and still keeps 12.
- **Who:** people who already run Hermes Agent and can install the mobile plugin on their gateway. Google advises testers who "represent your app's intended future audience", and part 1 of the application asks whether tester use "matched expected production user behavior".
- **Where:** the owner's own network and Hermes Agent user communities. This is owner work. Say plainly that Herms is an independent client, not a Nous Research app.
- **Testers without a gateway:** give them the reviewer sandbox invitation privately. Their use will be synthetic, so keep them a minority. After the test, rotate the invitation (stop the sandbox, remove only `STATE/invitation.txt`, restart) and update the Console sign-in details.

### 3. What each tester does

Send this to each tester:

1. Join the Google Group with the Google account used for Play on your Android phone (Android 12 or later).
2. Open the opt-in link we send (copied from Play Console; normally `https://play.google.com/apps/testing/dev.avifenesh.hermes.play`) and tap **Become a tester**.
3. Install Herms from `https://play.google.com/store/apps/details?id=dev.avifenesh.hermes.play`.
4. Pair with your own gateway (`hermes mobile pair`), or with the sandbox if we sent you an invitation.
5. **Stay opted in for at least 14 days.** Do not tap "Leave the program" on the opt-in page. Keep the app installed, and use it at least a few times a week.
6. Try: chat, approvals, cron, Talk, the wake word, a Wake alarm, a content report. Send feedback through Play's private feedback or to hello@tiyuvta.ai.

### 4. Keep 12 opted in for 14 days

- Day 0 is the day the 12th tester opts in. Plan the application for day 15 or later, counted from the last of the first 12 opt-ins.
- Check the opted-in count every two or three days. If it falls under 15, recruit again at once. A new tester needs a full 14 days.
- Remind testers at day 7 and day 12 not to leave the program.
- Ship at least one closed-test update that fixes something testers reported. It gives honest answers for part 3 of the application.
- Keep a private feedback log: date, tester (initials), feedback, action, release.

## Production access application: draft answers

Google's form has three parts. Placeholders in braces are filled from the real test.

### Part 1: About your closed test

- **How easy was it to recruit testers?** {owner picks the honest option}
- **Did testers use all available features?**

```text
Testers used chat with streamed replies, sensitive-action approvals with biometric confirmation, cron schedules, Talk, on-device wake word, Wake alarms with reading/photo/step proofs and content reports. {N} of {M} testers paired with their own Hermes gateway; {K} used our synthetic sandbox. {Features used less, and why.}
```

- **Did tester usage match expected production behavior?**

```text
Mostly yes. Production users run their own Hermes gateway, and {N} testers did the same, using the app daily to follow and approve agent work. Sandbox testers saw scripted replies instead of real agent output, so their sessions were shorter. {Other differences.}
```

- **Summary of feedback and how it was collected:**

```text
Feedback came through Google Play private feedback, email to hello@tiyuvta.ai and the in-app report flow. Main themes: {theme 1}, {theme 2}, {theme 3}. We logged each item with its outcome.
```

### Part 2: About your app

- **Target audience:**

```text
Adults who run their own Hermes Agent gateway (an open-source AI agent on their own computer or server) and want to follow and control it from Android: developers, researchers and technical professionals. Not for children or teens.
```

- **Value proposition:**

```text
Herms is a native Android client for the user's own Hermes Agent gateway. Users read streaming replies, send files and voice, approve sensitive actions with biometric confirmation, and manage cron jobs and an alarm clock from their phone. The publisher hosts no chat backend: messages go only to the gateway the user pairs with. It is an independent client, not affiliated with Nous Research.
```

- **Estimated installs in the first year:** {owner picks}. The audience is limited to people who run a Hermes gateway.

### Part 3: About your production readiness

- **Changes made from the closed test:**

```text
{List each change with its version, for example: "0.16.17: fixed ... reported by testers".}
```

- **How you decided the app is ready:**

```text
Every release is built from a committed source tree and qualified on the exact AAB and a bundle-generated APK: signing, manifest permission boundary, 16 KB native alignment, legal notices and the packaged privacy policy, plus unit tests and lint. During the closed test there were {no open crash reports / N fixed}. Reviewer access works against a hosted synthetic sandbox, and every declared permission has a working denial path.
```

## Sources

Read on 2026-11-04.

- App testing requirements for new personal developer accounts: https://support.google.com/googleplay/android-developer/answer/14151465
- Set up an open, closed, or internal test: https://support.google.com/googleplay/android-developer/answer/9845334
- Distribute app releases to specific countries (testing track targeting, Unsync): https://support.google.com/googleplay/android-developer/answer/9874837
