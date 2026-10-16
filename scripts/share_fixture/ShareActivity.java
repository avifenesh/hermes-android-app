package dev.avifenesh.hermes.sharefixture;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;

/** Separate UID is essential: an in-app test provider cannot prove external URI grants. */
public class ShareActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (!android.os.Build.PRODUCT.contains("sdk")) throw new IllegalStateException("Emulator only");
        String mode = getIntent().getStringExtra("mode");
        Uri uri = Uri.parse("content://dev.avifenesh.hermes.sharefixture.files/shared.txt");
        Intent send = new Intent(Intent.ACTION_SEND).setClassName("dev.avifenesh.hermes", "dev.avifenesh.hermes.MainActivity")
            .setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri("Fixture file", uri));
        if (!"ungranted".equals(mode)) send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(send);
        if ("revoked".equals(mode)) {
            // Revoke after ingress but before the test driver presses Upload.
            new Handler(getMainLooper()).postDelayed(() -> {
                revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                finish();
            }, 1000);
        } else finish();
    }
}
