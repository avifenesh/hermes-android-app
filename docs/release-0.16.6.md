# 0.16.6 full APK verification

This client-only update fixes Markdown links inside bold and italic text, including the bold download link that rendered as plain text in chat. Formatted link labels keep their styles and clickable annotation. Code spans remain literal. Bare URLs exclude trailing sentence punctuation and preserve balanced parentheses. This is a focused inline Markdown subset, not full CommonMark or HTML support.

| Identity | Value |
|---|---|
| Package | `dev.avifenesh.hermes` |
| Version | `0.16.6` / versionCode `38` |
| APK file | `Hermes-0.16.6.apk` |
| APK SHA-256 | `975b448d7ab3aee3a431cb3b15ced8daa897887b66aad327027837cd8a08c008` |
| Signer SHA-256 | `cdd83d31e6ea91a853a973ed8647cb3a6f5408d1301b2e1e6d06a04bd33a27ee` |

## Executed checks

- The initial 15-test inline regression suite ran against the previous renderer: 14 tests failed. All 23 final inline tests passed, including the exact bold-wrapped APK download link, ordinary nested emphasis and triple emphasis nested inside bold or italic. Two added triple-emphasis cases failed before the final matching fix and passed after it.
- Delimiter indexing passed deterministic linear-work assertions on malformed bracket/backtick inputs of 40,000, 80,000 and 160,000 characters. It avoids repeated suffix scans without truncating output.
- All 59 full-debug JVM tests passed. Full-release lint reported zero errors, 65 warnings and 27 hints. The full release build succeeded.
- Real taps on bold, italic, formatted-label, nested-emphasis and table links in a debug emulator fixture reached the expected Compose URI handler in both themes. The handler recorded destinations without opening a browser or making a network request. Screenshots covered headings, nested emphasis, lists, quotes, tables and code.
- The bold download link remained readable and tappable at 320dp width with 200% text.
- The signed full release passed distribution/signature, legal inventory and 16 KiB ZIP alignment guards. Debug Markdown fixtures are absent from release DEX.

## Limits

These are focused parser, emulator and artifact checks, not a complete Markdown conformance suite or physical-phone browser test. Only HTTP, HTTPS and mailto destinations are eligible for link annotations; app, intent, file and content destinations remain inert. Existing app capabilities and server protocol are unchanged. No live gateway deployment, Play submission or reviewer-backend deployment is included.
