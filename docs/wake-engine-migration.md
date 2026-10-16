# microWakeWord Android migration

0.16.2 integration record. Full and Play builds and focused device tests have now executed; [Android QA](android-qa.md) records their exact scope. The release owner still owns final signing/alignment/artifact qualification. No public/Play release or physical-phone performance/accuracy certification is implied.

## Source and model pins

Native source: `home-assistant/android@110809fb146429a613cabeaa0eb2165be57939b9`, `microwakeword/src/main/cpp/`. The small CMake target is wired directly into the common app source set; both full and play build it. No HA application dependencies or Google inference service.

Model repository: `esphome/micro-wake-word-models@40ff33f57f8fc6ad71a75ef085abbf742495b225`.

- `android/app/src/main/assets/microwakeword/hey_jarvis.tflite`: SHA-256 `21a7976add39ee24ec96c63d96b7aaa18e24d1d9824b963e451da8feb4b78b77`.
- `android/app/src/main/assets/microwakeword/hey_jarvis.json`: SHA-256 `b153867d818675d8abcc9dace474afe7f83551ae0d5a9b1d71a98681320185af`.
- Preference/model ID: `mww_hey_jarvis_v2`.
- Primary pinned README and NOTICE explicitly include the weights and JSON under Apache-2.0. The training-data provenance chain is NOT fully audited. No claim that every training input was permissive, nor that an author-linked NC dataset changes this checkpoint’s explicit weight grant. See [per-file provenance](../licenses/MODEL-PROVENANCE.txt) and [packaged legal checks](../licenses/README.md).

CMake archive pins, unchanged from HA:

| Dependency | GitHub repository / commit | Archive SHA-256 |
|---|---|---|
| KissFFT | `mborgerding/kissfft@7bce4153c6bc8aba2db0e889e576f9d00505cbe1` | `7ad1124648a46977b16ddde03bf243bcd52fc452516c57016584ab4b4f2baadc` |
| TFLite Micro | `tensorflow/tflite-micro@2747abd5c82a95fb1624106a946fc671c31f16e8` | `6ac0c8ef35f267cf3baf05dc094d4fd158864d4cc830d490b752994e58add8c8` |
| FlatBuffers | `google/flatbuffers@0100f6a5779831fa7a651e4b67ef389a8752bd9b` | `85db3520acc4010b21984e2fb5ead3ec0d2c48df8009b614cb73562a82846554` |
| gemmlowp | `google/gemmlowp@fda83bdc38b118cc6b56753bd540caa49e570745` | `0f990732a0d541be514dfc5c1c45969626e9f349faa851baec01b9a9dcb4ae4b` |
| ruy | `google/ruy@54774a7a2cf85963777289193629d4bd42de4a59` | `91993e7eb2aa56e62e9f4abc1158b2b46333574aa81f1a7431b06df80a42b7fa` |

Archive URLs use `https://github.com/<repository>/archive/<commit>.tar.gz`. The canonical [legal inventory](../licenses/README.md) owns notices, license files and packaged legal assets; this guide does not replace it. Original upstream source files have no file-level copyright headers; existing comments are retained. Adapted files identify the pin and changes. Unmodified reference files live only in androidTest.

## Runtime contracts

- Offline bundled model + algorithmic native microfrontend + TFLM. No ONNX sessions or embedding backbone.
- PCM16 mono 16 kHz. AudioRecord still batches 1280 samples (80 ms). Wrapper retains arbitrary partial reads and feeds native exactly 160 samples (10 ms) at a time. This avoids the reference's early-return discarding subsequent frames in a large input chunk.
- Model default cutoff 0.97, window 5, feature step 10 ms. Native quantizes cutoff with `uint8(cutoff * 255)` and compares window sum with strict `>`; this is not a dequantized output score or an OWW probability.
- Upstream warm-up of 100 below-cutoff inference slices is retained. Model stride is 3. Cooldown is still two seconds but uses elapsed realtime rather than chunk count.
- Reset destroys/recreates frontend, interpreter and resource-variable arenas. Do not use upstream partial `reset()` across microphone streams. Mic restart, identity epoch change and cutoff change reset state. Identity changes also reopen AudioRecord to discard already queued old-identity PCM. Audio read across an identity change is discarded.
- Migration preserves the wake opt-in, replaces legacy model ID, removes `wakethr`, and initializes `mww_probability_cutoff` to 0.97 once. Subsequent native cutoff settings persist. Manual adjustment uses hundredths from 0.01 through 0.99; zero prevents the reference warm-up counter progressing and one makes its strict detection inequality unreachable. These bounds exclude nonfunctional endpoints, not measured quality presets. UI labels it as a window cutoff and retains manual adjustment. The live bar uses the actual native five-output mean in 0..255 units, explicitly not confidence or a Boolean-derived score.
- Native reference uses a 64 KiB tensor arena and 4 KiB variable arena, in addition to a copied model, frontend and interpreter allocations. Manifest's 22,860-byte arena hint is NOT a total memory footprint. RSS, power and CPU remain unmeasured.
- Existing permission checks, microphone FGS, notification Stop, VoiceLaunch/VoiceSession and origin-bound handoff are preserved. No flavor capabilities changed.

## Parent build commands

The integrated builds use NDK 29.0.14206865 and CMake 3.22.1. For a fresh environment, install the pinned tools before building:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME="$HOME/Android/Sdk"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'ndk;29.0.14206865' 'cmake;3.22.1'
cd /path/to/hermes-android/android
export CMAKE_BUILD_PARALLEL_LEVEL=2
./gradlew --no-daemon --max-workers=2 -Porg.gradle.java.installations.paths="$JAVA_HOME" :app:testFullDebugUnitTest :app:testPlayDebugUnitTest :app:assembleFullDebug :app:assemblePlayDebug
```

First native configuration downloads the pinned archives; Gradle `--offline` does not make CMake's first FetchContent download offline. Runtime is fully offline. SDK/tool acquisition and initial archive cache need network. Do not enable HWASan for ordinary API 31 devices; it is opt-in `HERMES_WAKE_HWASAN`.

### Native parity / reset / migration tests

Use an isolated test emulator/install, not the owner's paired phone. Tests use AndroidX runner 1.7.0 (test-only), JUnit 4.13.2, and optionally build the unmodified pinned HA engine into `libmwwreference.so`. That library is guarded by both the explicit property and CMake Debug; it cannot enter a release build.

```sh
./gradlew --no-daemon --max-workers=2 -Porg.gradle.java.installations.paths="$JAVA_HOME" -PhermesWakeReferenceTests=true :app:connectedFullDebugAndroidTest :app:connectedPlayDebugAndroidTest
```

`WakeEngineTest` compares native reference detections against integrated 80 ms delivery, checks 1/159/17/1280/4096-sample partitions, compares every inference output/state after dirty-stream recreation against a fresh engine, checks silence, malformed model rejection, idempotent close and preference migration. Deterministic silence/noise/clipping fixtures are generated locally in memory. The 0.1 test cutoff is only an adversarial test setting, not a shipping sensitivity preset. The optional spoken fixture test is explicitly skipped if absent, not counted as recognition-quality success.

Consented local PCM16 mono 16 kHz WAV replay through both engines (after installing reference-enabled debug and androidTest APKs):

```sh
# Copy a consented fixture into the isolated full app's private files directory.
adb shell run-as dev.avifenesh.hermes mkdir -p files
adb shell "run-as dev.avifenesh.hermes sh -c 'cat > files/wake-fixture.wav'" < /absolute/path/consented-fixture.wav
adb shell am instrument -w -e class dev.avifenesh.hermes.WakeEngineTest#testConfiguredWavFixture -e wakeFixture /data/user/0/dev.avifenesh.hermes/files/wake-fixture.wav -e wakeMinHits 1 dev.avifenesh.hermes.test/androidx.test.runner.AndroidJUnitRunner
```

For Play use `dev.avifenesh.hermes.play` and test package `dev.avifenesh.hermes.play.test`. `wakeMinHits 0` means assert no detections for a negative fixture; positive values assert at least that many events. Prepend silence to allow the reference warm-up before a spoken positive. Detection sample positions are emitted to `HermesWakeFixture` logcat. This is engine parity, not microphone/FGS/voice-handoff coverage.

The existing debug `MainActivity --es wwtest <app-readable-wav-path>` path now logs `detectionSamples` through the replacement engine and catches malformed input; it never treats Boolean hits as scores. Release ignores this extra. The debug service `testwav` injection remains available only to the existing nonexported service and recreates state at fixture/microphone boundaries. It is an 80 ms live-service probe; use instrumentation for exact partial-tail replay.

### Signed release and artifact gates

```sh
./gradlew --no-daemon --max-workers=2 -Porg.gradle.java.installations.paths="$JAVA_HOME" :app:assembleFullRelease :app:assemblePlayRelease :app:lintFullRelease :app:lintPlayRelease
```

Release tasks remain fail-closed for publisher signing. Native release is universal arm64-v8a/armeabi-v7a/x86/x86_64; minSdk31/target36 unchanged. NDK29 flexible-page-size and explicit 16 KiB linker alignment are build intent, not final APK evidence.

## Executed source-level verification

- `python3 scripts/check_wake_engine.py`: passed model/manifest hashes, five archive pins, common-flavor native wiring and freshness contracts.
- Downloaded all five exact native archives and verified their SHA-256 values. Verified the six unmodified test reference files against git blob IDs in the pinned HA tree.
- Clang `-std=c++23 -fsyntax-only` against those real dependency headers: all three production C++ translation units passed. JNI clang-tidy completed with no reported warnings after explicit documented JNI-ABI exceptions. This is not NDK linking.
- Focused cached Kotlin 2.4.20 compiler check: seven new engine/preference/fixture/test files compiled against the Android SDK/JUnit/real test-monitor classes, with no Gradle invocation. `javap -s` confirmed production and reference JNI descriptors match their registration tables.
- Ran `WakePcmTest` on the JVM: 3 tests passed (PCM/odd-chunk padding, malformed format/truncation, RIFF length).
- Kotlin PSI parse: twelve touched/new Kotlin/Gradle files passed. CMake token/parser check passed without configuring/building.
- Existing `scripts/check_voice_ingress.py` and `scripts/check_distribution.py` source guards passed; `git diff --check` passed.

The original source-check helper scripts/logs were temporary worker scratch artifacts, not durable public release receipts. Preserve the final integration receipts alongside the exact release artifacts. The initial Kotlin 2.4 PSI environment failed to initialize; the established 1.9.22 PSI parser on JDK21 succeeded. The actual focused type-check uses Kotlin 2.4.20. That original implementation worker did not execute native inference, build/install an APK, commit or deploy. Subsequent integration-owner execution is recorded below; the historical source-only checks are not the current integration status.

## Executed integration verification

- Both flavors built; full/Play JVM suites passed 31/34 tests respectively with zero failures, and both release lint runs reported zero errors.
- Checked APKs passed legal/distribution guards: 19 legal assets, pinned model/manifest, and absence of all old ONNX/ORT assets/runtime.
- Both flavors executed four native parity/reset/silence/migration cases on an API 36 emulator with actual 16 KiB pages. The optional WAV case initially skipped without a fixture was then explicitly run with a synthetic TTS “Hey Jarvis” positive in each flavor; positive parity passed.
- A fullDebug live-service synthetic-WAV probe reached native detection, private voice handoff, TalkService and on-device recognizer startup. `NoSpeech` from the empty emulator microphone was expected; the injected WAV does not demonstrate physical microphone pickup or successful spoken recognition. This handoff check was full-only.
- Independent source review passed at the reviewed integration point. These are integration-owner results; this documentation update performed no build/device run.

See [Android QA](android-qa.md) for limitations and release context.

## Remaining acceptance gates

Final post-change artifact/signer/notices revalidation, ELF LOAD and APK ZIP alignment for packaged native libraries, bundle-generated APK checks and API31 replacement-engine qualification remain release-owner work. The API36 true-16-KiB debug execution above is real but not complete ABI/platform coverage. Real spoken multi-speaker positives, near-misses, long negatives, latency, acoustic microphone handoff, screen-off/OEM behavior and sustained CPU/RSS/battery need real-device evidence. Synthetic fixture parity does not establish recognition quality. Training provenance remains unaudited despite the explicit Apache weight grant. No GPU or training was used.
