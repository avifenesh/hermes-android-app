# 0.16.13 Momentum on the radar page

With [momentum](https://github.com/agent-sh/momentum) installed on the machine, the radar
page opens with its report, above the scout's findings: **Needs you, Problems, Running,
Shipped, Decided**. A section opens while it shows something unread and shows only its
count once read. A row is read once it was at least half on screen for one continuous
second while the page was in front; leaving the page records it. A later report reopens
only the sections that gained an item, and marks the new item with a dot.

Each section lists at most five items, then "and N more". An item shows its title, one
line of detail (shipped items: the measured effect) and its links, which open in the
browser. Tapping an item shows the full text. Everything is plain text.

Nothing notifies. The Radar row in Me carries the needs-you count: coral while a
question is unread, quiet once read. The read state is kept per paired machine.

The plugin's `GET /v1/radar/momentum` runs `momentum report --json` with a 5 s timeout,
reuses one run for 15 s, keeps the last good copy across restarts, and marks it stale
with the reason when a run fails. It never writes to momentum. Reading the scout's page
no longer creates the scout's store. Also in this release: chats open at the last-read
position (0.16.12, never published on its own).

## Artifact

| Identity | Value |
|---|---|
| Package | `dev.avifenesh.hermes` |
| Version | `0.16.13` / versionCode `45` |
| APK | `Hermes-0.16.13.apk` |
| Bytes | `12801099` |
| APK SHA-256 | `3d4397f48318dd095cc9ca6e152fbf2ad906999d627eb230d4ded42287c8703e` |
| Signer SHA-256 | `cdd83d31e6ea91a853a973ed8647cb3a6f5408d1301b2e1e6d06a04bd33a27ee` |

## Executed focused verification

- `scripts/momentum_report_integration.py`: 10 checks through the real app router and
  request auth with a stand-in `momentum`: plain contract data, including control and
  invisible format characters; a failure keeping the last good copy marked stale; a hung
  report cut off with its whole process group; a restart during an outage serving the
  saved copy; one run for a concurrent burst; a clock step back; a wrong report version;
  no momentum and no scout store; an unsigned request refused. They fail on the previous
  code.
- Adjacent integration checks passed: phone transport 15, replay security 25, fleet
  terminal 8, self-check imports 6, cron reply context 11, skills catalog 1, hub
  catalog 13, model control 25.
- Signed full release build; release lint reported zero errors. Distribution guard,
  legal inventory and 16 KiB ZIP alignment passed.
- The signed APK on an emulator at 1080x2340 / 450 dpi, paired to an isolated test
  gateway (`gateway run` on a disposable home) serving a synthetic report, in Day and
  Night: first look, sections collapsing to their counts after reading, new items
  reopening only their sections, a stale copy with its reason, "No report yet", a link
  opening in Chrome, and the Me badge hot then quiet. Sixteen scripted read-state checks
  passed, among them: a question that arrives while its section is off screen stays
  unread and alone carries the dot; rotations keep the page and what was read; a
  half-second visit reads nothing; an item past the fifth does not reopen its section;
  a problem resolved and added again under its id is new; reading after a round trip to
  the browser counts; rows under the compose sheet are not read; a machine without a
  scout gets a momentum page. Earlier builds of the change fail the checks that their
  fixes target. No notification was posted.
- Four clean-context review rounds; the last one passed.

These are focused checks, not the full project suite or physical-phone QA. Install the
complete matching `server/mobile/` and restart through the supported gateway control;
the app alone cannot add the route.
