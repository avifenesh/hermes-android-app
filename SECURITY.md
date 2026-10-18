# Security

## Reporting a vulnerability

Send private reports to **[hello@tiyuvta.ai](mailto:hello@tiyuvta.ai)**. Do not post an unpatched exploit or private data in a public issue. Include the app version/package, gateway/plugin source revision, affected endpoint or screen, impact and minimal sanitized reproduction steps. Synthetic keys, devices and test data are preferred.

Never send signing keys, Firebase service-account keys, pairing links, device tokens, approval signatures, full databases or unredacted chat/log archives. If sensitive data is essential to demonstrate a problem, first ask how to share it safely. Email is not a secure vault. No response-time SLA, bug bounty or fixed remediation deadline is promised.

For ordinary installation help, use the same address with a sanitized description. See the [privacy policy](docs/privacy-policy.md) for voluntary support mail and self-hosted data responsibilities.

## Supported security baseline

The public distribution target is the full Android package **`dev.avifenesh.hermes`**, on **Android 12+**. The **0.16.8 / versionCode 40** candidate has [artifact-specific verification](docs/release-0.16.8.md); availability is shown on the Releases page. Use the latest qualified signed release and its matching bundled plugin. Older releases do not have a promised backport/support window. Play and reviewer hosting are deferred and are not prerequisites for GitHub sideloading.

The server security minimum is **`security.signature_replay: 2`**, advertised by `/v1/health` and authenticated metadata. Missing or older values do not meet this baseline. Install the **entire** bundled `server/mobile/` directory, not selected files from mixed versions, and activate it through your gateway's supported service control. An app update alone does not repair an old gateway. See [installation and updates](README.md#install-the-gateway-plugin) and the [replay security design](docs/request-replay-security.md).

The marker is not an attestation and does not establish that a server is trustworthy. Authenticate the intended HTTPS host. No minimum tagged Hermes release has been established. The recorded Hermes revision and narrowly tested scope are in [QA](docs/android-qa.md); passing isolated tests does not establish the state of a running gateway.

## Verify an APK before installing

Use the APK and checksum from the same repository release. Compare `sha256sum` output to that release's APK checksum, then verify the signature using Android SDK Build Tools:

```sh
APK=Hermes-0.16.8.apk  # replace with the exact published filename
printf '%s  %s\n' \
  0eba259b916979fa7da1ba80077f19611ca7693916d30de95e17166545bd0f71 "$APK" | sha256sum --check -
apksigner verify --verbose --print-certs "$APK"
```

Require successful verification and this approved **signing certificate SHA-256**:

```text
cdd83d31e6ea91a853a973ed8647cb3a6f5408d1301b2e1e6d06a04bd33a27ee
```

A certificate fingerprint identifies a signer; it is not the file hash. Comparing two values both obtained from an untrusted APK does not authenticate a release. Never install after a checksum, package or signer mismatch. Do not uninstall an existing app to bypass Android's signature check. Legitimate full updates preserve the package and signer and increase the version code. Locally signed/debug builds have a different identity for update purposes.

## Trust boundaries

- **Gateway and host:** the gateway can run agent tools with the host's permissions. Treat installing plugins, pairing a device and widening tool permissions as granting access to that host. Use a dedicated least-privilege account and a trusted gateway, not an unknown public endpoint.
- **Transport:** keep the API bound to localhost behind authenticated HTTPS or a private-network HTTPS proxy. Do not publish its raw HTTP listener. TLS is required even though requests are signed; signing does not encrypt traffic.
- **Pairing:** one-use, expiring codes enroll device and approval public keys. Keep links/QRs private. Device tokens are revocable. Revoke a lost phone with `hermes mobile revoke <device-id>` against the correct home/profile.
- **Requests:** a P-256 device signature binds method, raw path/query, timestamp and body hash. Persistent normalized signature claims prevent exact and equivalent-signature replays. The clock and replay database are part of the security boundary.
- **Power-widening actions:** a separate Android Keystore key, unlocked with biometrics or device credentials, signs the exact guarded action. Request authorization is not a substitute for the approval signature. Read each confirmation before approving.
- **Kitty input:** named keys, clicks and remote scrolling always need a fresh approval proof under the separate `fleet-input` domain, bound to the exact agent and raw request-body hash. A fixed server-side helper checks foreground identity and uses Kitty's native encoders. Clicks and scrolling reject stale frames. Mouse-disabled terminal apps receive no fabricated mouse escape text; use keys instead.
- **Full-flavor phone control:** Accessibility can read visible screen content and act in other apps. It requires local opt-in, server arming and a visible Stop notification. Stop locally, disable the service or revoke the device when access is no longer needed. Password/protected-screen restrictions are defenses, not a guarantee that all private content is hidden.
- **Push:** FCM sends wake hints/identifiers, not chat or phone-command payloads. Google still handles tokens and service/SDK data. Commands are fetched over the signed API by the target armed device. FCM configuration is optional; it is not a security bypass or a delivery guarantee.
- **Storage/providers:** app pairing keys use Android Keystore; gateway data and configured providers have separate storage and retention. Revocation and unpairing are not deletion. System speech recognition can use cloud processing. See [privacy](docs/privacy-policy.md).

## Recovery, backups and rollback

Back up the plugin and mobile data privately while the gateway is stopped. Copy source **contents** into a clean destination as shown in the README; nested/stale plugin copies can leave the old code active. Do not publish backup directories or service credentials.

Replay claims survive a normal restart but not restoration of an older database. A vulnerable server rollback also removes protection. If captured pre-upgrade requests must be invalidated, follow the revocation/re-pairing or ingress-closure procedure in [replay compatibility and limits](docs/request-replay-security.md#compatibility-and-limits). Do not roll the clock/database backwards during the signature validity window. Keep ingress closed on uncertain recovery until a secure complete plugin is active.

## Verification limits

The public phone/replay scripts use synthetic devices and isolated temporary data roots. Run them directly with the installed Hermes managed runtime, without plugin discovery or a live gateway. They do not prove physical-phone biometrics, OEM behavior, real FCM delivery, whole-application security or the current deployment state. See [recorded checks and remaining device qualification](docs/android-qa.md).
