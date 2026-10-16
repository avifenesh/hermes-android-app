# Hermes for Android

A native Android client for [Hermes Agent](https://github.com/NousResearch/hermes-agent). It connects to a Hermes gateway you choose. There is no publisher-operated chat backend. Your phone, gateway and configured providers can still store data; speech recognition, model and TTS services may process data off-device.

**Distribution:** GitHub full APK first. The **0.16.4 / versionCode 36** candidate passed the [focused checks](docs/release-0.16.4.md). Check the Releases page for availability. Play distribution and reviewer hosting remain deferred.

[Privacy](docs/privacy-policy.md) · [Security](SECURITY.md) · [Verification scope](docs/android-qa.md) · [Distribution details](docs/distribution-flavors.md)

## What it does

- Chat with your agent. Steer or queue while it works. Stop a run.
- Tool calls fold into activity cards with diffs. Tokens, cache hit, cost and context show per reply.
- Approve dangerous commands with biometrics or device credentials.
- Manage cron jobs, models, settings, skills, memory and profiles through Hermes' own write paths.
- Use optional Talk, alarms and offline microWakeWord v2 Hey Jarvis detection.
- Full distribution only: opt-in Accessibility phone control and user-directed APK attachment installation.
- Optional FCM push carries wake hints, not chat or command contents. The app fetches content over the authenticated API.

## Install the full APK

Requires **Android 12 or later (API 31+)** and access to your own configured Hermes gateway. The full package is **`dev.avifenesh.hermes`**. Only use a signed full APK and checksum published together on this repository's Releases page. A source version bump does not mean an APK has been published.

Before installation, verify the downloaded APK using `sha256sum` and Android SDK Build Tools' `apksigner` (add your installed Build Tools directory to `PATH`):

```sh
# Set APK to the file you downloaded. Compare this digest with its release checksum.
APK=Hermes-0.16.4.apk
printf '%s  %s\n' \
  5dc20a1dda2e7659030538041707c5b2c1047ec7bac9c076dd37cd28c22c884e "$APK" | sha256sum --check -
apksigner verify --verbose --print-certs "$APK"
```

The approved **signing certificate SHA-256** is:

```text
cdd83d31e6ea91a853a973ed8647cb3a6f5408d1301b2e1e6d06a04bd33a27ee
```

The certificate fingerprint is NOT the APK checksum. Require a successful signature verification, this exact signer fingerprint, and the matching release's file checksum. If any differ, do not install. Do not trust a fingerprint taken only from the APK itself. See [artifact and update security](SECURITY.md).

Open the verified APK on your phone and allow installation from that source when Android asks. Disable that source's installation permission afterward if you do not need it. For updates, install over the existing full app without uninstalling: the package and signer must match, and the version code must be newer. A locally self-signed/debug APK cannot update the publisher-signed app. Uninstalling removes local data and pairing keys; do not use it to work around a signature mismatch.

## Install the gateway plugin

First install and configure Hermes using its [installation guide](https://hermes-agent.nousresearch.com/docs/getting-started/installation). This plugin uses Hermes' [platform/plugin APIs](https://hermes-agent.nousresearch.com/docs/user-guide/features/plugins); it is not a standalone server.

**Tested baseline, not a minimum-version promise:** the 15 focused phone transport checks passed against the local Hermes source revision `9706739432c599bb04eb970eac9229c4b7a1f2e1`, using its managed Python 3.14.7 runtime. Tracked Hermes sources were unchanged; an unrelated untracked test file was present. This is not certification of a tagged Hermes release, every API surface or a fresh-install deployment. No minimum tagged Hermes version has been established. See [QA](docs/android-qa.md) for scope and reproducible checks.

**Security requirement:** use the complete bundled `server/mobile/` from the same release, not an old plugin copied from another install. The server must advertise **`security.signature_replay: 2`**. An updated app alone cannot fix an old server. Read [request and approval replay protection](docs/request-replay-security.md), including backup/rollback limitations.

The examples below are run from this repository's root. `HERMES_HOME` selects the intended profile/data root; it defaults to `~/.hermes`. Stop that profile's gateway before replacing files. These are operator installation instructions, not actions performed by the test harness.

### Fresh install

```sh
set -eu
umask 077
home="${HERMES_HOME:-$HOME/.hermes}"
export HERMES_HOME="$home"
test -f server/mobile/plugin.yaml
test ! -e "$home/plugins/mobile"
test ! -L "$home/plugins/mobile"
mkdir -p "$home/plugins/mobile"
cp -R server/mobile/. "$home/plugins/mobile/"
test -f "$home/plugins/mobile/plugin.yaml"
hermes plugins enable mobile
```

The `/.` copies the **contents** into the destination. The installed manifest must be `plugins/mobile/plugin.yaml`, never `plugins/mobile/mobile/plugin.yaml`. If the destination already exists, use the update procedure rather than repeating a directory-to-directory copy.

### Update an existing plugin

Close API ingress and stop the gateway using the control for the target profile/service before this block. Keep it stopped until copying finishes. Back up its plugin and mobile data together while SQLite is not being written:

```sh
set -eu
umask 077
home="${HERMES_HOME:-$HOME/.hermes}"
export HERMES_HOME="$home"
test -f server/mobile/plugin.yaml
test -d "$home/plugins/mobile"
test ! -L "$home/plugins/mobile"
mkdir -p "$HOME/.cache/hermes-mobile-backups"
backup=$(mktemp -d "$HOME/.cache/hermes-mobile-backups/update.XXXXXX")
if test -d "$home/mobile"; then
  mkdir "$backup/mobile"
  cp -R "$home/mobile/." "$backup/mobile/"
fi
mv "$home/plugins/mobile" "$backup/plugin"
mkdir "$home/plugins/mobile"
cp -R server/mobile/. "$home/plugins/mobile/"
test -f "$home/plugins/mobile/plugin.yaml"
printf 'Private backup: %s\n' "$backup"
```

This replaces the whole plugin, so removed files and an old accidentally nested `mobile/` do not survive. It leaves the active `mobile/` data directory in place. The backup may contain conversations, pairing credentials and optional Firebase credentials: keep it private and do not attach it to an issue. Keep the previous code backup for recovery, but do not restore a vulnerable plugin or stale replay database to an exposed server. If copying fails, leave the gateway stopped and restore a known-secure complete plugin before reopening ingress.

### Dependencies and configuration

The plugin uses the Hermes runtime's dependencies, not a separate plugin venv. It imports `aiohttp`, `cryptography` and `httpx`. Optional FCM sending also needs `google-auth` (`google.oauth2` and its requests transport). Resolve missing dependencies in the managed Hermes runtime using the supported Hermes package-management workflow; installing into an unrelated system Python will not help. The plugin's `check_requirements` probes only `aiohttp`, not all of these imports.

These are the plugin's environment variables. Set them in the environment of the gateway you start. For a managed service, persist them in that service's environment configuration; `hermes gateway restart` does not guarantee that exports from your current shell reach an existing service. Do not put non-secret settings into Hermes' secrets `.env` file.

```sh
export MOBILE_ENABLED=1
export MOBILE_ALLOWED_USERS=owner
export MOBILE_PUBLIC_URL=https://myhost.tailnet.ts.net:9445
export MOBILE_BIND=127.0.0.1:47930
# Optional: MOBILE_HOME_CHANNEL (default digests), MOBILE_AGENT_NAME,
# MOBILE_USER_NAME, MOBILE_PHONE_CC (country code for wake escalation).
```

Bind to localhost and expose only through authenticated HTTPS, for example:

```sh
tailscale serve --bg --https=9445 http://127.0.0.1:47930
```

The phone must be able to reach that HTTPS URL and trust its certificate. With Tailscale, both devices need access to the tailnet; an HTTPS tunnel is another option. Do not expose the plaintext localhost listener directly to the Internet.

For an initial foreground run from the configured shell, use `hermes gateway run`. For an installed service, use its supported start/restart control after saving its environment. Once your intended server is running, check `https://YOUR_SERVER/v1/health`: require `api: 1` and `security.signature_replay: 2`. The marker is a server assertion, not proof of an unknown host's trustworthiness.

Then use the same profile/home for pairing:

```sh
hermes mobile pair                 # one-use QR/link, valid 10 minutes
hermes mobile devices
hermes mobile revoke DEVICE_ID    # replace DEVICE_ID with the listed lost/unwanted phone
```

Scan the QR or paste the pairing link on the first screen. Pairing links are secrets; never publish one. `hermes mobile check` is an operator self-check against real configured state, **not** an isolated public test. Do not run it against a production home for a test-suite receipt.

### Optional Firebase push

Without push, text chat and live connection sync still work. Android may limit background dataSync services; periodic WorkManager sync is a fallback, not an instant-delivery guarantee.

Create a Firebase Android client for `dev.avifenesh.hermes` in your own project. Put its server service-account JSON in `$HERMES_HOME/mobile/fcm-service-account.json` (default `~/.hermes/mobile/fcm-service-account.json`). Keep that credential server-only and restrict its filesystem access. Do not ship it in an APK, repository, client config or bug report.

Put **only client configuration** in `$HERMES_HOME/mobile/fcm-client.json`:

```json
{
  "api_key": "YOUR_FIREBASE_ANDROID_API_KEY",
  "app_id": "1:YOUR_PROJECT_NUMBER:android:YOUR_APP_ID",
  "project_id": "YOUR_FIREBASE_PROJECT_ID",
  "sender_id": "YOUR_PROJECT_NUMBER"
}
```

All four values are strings. `app_id` is the Firebase application ID, not the Android package name. The field names come from `FirebaseOptions.Builder` in `App.kt`. The paired app retrieves this through signed `/v1/push-config`; no bundled `google-services.json` is needed. The server returns it only when service-account configuration is also present. Use a matching project/client and appropriate Firebase API restrictions. Never add private keys to this client JSON: it is sent to paired phones.

Google Play services, successful token acquisition and server token registration are required before push is ready. Config files alone do not prove delivery. Changing Firebase projects requires restarting the Android app process. A separately installed Play-flavor package needs its own appropriate Firebase registration. Google services can process installation identifiers, tokens and SDK/service diagnostics even though wake hints contain no message text.

## Phone control (full distribution only)

From **Me → This phone → Control**, enable **Hermes phone control** in Android Accessibility settings, then turn on **Allow agent control**. Arming uses the fingerprint/PIN signature used for other power-widening actions. On a sideloaded install, Android may first require **Settings → Apps → Hermes → ⋮ → Allow restricted settings**. Grant this only after verifying the APK and understanding the access.

Enable the toolset on the mobile platform if needed:

```sh
hermes tools enable --platform mobile phone_use
```

The agent gets one `phone_use` tool. Start with `action=state` to read indexed elements and a screenshot. It supports taps, long presses, text entry, scrolling, swiping, system keys, app launch, app lists and window lists. Coordinates use physical screen pixels. Read state again after changing the screen.

State defaults to the focused screen with keyboard/background-window noise excluded. Use `scope="all"` and `include_keyboard=true` when needed. Elements expose `checkable`, `checked`, `selected` and `focused`. Taps accept `method="auto"`, `"gesture"` or `"accessibility"`. Auto uses one short physical tap for checkable controls; elsewhere it falls back only on explicit Accessibility rejection. An accepted dispatch is not proof of its effect.

- Control is opt-in per phone. Only the armed phone can fetch or answer its commands.
- SSE/FCM carry wake hints. Commands expire, serialize and are canceled by disarming or revocation.
- **Stop control** on screen or in the notification is a local kill switch even if the server is unreachable. A visible notification is required for arming.
- The screen must be on and unlocked. Password-field text is redacted and password typing is refused. Android can block screenshots on protected screens; these safeguards are not an exhaustive privacy boundary for every app.
- Switching machines or unpairing turns phone control off.

## Platform and privacy limits

Camera, microphone and step sensors are optional; denying their permissions does not block text chat. Pair by pasting the link when Google code scanning is unavailable. Google code scanning and Firebase push require Google Play services; a Google-free device does not gain those features from this APK.

Wake-word detection runs locally, but **speech recognition is not guaranteed offline**: Talk prefers an available on-device recognizer and can fall back to the system recognizer, which may use a cloud service. Server-configured TTS can use providers, with device TTS as a fallback. Models, recognition/TTS providers, Firebase and Google SDKs have their own terms and data practices. Bundled open-source notices do not license third-party service access. See [privacy](docs/privacy-policy.md), [licenses](licenses/README.md) and [model provenance](licenses/MODEL-PROVENANCE.txt).

Support and private security reports: **[hello@tiyuvta.ai](mailto:hello@tiyuvta.ai)**. Include version/package and sanitized reproduction steps, not tokens, pairing links, keys, full databases or private chats. Sending support mail is voluntary and uses your mail app/provider. See [SECURITY.md](SECURITY.md).

## Focused verification

From this repository's root, set `HERMES_PYTHON` to the Python executable in your **installed Hermes managed runtime**. On an older venv-based installation it may be `~/.hermes/hermes-agent/venv/bin/python`; confirm it is the active runtime, not a stale interpreter from before a Hermes upgrade. The phone harness disables automatic lazy dependency installs/relaunches. It needs the existing managed dependencies and the Hermes source checkout at `~/.hermes/hermes-agent`.

```sh
"$HERMES_PYTHON" scripts/phone_control_integration.py
"$HERMES_PYTHON" scripts/replay_security_integration.py
```

Run these scripts directly in fresh processes; do not import bootstrap or discover plugins first. The phone harness force-sets a disposable `HERMES_HOME` before application imports, disables project-plugin discovery and refuses a pre-bootstrapped process. It uses temporary loopback servers, real SQLite/P-256/auth/biometric guards and synthetic devices. Its 15 checks substitute FCM credentials/network and do not demonstrate Android execution, real push, production deployment or full compatibility. See [QA](docs/android-qa.md).

## Build the full app

Needs JDK 21, Android compile SDK 37 / target SDK 36, NDK 29.0.14206865 and CMake 3.22.1. Initial native configuration downloads pinned archives; runtime wake detection is offline. Release APKs include ARM64, ARMv7, x86 and x86-64 native libraries.

For a local unsigned-by-the-publisher debug build:

```sh
cd android
# Set JAVA_HOME to your JDK 21 installation and configure your Android SDK.
./gradlew :app:assembleFullDebug
./gradlew --stop
```

For your own release build, copy [android/keystore.properties.example](android/keystore.properties.example) to `android/keystore.properties` and fill it with your own signing inputs. Keep the file and keystore private; never commit them. An alternate properties path is supported via `-PhermesSigningProperties=/path/to/signing.properties`. Do not put passwords on the command line. Release tasks fail without complete signing inputs and never silently use a debug key.

```sh
cd android
./gradlew :app:assembleFullRelease
./gradlew --stop
# app/build/outputs/apk/full/release/app-full-release.apk
```

Use explicit flavored tasks. `assembleRelease`/`assembleDebug` aggregate both flavors; they are not full-only. A self-built APK has your signer, not the approved publisher signer. See [distribution/build checks](docs/distribution-flavors.md) for maintainer validation; Play tasks are optional development work, not prerequisites for GitHub installation.

## Layout

| Path | What |
|---|---|
| `android/` | Compose client and full/Play source sets |
| `server/mobile/adapter.py` | Platform adapter, signed HTTP/SSE API and CLI |
| `server/mobile/sig.py`, `store.py` | Request/approval signatures and persistent replay claims |
| `server/mobile/control.py`, `control2.py` | Hermes control surfaces |
| `server/mobile/selfcheck.py` | Operator self-check |
| `scripts/phone_control_integration.py` | Isolated phone transport checks |
| `scripts/replay_security_integration.py` | Isolated replay security checks |
