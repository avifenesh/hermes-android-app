# Account deletion: does Play's requirement apply?

**Draft for owner review.** Policy read on 2026-11-04. Applies to the Play build `dev.avifenesh.hermes.play`, 0.16.16 candidate.

## Conclusion

**The account deletion requirement does not apply.** Herms does not let users create an app account, and it does not send users to an account creation flow. Pairing enrolls this phone into a Hermes gateway that the user already runs. The Data safety form should say the app does not allow account creation, and should still answer **Yes** to "users can request that their data is deleted", for the data the publisher holds.

This is a reasoned classification, not an exemption Google has granted. Google can disagree at review. If it does, the minimum change is in the last section.

## What the policy says

The [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311) says: "If your app allows users to create an account from within your app, then it must also allow users to request for their account to be deleted." The deletion option must be in the app and on a web resource linked in Play Console.

The [account deletion help article](https://support.google.com/googleplay/android-developer/answer/13327111) defines the terms:

- "An app account is a unique user identity that developers provide as a user-facing feature to serve the user across applications and/or devices (can often include use of usernames, email addresses, and passwords). App accounts provide a mechanism for a user to authenticate and generally include a mechanism to verify an identity."
- "Your app supports account creation within the app if a user can complete creating an app account directly in the app or if the app directs the user to an app account creation flow outside of the app."
- "All developers must complete new Data deletion questions in the Data safety form."
- "Accounts that are created and operated offline are not app accounts and do not fall within policy scope."

## How Herms works against that definition

| Test from the definition | Herms |
|---|---|
| A user identity that the **developer provides** | No. The publisher runs no identity service. The gateway that issues device credentials is installed and run by the user or their operator. |
| Created in the app, or the app sends the user to a creation flow | No. The pairing screen tells the user to run `hermes mobile pair` on a gateway they already control, then scan or paste the one-time link. That enrolls a device; it does not create a person's account. |
| Usernames, emails, passwords | None. The phone generates two P-256 key pairs in Android Keystore. The gateway stores the public keys, a random device ID and token, the device label and timestamps. |
| Serves the user across apps or devices | No developer identity spans devices. Each phone pairs separately and gets its own device record. The gateway's own user model, if any, belongs to the gateway operator. |
| Developer can see or delete it | No. The publisher has no access to any gateway. |

The closest common case is an email client that signs in to the user's own mail server: the account belongs to the mail server, not to the client's developer.

One fact cuts the other way, and a reviewer may weigh it: the publisher also writes the open-source gateway plugin (`server/mobile/`) that issues the device credentials. The plugin is software the user installs and runs. The publisher does not operate it.

## Deletion paths that exist anyway

| Path | What it does | What it does not do |
|---|---|---|
| **Unpair this phone** (Me > This phone > Connection) | Asks the gateway to revoke this device, then deletes the phone's Keystore keys, token and preferences. | The gateway keeps the revoked device record, chats, files and provider copies. Revoking is not deletion. |
| **Revoke** another device (same screen) | Revokes that device's access at the gateway. | Same: the record and history stay on the gateway. |
| **Forget** a machine (Me > Machines) | Drops the phone's token for that machine. | The device stays enrolled until revoked on that machine. |
| Android **Clear storage** or uninstall | Removes all app-private data on the phone. | Exported Downloads and copies shared with other apps stay. Gateway data stays. |
| **Delete gateway data** | The gateway operator deletes chats, files, sessions and device records with the gateway's own tools. | The publisher cannot do this for the user. |
| **Delete a content report** | The user emails hello@tiyuvta.ai with the report ID. The publisher deletes that row by exact ID. Instructions: `https://hermes-android-reports.aviarchi1994.workers.dev/delete` | Cloudflare recovery copies are not erased immediately. The original conversation on the gateway is not touched. |
| **Delete a support email thread** | The user asks at hello@tiyuvta.ai. | Mail provider trash and backups may remain. |

The publisher holds only two kinds of user data: voluntary reports and support emails. Both have a deletion request path.

## Data safety answers that follow

- **Account creation:** the app does not allow users to create an account.
- **Do you provide a way for users to request that their data is deleted?** **Yes.** Google allows this answer for any mechanism to request deletion, including "a dedicated email alias". It may be selected "even if you need to retain certain data for legitimate reasons".
- **Data deletion web link**, if the form offers the optional field: `https://hermes-android-reports.aviarchi1994.workers.dev/delete`.
- **Account deletion URL:** not applicable.

Required before submitting this answer (C4 in [declarations.md](declarations.md)): the `/delete` page must name the app or the developer as shown on Play, "Herms for Hermes Agent" or "Avifen". Today it says "Hermes Android". The page should also say plainly that gateway data is deleted by the gateway operator and that unpairing revokes access without deleting history. Edit `report-worker/delete.html`, then redeploy the Worker.

## If Google rules that pairing is account creation

The minimum change that would satisfy the policy text:

1. **In app:** add "Delete this phone's data on this gateway" next to Unpair. It calls a new plugin endpoint that deletes the device record, its push token, and its uploads, and offers to delete the chats this phone created. Then it clears local data as Unpair does.
2. **On the web:** extend `/delete` with the same instructions, plus a contact path for users who no longer have the app. Their request goes to their gateway operator, because the publisher cannot reach the gateway.
3. **Data safety:** declare account creation as "Other: device pairing with your own Hermes gateway" and enter the `/delete` URL as the account deletion link.

## Sources

Read on 2026-11-04.

- Understanding Google Play's app account deletion requirements: https://support.google.com/googleplay/android-developer/answer/13327111
- User Data policy, Account Deletion Requirement: https://support.google.com/googleplay/android-developer/answer/10144311
- Data safety form, deletion request mechanism FAQ: https://support.google.com/googleplay/android-developer/answer/10787469
