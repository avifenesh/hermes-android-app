# 0.16.4 full APK verification

The full sideload APK adds a bundled offline privacy policy reachable before pairing, from Me and from the support flow. It updates 0.16.3 without replacing that version's APK bytes.

| Identity | Value |
|---|---|
| Package | `dev.avifenesh.hermes` |
| Version | `0.16.4` / versionCode `36` |
| APK file | `Hermes-0.16.4.apk` |
| APK SHA-256 | `5dc20a1dda2e7659030538041707c5b2c1047ec7bac9c076dd37cd28c22c884e` |
| Signer SHA-256 | `cdd83d31e6ea91a853a973ed8647cb3a6f5408d1301b2e1e6d06a04bd33a27ee` |

## Executed checks

- Full release build succeeded. 36 full-debug JVM tests passed with zero failures/errors; full-release lint reported zero errors.
- Three API 31 signed-release tests passed: update from 0.16.3 retained pairing/chat; general support excluded chat; selected-message reporting required explicit quote consent. A local no-network mail sink received the preview's exact recipient and body. No real email was sent.
- Offline privacy navigation passed before pairing and from paired Me and support. Both themes, normal size and 320dp width with 200% text were exercised before pairing. The policy scrolled to its final sources. Leaving the support dialog for privacy did not restore its unsent draft.
- Packaged privacy bytes equal `docs/privacy-policy.md`. Policy rendering is local; it does not fetch content or launch external links.
- Distribution/signature and legal guards passed. ZIP alignment and all eight 64-bit native libraries passed 16 KiB alignment checks.

The bundled server plugin remains version `0.16.2`; this client-only update does not change the wire contract. Install the complete bundled plugin and require `security.signature_replay: 2`. The two version strings need not match.

## Limits

Focused emulator and artifact checks are not security certification or complete Android qualification. Physical-phone microphone quality, OEM battery behavior, real FCM, real email providers and a full TalkBack audit remain unverified. Earlier native-runtime checks apply only to their recorded versions. No Play submission or reviewer-backend deployment is included.
