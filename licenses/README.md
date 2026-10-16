# Bundled legal material

Canonical application license: `../LICENSE`. Canonical notice: `../NOTICE`.
Full texts, provenance and `inventory.json` live here. Exact UTF-8 copies ship
in `android/app/src/main/assets/legal/`. The inventory drives **Me > Licenses**
and the first-run **Licenses** button, without a server, browser or account.

## Current offline wake implementation

- **Weights and manifest:** ESPHome microWakeWord v2 Hey Jarvis at
  `40ff33f57f8fc6ad71a75ef085abbf742495b225`. The publisher's original README
  and NOTICE explicitly cover `.tflite` weights and `.json` manifests under
  Apache-2.0, without additional binary terms. This is not an inference from
  the application or engine code license.
- **Engine:** Home Assistant Android at
  `110809fb146429a613cabeaa0eb2165be57939b9`, adapted under Apache-2.0.
- **Native source pins:** TFLM/microfrontend, KissFFT, FlatBuffers, gemmlowp
  and ruy. See `NATIVE-DEPENDENCIES.txt` for commits, archive SHA-256 values,
  attribution and exact license URLs. All five archive hashes were verified.
- **KissFFT:** both the original COPYRIGHT-bearing `COPYING` and the full
  `LICENSES/BSD-3-Clause` are packaged. COPYING alone only refers to the terms.
- **NDK:** r29 / `29.0.14206865`, C++ shared runtime. The unabridged upstream
  `NOTICE` and `NOTICE.toolchain` are packaged. They include LLVM exception
  and BSD/legacy runtime terms. They are omnibus texts, not a claim that every
  listed tool/platform component is in the APK. The original archive members
  were retrieved from Google's official ZIP using HTTP Range and ZIP CRC
  verification; `inventory.json` records their byte hashes and member paths.

There are no openWakeWord ONNX assets or ONNX Runtime entries in the shipping
inventory. The checker rejects their reintroduction. MIT application terms,
original Geist/Instrument OFL texts and Google ML Kit separate terms remain.

## Validate without building

Python 3.9+ standard library; no credentials, gateway or Android build needed:

```sh
python3 licenses/check_legal.py
python3 licenses/test_check_legal.py
# Re-download public primary texts, models and native license files:
python3 licenses/check_legal.py --verify-upstream
# Compare notices against the exact NDK used by the parent build:
python3 licenses/check_legal.py --ndk "$ANDROID_HOME/ndk/29.0.14206865"
# After the parent builds, inspect the exact delivered artifact:
python3 licenses/check_legal.py --apk PATH_TO_FINAL_APK
```

The checker is read-only. It checks canonical/asset bytes, hashes and lengths,
required full notices, model/manifest exact hashes, source-set absence of old
ONNX/ORT assets, CMake dependency pins, NDK version, scanner dependency and
the offline-reader source contract. The SHA-256 of the inventory itself is
printed and its canonical/asset/APK bytes compared; it cannot hash itself.

`--verify-upstream` downloads raw public documents and models. NDK ZIP members
are listed separately, not silently treated as plaintext URLs or re-downloaded
as a large SDK. Use `--ndk` to compare those members against the build SDK.
The original Geist notice remains verbatim: current upstream differs only by
the `.git` suffix in the author's URL; both hashes are recorded.

`--apk` checks legal assets and exact replacement model bytes, native engine
presence, font bytes (including AAPT2-shortened resource paths), and reader
markers in DEX. It rejects old ONNX/ORT ZIP entries, original old-model hashes
even if renamed, ORT libraries, and `ai.onnxruntime` Java class markers. The
reported SHA-256 identifies that exact APK. The native-library check establishes
presence only, not architecture, ELF alignment, loading or runtime correctness.

The regression suite uses explicitly synthetic ZIP/DEX/ELF marker fixtures.
They are not Android APK execution evidence. The suite requires the completed
implementation's source assets/CMake/Gradle contracts. During parallel engine
replacement, source tests intentionally fail until the owner removes the old
assets and supplies the pinned replacements; do not weaken that gate or modify
the implementation owner's files just to make tests pass.

## Updating intentionally

1. Fetch version-specific primary licenses/notices, preserving all original
   bytes and copyrights. Establish model-weight licensing independently.
2. Update pins, attribution, changes and unresolved provenance in the canonical
   notice and inventories. Native source-list changes require another review.
3. Copy documents into `assets/legal/`, update reviewed hashes and sizes in both
   inventories, and update the checker's required documents/pinned constants.
   The checker never automatically blesses changed bytes.
4. Run source, negative regression, upstream/NDK and final-artifact checks.
   The parent still verifies real offline wake behavior and the legal reader
   on a device. Source and ZIP checks do not prove signing, UI, dependency
   resolution, runtime behavior or legal compliance.

## Remaining licensing questions

The publisher's explicit Apache-2.0 weight grant does **not** establish a fully
audited permissive training-data chain. Upstream's clarification left training
provenance unresolved. The author-linked feature dataset is marked
CC-BY-NC-4.0, but its exact use for this checkpoint and any effect on resulting
weight rights have not been established. Do not claim either that all inputs
are permissive or that this makes the replacement weights CC-NC. This is distinct
from the old openWakeWord classifier's removed CC BY-NC-SA distribution terms.

No trademark endorsement or third-party trademark rights are granted. Google
ML Kit/Google services retain separate terms. This selected inventory is not
an exhaustive Maven/native final-link SBOM or certification for every intended
commercial use. Review remaining rights and resolved dependency applicability
before making broader clearance claims.
