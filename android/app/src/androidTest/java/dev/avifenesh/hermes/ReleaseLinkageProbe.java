package dev.avifenesh.hermes;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import org.json.JSONObject;

/** Test APK only. No Kotlin/test-runner shared APIs that the target R8 may remove. */
public final class ReleaseLinkageProbe extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!Build.PRODUCT.contains("sdk")) throw new IllegalStateException("Disposable emulator only");
            Context context = getTargetContext();
            ClassLoader loader = context.getClassLoader();
            Class<?> worker = loader.loadClass("dev.avifenesh.hermes.SyncWorker");
            boolean retained = false;
            for (Constructor<?> constructor : worker.getConstructors()) {
                Class<?>[] types = constructor.getParameterTypes();
                if (types.length == 2 && types[0] == Context.class && !types[1].isPrimitive()) retained = true;
            }
            if (!retained) throw new AssertionError("WorkManager reflection constructor missing");

            JSONObject config;
            try (InputStream input = context.getAssets().open("microwakeword/hey_jarvis.json")) {
                config = new JSONObject(new String(read(input), java.nio.charset.StandardCharsets.UTF_8));
            }
            byte[] bytes;
            try (InputStream input = context.getAssets().open("microwakeword/" + config.getString("model"))) {
                bytes = read(input);
            }
            ByteBuffer model = ByteBuffer.allocateDirect(bytes.length); model.put(bytes); model.rewind();
            Class<?> nativeClass = loader.loadClass("dev.avifenesh.hermes.wake.MicroWakeWord");
            System.loadLibrary("microwakeword");
            Method create = nativeClass.getDeclaredMethod("nativeCreate", ByteBuffer.class, int.class, int.class, float.class, int.class);
            Method process = nativeClass.getDeclaredMethod("nativeProcessAudio", long.class, short[].class);
            Method snapshot = nativeClass.getDeclaredMethod("nativeSnapshot", long.class);
            Method destroy = nativeClass.getDeclaredMethod("nativeDestroy", long.class);
            for (Method method : new Method[]{create, process, snapshot, destroy}) method.setAccessible(true);
            JSONObject options = config.getJSONObject("micro");
            long handle = (Long) create.invoke(null, model, 16000, options.getInt("feature_step_size"),
                    (float) options.getDouble("probability_cutoff"), options.getInt("sliding_window_size"));
            if (handle == 0) throw new AssertionError("Native engine creation failed");
            try {
                for (int i = 0; i < 100; i++) process.invoke(null, handle, new short[160]);
                if (((int[]) snapshot.invoke(null, handle)).length == 0) throw new AssertionError("Empty native snapshot");
            } finally { destroy.invoke(null, handle); }
            result.putString("stream", "RELEASE LINKAGE PASS: native create/process/snapshot/destroy and retained WorkManager constructor. Silence is not an accuracy benchmark; no worker job or physical-phone behavior is asserted.");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private static byte[] read(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
        for (int n; (n = input.read(buffer)) >= 0;) output.write(buffer, 0, n);
        return output.toByteArray();
    }
}
