# Hermes for Android

## One source of truth

- `avifenesh/hermes-android-app` is the sole development and release repository.
- Develop both `android/` and `server/mobile/` here. Use pull requests to `main`.
- Retired repositories are private read-only archives. Do not develop there,
  export from them, merge their history, or push private refs to this repository.
- Never commit signing keys/properties, local SDK configuration, pairing material,
  private design captures, personal data or gateway databases. Local publisher
  keys belong under ignored `android/keys/`; build artifacts belong under `dist/`.
- Publisher commits use the approved noreply identity for author and committer.

## Build and qualification

- Read the README, `docs/distribution-flavors.md` and `SECURITY.md` first.
- Use JDK 21. Build explicit full or Play tasks; do not silently drop ABIs or features.
- Run focused checks for changed functionality. Security and terminal fixtures must
  use disposable `HERMES_HOME` roots; never inject test input into live sessions.
- Keep probes in debug/test sources and verify they are absent from release APKs.
- Release tasks require publisher signing; no debug-key fallback for distribution.
- Publish the tested APK and checksum here. Verify download bytes and signer.
  Do not replace an already published version with different APK bytes.
- Stop owned Gradle/Kotlin daemons, emulators and fixture servers after checks.

## Gateway deployment

- The gateway's installed `plugins/mobile/` is a deployment copy, not another repo.
- Deploy the complete compatible plugin and verify its files. Use the supported
  gateway restart control; never bypass an in-process restart guard.
- Copied files are staged, not active. Verify activation before claiming it live.
