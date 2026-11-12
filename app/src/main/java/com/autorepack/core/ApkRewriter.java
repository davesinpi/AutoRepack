package com.autorepack.core;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Copies one APK (base or split) into a new, unsigned APK with a patched manifest and
 * resource table. The old signature is dropped; signing happens afterwards.
 */
public final class ApkRewriter {
    static final String MANIFEST = "AndroidManifest.xml";
    static final String ARSC = "resources.arsc";
    public static final String CAR_DESC_PATH = "res/aarepack_automotive_app_desc.xml";

    public static final class Options {
        public String oldPackage;
        public String newPackage;
        /** True for the base APK, which gets the Android Auto declaration. */
        public boolean base;
        /** Base only: new launcher label, or null to keep the original. */
        public String label;
        /** Base only: automotive "uses" entries, e.g. "media", "notification". */
        public List<String> carUses;
    }

    public interface Progress {
        void onEntry(int done, int total);
    }

    private ApkRewriter() {}

    public static ManifestPatcher.Info rewrite(File in, File out, Options o, Progress progress) throws IOException {
        ManifestPatcher.Info info = new ManifestPatcher.Info();
        try (ZipFile zip = new ZipFile(in)) {
            ZipEntry manifestEntry = zip.getEntry(MANIFEST);
            if (manifestEntry == null) throw new ApkFormatException(in.getName() + " has no AndroidManifest.xml");

            int carDescId = 0;
            byte[] arsc = null;
            ZipEntry arscEntry = zip.getEntry(ARSC);
            if (arscEntry != null) {
                ArscPatcher.Result r = ArscPatcher.patch(read(zip, arscEntry), o.oldPackage, o.newPackage,
                        o.base ? CAR_DESC_PATH : null);
                arsc = r.arsc;
                carDescId = r.resourceId;
            }
            if (o.base && carDescId == 0) {
                throw new ApkFormatException("This app has no resource table, so it can't be declared to Android Auto");
            }
            byte[] manifest = ManifestPatcher.patch(read(zip, manifestEntry), o.oldPackage, o.newPackage,
                    o.base ? o.label : null, o.base ? carDescId : 0, info);

            int total = zip.size();
            int done = 0;
            Set<String> written = new HashSet<>();
            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 16))) {
                put(zos, MANIFEST, manifest, false);
                written.add(MANIFEST);
                if (arsc != null) {
                    put(zos, ARSC, arsc, true); // must be stored uncompressed (targetSdk 30+)
                    written.add(ARSC);
                }
                if (o.base) {
                    put(zos, CAR_DESC_PATH, ManifestPatcher.buildAutomotiveDesc(o.carUses), false);
                    written.add(CAR_DESC_PATH);
                }

                byte[] buf = new byte[1 << 16];
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String name = e.getName();
                    if (progress != null) progress.onEntry(++done, total);
                    if (e.isDirectory() || isSignatureFile(name) || !written.add(name)) continue;

                    ZipEntry ne = new ZipEntry(name);
                    if (e.getMethod() == ZipEntry.STORED) {
                        ne.setMethod(ZipEntry.STORED);
                        ne.setSize(e.getSize());
                        ne.setCompressedSize(e.getSize());
                        ne.setCrc(e.getCrc());
                    } else {
                        ne.setMethod(ZipEntry.DEFLATED);
                    }
                    zos.putNextEntry(ne);
                    try (InputStream is = zip.getInputStream(e)) {
                        int n;
                        while ((n = is.read(buf)) > 0) zos.write(buf, 0, n);
                    }
                    zos.closeEntry();
                }
            }
        }
        return info;
    }

    /** Reads just the manifest info (package, SDK levels) without writing anything. */
    public static ManifestPatcher.Info inspect(File apk) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry m = zip.getEntry(MANIFEST);
            if (m == null) throw new ApkFormatException("No AndroidManifest.xml");
            ManifestPatcher.Info info = new ManifestPatcher.Info();
            ManifestPatcher.patch(read(zip, m), null, "inspect.only", null, 0, info);
            return info;
        }
    }

    static boolean isSignatureFile(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        if (!n.startsWith("META-INF/") || n.indexOf('/', 9) >= 0) return false;
        return n.equals("META-INF/MANIFEST.MF") || n.endsWith(".SF") || n.endsWith(".RSA")
                || n.endsWith(".DSA") || n.endsWith(".EC");
    }

    private static void put(ZipOutputStream zos, String name, byte[] data, boolean stored) throws IOException {
        ZipEntry e = new ZipEntry(name);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(data);
            e.setMethod(ZipEntry.STORED);
            e.setSize(data.length);
            e.setCompressedSize(data.length);
            e.setCrc(crc.getValue());
        } else {
            e.setMethod(ZipEntry.DEFLATED);
        }
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    static byte[] read(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream is = zip.getInputStream(e)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
