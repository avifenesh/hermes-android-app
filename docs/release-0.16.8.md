# 0.16.8 Hub discovery and filters

Hub opens on **Discover**, showing only catalog items that are not installed.
**Installed** and **All** are separate views for skills, plugins and MCP servers.
Skills use a vertical, alphabetized list instead of installed rows above horizontal
catalog shelves. Source filters affect browsing without a query as well as search.
Category filters combine with the selected view and source.

Local skill detection includes bundled, copied, imported and disabled skills, not
just the Hub lockfile. Unmanaged local skills do not acquire Hub update/uninstall
permissions. Their preview offers Extensions rather than a duplicate install.
Search clears old results when its query, profile or source changes. Returning
from a detail screen refreshes the catalog.

## Artifact

| Identity | Value |
|---|---|
| Package | `dev.avifenesh.hermes` |
| Version | `0.16.8` / versionCode `40` |
| APK | `Hermes-0.16.8.apk` |
| Bytes | `12768395` |
| APK SHA-256 | `0eba259b916979fa7da1ba80077f19611ca7693916d30de95e17166545bd0f71` |
| Signer SHA-256 | `cdd83d31e6ea91a853a973ed8647cb3a6f5408d1301b2e1e6d06a04bd33a27ee` |

## Executed focused verification

- Nine Hub JVM checks passed. The signed full release build passed;
  full release lint reported zero errors.
- Thirteen isolated aiohttp integration checks passed for local inventory,
  browse/search/preview consistency, profile isolation, concurrent profile reads,
  lock-only mutation permissions and avoiding community basename false matches.
  Regression checks failed on the previous backend. External catalog/search
  providers were stubbed. Shadowed copies have stable, unique inventory identities.
- Seventeen scripted UI checks passed on the exact signed, minified full release
  installed on an emulator against a disposable paired fixture. They covered
  discovery hiding installed entries; source/category filters without a query;
  local installed inventory; All; search/source composition; index results whose
  underlying source differs; plugin filtering; MCP views; and preventing duplicate
  installation or Hub mutation of unmanaged local skills. Same-name local copies
  and All-view search for local-only skills were exercised. The catalog was synthetic;
  pairing, signed requests, routes and app navigation were real.
- Screenshots were reviewed in Day and Night themes. The artifact passed package,
  signer, release-component/distribution boundaries, legal inventory and 16 KiB
  ZIP alignment checks. All four existing native ABIs remain present.
- Twenty-five adjacent replay-security integration checks passed.
- A temporary `hermes-verify-` script ran the focused build, Hub JVM, lint and
  backend checks, then removed itself. This is ad-hoc focused verification, not
  a claim that the full project suite is green.

## Gateway update and limits

Install the complete matching `server/mobile/` directory and activate it with the
supported gateway restart control. Existing pairing and replay storage are
preserved. The updated backend is required to recognize skills installed outside
Hub. Copying source does not establish that a running gateway has activated it.

No physical-phone, live external registry availability, biometric hardware,
whole-app, battery or Play qualification follows from these checks. Catalogs
without a preloaded browse list still require a search query. Older legal/model
provenance limitations remain unchanged.
