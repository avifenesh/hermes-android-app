# 0.16.3 candidate verification

Publication is paused pending privacy and export review. This is focused verification, not security certification.

- Package: `dev.avifenesh.hermes`
- Version: `0.16.3`, versionCode `35`
- APK SHA-256: `f139dd65b6f88057e8efe8a8e9b7f3aece7c006588bf5d9cd36f9e1879e76b64`
- Signing certificate SHA-256: `cdd83d31e6ea91a853a973ed8647cb3a6f5408d1301b2e1e6d06a04bd33a27ee`

## Recorded checks

- Full release build succeeded; 36 full-debug JVM tests passed, zero failures or errors. Full-release lint reported zero errors.
- Two Android intent/Parcel tests passed during support-flow qualification.
- Three API 31 signed-release upgrade/support checks passed against the APK hash above. They used an isolated synthetic gateway and a local mail sink, not a real email provider.
- The consent control's accessibility tree exposed a labelled switch, tested off to on. This is not a complete TalkBack audit.
- Distribution, signature and packaged legal guards passed. ZIP alignment and all eight 64-bit native libraries passed 16 KiB alignment checks.
- A downloaded GitHub asset matched the APK hash before publication was paused.

The bundled plugin retains version `0.16.2`; the app's `0.16.3` changes do not require a server version bump. Install the complete bundled plugin and require `security.signature_replay: 2`. App and plugin version strings need not match.

## Limits and outstanding work

Physical-phone microphone quality, power use, OEM behavior, real push and real email-provider behavior are not certified. Support mailbox practices need owner confirmation. The candidate has no in-app privacy-policy link. These checks do not establish Google Play readiness. No Play submission or reviewer-backend deployment occurred.
