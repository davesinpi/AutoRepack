package com.autorepack;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import com.android.apksig.ApkSigner;
import com.autorepack.core.ApkFormatException;
import com.autorepack.core.ApkRewriter;
import com.autorepack.core.ManifestPatcher;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Rewrites and signs every APK of an installed app. Runs on a background thread. */
final class RepackJob {
    private static final String TAG = "AutoRepack";

    interface Listener {
        void onStatus(String text, int percent);
    }

    private RepackJob() {}

    /** Returns the signed APKs (base first), ready to install together. */
    static List<File> run(Context ctx, ApplicationInfo app, String newPkg, String label,
                          List<String> carUses, Listener listener) throws Exception {
        // Check before doing any work: Android 14 refuses apps targeting below API 23,
        // Android 15 and later below API 24.
        int minTarget = Build.VERSION.SDK_INT >= 35 ? 24 : Build.VERSION.SDK_INT >= 34 ? 23 : 0;
        if (app.targetSdkVersion > 0 && app.targetSdkVersion < minTarget) {
            throw new ApkFormatException("This app targets Android API " + app.targetSdkVersion
                    + ". This phone refuses to install apps targeting below API " + minTarget + ".");
        }

        allowAnyZipEntryNames();

        List<File> sources = new ArrayList<>();
        sources.add(new File(app.sourceDir));
        if (app.splitSourceDirs != null) {
            for (String s : app.splitSourceDirs) sources.add(new File(s));
        }

        File work = new File(ctx.getCacheDir(), "repack");
        deleteRecursive(work);
        if (!work.mkdirs()) throw new IllegalStateException("Can't create " + work);

        listener.onStatus("Preparing signing key…", 0);
        LocalSigningKey key = LocalSigningKey.get();
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                "AUTOREPK", key.privateKey, Collections.singletonList(key.certificate)).build();

        List<File> outputs = new ArrayList<>();
        int n = sources.size();
        for (int i = 0; i < n; i++) {
            File src = sources.get(i);
            final int index = i;
            final String what = n == 1 ? "APK" : (i == 0 ? "base APK" : "split " + i + " of " + (n - 1));

            ApkRewriter.Options o = new ApkRewriter.Options();
            o.oldPackage = app.packageName;
            o.newPackage = newPkg;
            o.base = i == 0;
            o.label = label;
            o.carUses = carUses;

            File unsigned = new File(work, i + "-unsigned.apk");
            File signed = new File(work, i + ".apk");
            ManifestPatcher.Info info = ApkRewriter.rewrite(src, unsigned, o, (done, total) -> {
                if (done % 50 == 0 || done == total) {
                    listener.onStatus("Rewriting " + what + "…", percent(index, n, done * 0.7 / total));
                }
            });

            listener.onStatus("Signing " + what + "…", percent(i, n, 0.75));
            new ApkSigner.Builder(Collections.singletonList(signer))
                    .setInputApk(unsigned)
                    .setOutputApk(signed)
                    // The copy only installs on this phone. Signing for an older minSdk would make
                    // apksig use SHA-1 for v1 (minSdk < 18), which the Keystore key doesn't allow.
                    .setMinSdkVersion(Build.VERSION.SDK_INT)
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .setV4SigningEnabled(false)
                    // Uncompressed native libraries must be 16 KB aligned on phones with 16 KB
                    // memory pages (Android 15+); that also satisfies 4 KB phones.
                    .setLibraryPageAlignmentBytes(16384)
                    .build()
                    .sign();
            //noinspection ResultOfMethodCallIgnored
            unsigned.delete();
            outputs.add(signed);
            Log.i(TAG, "Prepared " + signed + " (" + signed.length() + " bytes)");
        }
        listener.onStatus("Ready to install", 100);
        return outputs;
    }

    private static int percent(int index, int count, double fraction) {
        return (int) Math.round(100.0 * (index + fraction) / count);
    }

    /**
     * Apps targeting Android 14 reject zip entries containing ".." by default. We only copy
     * entries from one zip to another, never to the file system, so that check isn't needed.
     */
    private static void allowAnyZipEntryNames() {
        if (Build.VERSION.SDK_INT >= 34) {
            dalvik.system.ZipPathValidator.clearCallback();
        }
    }

    static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
