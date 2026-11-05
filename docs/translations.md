# Translations

The app's text lives in Android string resources. English is the base and the fallback. A language listed in
`android/app/src/main/res/xml/locales_config.xml` shows in the in-app picker (This phone > Look > App language) and,
on Android 13+, in system Settings. Until its translation lands it falls back to English.

## Where strings live

| File | Holds |
|---|---|
| `android/app/src/main/res/values/strings.xml` | Everything both editions show |
| `android/app/src/full/res/values/strings.xml` | Full (sideload) edition only: phone control, installer, battery settings |
| `android/app/src/play/res/values/strings.xml` | Play edition only |

A translation mirrors each file that exists: `main/res/values-de/strings.xml`, `full/res/values-de/strings.xml`,
`play/res/values-de/strings.xml`. Each must contain every key of its base file, except keys marked
`translatable="false"`, and nothing else.

Code reads strings with `stringResource(R.string.key)` in Compose and `str(R.string.key)` elsewhere
(`L10n.kt`). Dates, times, durations, money, percentages and sizes are formatted by locale in `Format.kt`
(`Fmt`), so no date or number pattern appears in a string.

## Right to left

Text direction rules live in `Bidi.kt`:

- UI copy (translated strings, labels, lines the app composes) follows the layout direction. That is the default of
  `sans()`, `mono()` and `serif()`, so a Hebrew or Arabic line that starts with a Latin name still reads right to left.
- Content from the user, the agent or the gateway (messages, previews, chat and session titles, names, file names,
  input fields) uses `.content()`: its first strong character sets its direction. One line of content in a row uses
  `.contentLine()`, which also aligns it to the row's start.
- Code, commands, paths, terminal output and cron syntax use `.ltr()`, and their scroll containers sit in `LtrLayout`.
- Content passed into a translated template or joined into a UI line goes through `bidiIsolate()` (or `bidiJoin()`),
  which wraps it in U+2068/U+2069. Product names passed as arguments are isolated too.
- Hebrew: join a prefix letter to a Latin word or a placeholder with the maqaf (U+05BE, "ל־Hermes", "ב־%1$s"), not a hyphen. A hyphen there makes the line breaker wrap one-line labels; `StringResourcesTest` checks it.
- Titles in `serif()` pass their text through `trackFor()`, which drops the tight tracking for Arabic: Android draws
  Arabic without letter spacing, and with it the line breaker would move the last letter to a new line.

## Keys

- snake_case, screen first: `chat_jump_to_latest`, `wake_alarm_add`, `hub_install_failed`.
- Content descriptions (read by screen readers) end in `_desc`.
- `common_*` holds generic actions and states (Cancel, Save, Loading…). Reuse them; a screen-specific copy of the
  same word gets its own key only when the meaning differs.
- Keys are stable. Changing the English text of a key keeps the key unless the meaning changes; a new meaning gets a
  new key so stale translations do not survive.
- Comments above a string explain each placeholder and any ambiguous word (verb or noun, button or title).

## Glossary

Keep these exactly as written, in every language:

- Product and agent names. They arrive as arguments (`%1$s`), never inside the text: the app's name (Hermes,
  Herms on Play), the user's agent name, "Hermes" (the agent software on the user's machine), "Hermes Agent",
  "Hermes Desktop", the edition name and the publisher.
- "gateway", "Bot" and "MCP". `StringResourcesTest` fails when a translation drops one of them.
- Android, Google, Cloudflare, Telegram, Discord, WhatsApp, GitHub and other third-party names.
- Email addresses, URLs, file names and commands (`hermes mobile pair`); most of these arrive as arguments.
- Widget and accessibility-service labels referenced from the manifest cannot take arguments, so their English
  text names the app; translations keep that name (`StringResourcesTest` checks it).
- The launcher label `app_name` is the product name itself, marked `translatable="false"` (Play overrides it with
  Herms). Translations leave it out.

Tone: short, plain and direct, like the English. Address the user the way the language's Android system UI does.
Sentence case unless the language capitalizes otherwise. No exclamation marks the English does not have. Keep
"…" on in-progress states. Space is tight (chips, 48dp buttons, one-line rows): prefer the shorter wording.

## Placeholders and plurals

- `%1$s`, `%2$d` must all stay, with the same numbers and types. The order may change: `%2$d von %1$s`.
- `%%` is a literal percent sign in strings that take arguments.
- `<plurals>` need every quantity the language uses (Arabic: zero, one, two, few, many, other; Irish: one, two,
  few, many, other; Japanese, Korean, Chinese: other). A form may drop the count (`one` -> "Eine Datei"); `other`
  keeps it.
- Escape `'` as `\'` and `"` as `\"`; write `&amp;` and `&lt;` for & and <; `\n` for a line break.

## Resource folders

| Language | Tag in locales_config.xml | Folder |
|---|---|---|
| English (base) | `en` | `values` |
| Afrikaans | `af` | `values-af` |
| Arabic | `ar` | `values-ar` |
| German | `de` | `values-de` |
| Spanish | `es` | `values-es` |
| French | `fr` | `values-fr` |
| Irish | `ga` | `values-ga` |
| Hebrew | `he` | `values-iw` |
| Hungarian | `hu` | `values-hu` |
| Italian | `it` | `values-it` |
| Japanese | `ja` | `values-ja` |
| Korean | `ko` | `values-ko` |
| Portuguese | `pt` | `values-pt` |
| Russian | `ru` | `values-ru` |
| Turkish | `tr` | `values-tr` |
| Ukrainian | `uk` | `values-uk` |
| Chinese (Simplified) | `zh-Hans` | `values-zh-rCN` |
| Chinese (Traditional) | `zh-Hant` | `values-zh-rTW` |

Android resolves Hebrew resources under the legacy code `iw`: `values-he` is never read. Chinese is offered by
script; a Simplified locale (`zh-Hans`, `zh-CN`, `zh-SG`) reads `values-zh-rCN` and a Traditional one (`zh-Hant`,
`zh-TW`, `zh-HK`) reads `values-zh-rTW`, the same folders AndroidX uses.

## Adding a translation

1. Create the folder from the table in each of `main`, `full` and `play` and translate every key of the base file.
2. Remove the language from `PENDING` in `android/app/src/test/java/dev/avifenesh/hermes/LocalesConfigTest.kt`.
3. Run, from `android/`:
   `./gradlew :app:testFullDebugUnitTest :app:testPlayDebugUnitTest :app:lintFullRelease :app:lintPlayRelease`.
   The unit tests check keys, placeholders and the glossary per locale and that the folders match
   `locales_config.xml`; lint fails on missing translations and plural quantities.
4. Switch to the language in This phone > Look > App language and look at the main screens. Arabic and Hebrew
   lay out right to left.

## Adding a language that is not listed

Add `<locale android:name="TAG" />` to `locales_config.xml`, add its folder to `FOLDER` in `LocalesConfigTest`
(check Android's qualifier for the code), and follow the steps above. Its name in the picker comes from the
platform, in the language itself.
