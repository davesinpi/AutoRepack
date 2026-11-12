package com.autorepack;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** Installs a set of APKs (base + splits) as one app via a PackageInstaller session. */
final class Installer {
    static final String ACTION_INSTALL_STATUS = "com.autorepack.INSTALL_STATUS";

    private Installer() {}

    static void install(Context ctx, List<File> apks, String packageName) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);
        params.setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER);
        long total = 0;
        for (File f : apks) total += f.length();
        params.setSize(total);

        int id = pi.createSession(params);
        try (PackageInstaller.Session session = pi.openSession(id)) {
            byte[] buf = new byte[1 << 16];
            int i = 0;
            for (File f : apks) {
                try (InputStream in = new FileInputStream(f);
                     OutputStream out = session.openWrite((i++) + "_" + f.getName(), 0, f.length())) {
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
            }
            // The system reports progress back to MainActivity (explicit intent, so a mutable
            // PendingIntent is allowed on Android 14).
            Intent result = new Intent(ctx, MainActivity.class)
                    .setAction(ACTION_INSTALL_STATUS)
                    .putExtra(MainActivity.EXTRA_TARGET_PACKAGE, packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pending = PendingIntent.getActivity(ctx, id, result,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
        } catch (Exception e) {
            pi.abandonSession(id);
            throw e;
        }
    }
}
