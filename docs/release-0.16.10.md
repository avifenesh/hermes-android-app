# 0.16.10 focused verification

Full sideload package `dev.avifenesh.hermes`, versionCode 42.
Version 0.16.9 remains reserved for the separate Play-preparation branch.

## Changes

- A labelled Fast on/off control sits beside the model picker in existing and new chats.
  It changes this chat only. The model sheet still offers chat/default scope.
- Fast availability uses Hermes' effective runtime route and the same eligibility
  helper as `/fast`, even when the provider catalog omits capability metadata.
- Fast changes require the slash handler's success receipt and state readback.
  Default changes also verify persisted configuration.
- Replies in delivered cron-report chats carry the full report on the new inbound
  message. Explicit quotes are resolved from the same chat's stored messages.
  Mirrored profile reports use the same gateway reply envelope. Old cached
  conversation history and system prompts are not rewritten.

## Executed checks

- Full signed release build and release lint passed; zero lint errors.
- Focused ad-hoc integration checks: 25 model controls, 11 cron reply context,
  25 replay security and 15 phone transport checks passed.
- Signed HTTP against a disposable API exercised the real `/fast` slash handler,
  on/off readback, chat isolation and an unquoted cron report's reply envelope.
- Signed APK on an emulator: chat toggle, model-sheet toggle, persisted default
  change, app restart, Day/Night, and 320dp width with 200% text. The compact
  picker scrolls to a readable, labelled control. Accessibility dumps confirm
  its checked state changes.
- APK distribution/component/signature guard and 16 KiB ZIP alignment passed.

APK SHA-256:

```text
a40a54b5ad540d6c29c7496ffba5d9fef294b594f9c485fc7720500067dc657f
```

Signing certificate SHA-256:

```text
cdd83d31e6ea91a853a973ed8647cb3a6f5408d1301b2e1e6d06a04bd33a27ee
```

These are focused checks, not the full project suite, physical-phone QA,
provider billing validation or real FCM delivery. The HTTP fixture uses a
synthetic runtime and makes no model-provider requests. Plugin installation
and gateway activation are separate from building the APK: install the complete
matching `server/mobile/` and restart through the supported gateway control.
