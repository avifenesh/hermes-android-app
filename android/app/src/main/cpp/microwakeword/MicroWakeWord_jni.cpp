// Adapted from Home Assistant Android (Apache-2.0),
// 110809fb146429a613cabeaa0eb2165be57939b9/microwakeword/src/main/cpp/MicroWakeWord_jni.cpp.
// Hermes: package binding, fixed single-step PCM contract, exception containment,
// diagnostic snapshot, and no incomplete native reset API.
// JNI's public header owns platform typedefs (host jni_md.h is not an Android API).
// Pointer/integer handle and function-pointer casts are required by the JNI ABI.
// NOLINTBEGIN(misc-include-cleaner,cppcoreguidelines-pro-type-reinterpret-cast,performance-no-int-to-ptr)
#include <jni.h>
#include <cstddef>
#include <cstdint>
#include <array>
#include <cmath>
#include <exception>
#include <memory>

#include "MicroWakeWordEngine.h"

namespace {
constexpr jint kSampleRate = 16000;
constexpr jint kFeatureStepMs = 10;
constexpr jint kWindow = 5;
constexpr jsize kStepSamples = kSampleRate * kFeatureStepMs / 1000;
void throwJava(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) {
        const auto type = env->FindClass("java/lang/IllegalStateException");
        if (type != nullptr) env->ThrowNew(type, message);
    }
}

auto nativeCreate(JNIEnv* env, jclass, jobject buffer, jint rate, jint step,
                  jfloat cutoff, jint window) -> jlong {
    try {
        if (buffer == nullptr || rate != kSampleRate || step != kFeatureStepMs || window != kWindow ||
            !std::isfinite(cutoff) || cutoff < 0.0F || cutoff > 1.0F) {
            throwJava(env, "Invalid wake engine configuration");
            return 0;
        }
        const auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
        const auto size = env->GetDirectBufferCapacity(buffer);
        if (data == nullptr || size <= 0) {
            throwJava(env, "Expected a direct model buffer");
            return 0;
        }
        auto engine = std::make_unique<MicroWakeWordEngine>(data, static_cast<size_t>(size),
                                                          rate, step, cutoff, window);
        if (!engine->isInitialized()) {
            throwJava(env, "Wake model initialization failed");
            return 0;
        }
        // Ownership crosses JNI; nativeDestroy adopts it exactly once.
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception& e) {
        throwJava(env, e.what());
        return 0;
    } catch (...) {
        throwJava(env, "Native wake initialization failed");
        return 0;
    }
}

auto nativeProcessAudio(JNIEnv* env, jclass, jlong handle, jshortArray input) -> jboolean {
    try {
        if (handle == 0 || input == nullptr || env->GetArrayLength(input) != kStepSamples) {
            throwJava(env, "Expected an open wake engine and 160 PCM samples");
            return JNI_FALSE;
        }
        std::array<jshort, kStepSamples> samples{};
        env->GetShortArrayRegion(input, 0, static_cast<jsize>(samples.size()), samples.data());
        if (env->ExceptionCheck()) return JNI_FALSE;
        auto* engine = reinterpret_cast<MicroWakeWordEngine*>(handle); // borrowed, thread-confined
        return engine->processAudio(samples.data(), samples.size()) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        throwJava(env, e.what());
        return JNI_FALSE;
    } catch (...) {
        throwJava(env, "Native wake inference failed");
        return JNI_FALSE;
    }
}

auto nativeSnapshot(JNIEnv* env, jclass, jlong handle) -> jintArray {
    if (handle == 0) {
        throwJava(env, "Wake engine is closed");
        return nullptr;
    }
    const auto values = reinterpret_cast<MicroWakeWordEngine*>(handle)->snapshot();
    auto result = env->NewIntArray(static_cast<jsize>(values.size()));
    if (result != nullptr) env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

void nativeDestroy(JNIEnv*, jclass, jlong handle) {
    const std::unique_ptr<MicroWakeWordEngine> engine(reinterpret_cast<MicroWakeWordEngine*>(handle));
}

const std::array methods = {
    JNINativeMethod{"nativeCreate", "(Ljava/nio/ByteBuffer;IIFI)J", reinterpret_cast<void*>(nativeCreate)},
    JNINativeMethod{"nativeProcessAudio", "(J[S)Z", reinterpret_cast<void*>(nativeProcessAudio)},
    JNINativeMethod{"nativeSnapshot", "(J)[I", reinterpret_cast<void*>(nativeSnapshot)},
    JNINativeMethod{"nativeDestroy", "(J)V", reinterpret_cast<void*>(nativeDestroy)},
};
} // namespace

JNIEXPORT auto JNI_OnLoad(JavaVM* vm, void*) -> jint {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    const auto type = env->FindClass("dev/avifenesh/hermes/wake/MicroWakeWord");
    if (type == nullptr) return JNI_ERR;
    const auto result = env->RegisterNatives(type, methods.data(), static_cast<jint>(methods.size()));
    env->DeleteLocalRef(type);
    return result == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
// NOLINTEND(misc-include-cleaner,cppcoreguidelines-pro-type-reinterpret-cast,performance-no-int-to-ptr)
