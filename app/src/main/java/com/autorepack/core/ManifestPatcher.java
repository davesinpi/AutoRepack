package com.autorepack.core;

import com.autorepack.core.Axml.Attr;
import com.autorepack.core.Axml.Element;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rewrites a binary AndroidManifest.xml so the copy can be installed next to the original
 * (new package name, no clashing permissions or provider authorities) and, for the base APK,
 * declares it to Android Auto.
 */
public final class ManifestPatcher {
    public static final String CAR_APP_META = "com.google.android.gms.car.application";

    // android.R.attr ids
    static final int ATTR_LABEL = 0x01010001;
    static final int ATTR_NAME = 0x01010003;
    static final int ATTR_MANAGE_SPACE_ACTIVITY = 0x01010004;
    static final int ATTR_PERMISSION = 0x01010006;
    static final int ATTR_SHARED_USER_ID = 0x0101000b;
    static final int ATTR_TASK_AFFINITY = 0x01010012;
    static final int ATTR_AUTHORITIES = 0x01010018;
    static final int ATTR_RESOURCE = 0x01010025;
    static final int ATTR_TARGET_ACTIVITY = 0x01010202;
    static final int ATTR_SHARED_USER_LABEL = 0x01010261;
    static final int ATTR_TEST_ONLY = 0x01010272;
    static final int ATTR_BACKUP_AGENT = 0x0101027f;
    static final int ATTR_PARENT_ACTIVITY_NAME = 0x010103a7;
    static final int ATTR_APP_COMPONENT_FACTORY = 0x0101057a;
    static final int ATTR_MIN_SDK = 0x0101020c;
    static final int ATTR_TARGET_SDK = 0x01010270;

    /** What the manifest said, for reporting back to the user. */
    public static final class Info {
        public String oldPackage;
        public int minSdk = 1;
        public int targetSdk = 0;
        public boolean hadCarMetadata;
    }

    private ManifestPatcher() {}

    /**
     * @param label     new app label, or null to keep (only used for the base APK)
     * @param carDescId resource id of the generated automotive_app_desc XML, or 0 for split APKs
     */
    public static byte[] patch(byte[] manifest, String expectedOldPkg, String newPkg,
                               String label, int carDescId, Info info) {
        Axml doc = Axml.parse(manifest);
        Element root = doc.root();
        if (!"manifest".equals(root.name)) throw new ApkFormatException("Root element is not <manifest>");

        Attr pkgAttr = null;
        for (Attr a : root.attrs) if ("package".equals(a.name) && a.resId == 0) pkgAttr = a;
        if (pkgAttr == null || pkgAttr.stringValue() == null) throw new ApkFormatException("Manifest has no package name");
        String oldPkg = pkgAttr.stringValue();
        if (expectedOldPkg != null && !expectedOldPkg.equals(oldPkg)) {
            throw new ApkFormatException("APK package is " + oldPkg + ", expected " + expectedOldPkg);
        }
        info.oldPackage = oldPkg;
        pkgAttr.setString(newPkg);

        // A different signer can't join the original's shared user id.
        root.removeAttr(ATTR_SHARED_USER_ID, "sharedUserId");
        root.removeAttr(ATTR_SHARED_USER_LABEL, "sharedUserLabel");
        root.removeAttr(0, "sharedUserMaxSdkVersion");

        Element usesSdk = root.firstChild("uses-sdk");
        if (usesSdk != null) {
            info.minSdk = intAttr(usesSdk.attr(ATTR_MIN_SDK, "minSdkVersion"), 1);
            info.targetSdk = intAttr(usesSdk.attr(ATTR_TARGET_SDK, "targetSdkVersion"), info.minSdk);
        }

        // Permissions this app defines would clash with the original's definitions.
        Map<String, String> renames = new HashMap<>();
        for (String tag : new String[] {"permission", "permission-group", "permission-tree"}) {
            for (Element p : root.childElements(tag)) {
                Attr n = p.attr(ATTR_NAME, "name");
                String v = n == null ? null : n.stringValue();
                if (v != null && !v.isEmpty()) renames.put(v, renamePrefixed(v, oldPkg, newPkg));
            }
        }
        renameExactValues(root, renames);

        absolutizeAndRename(root, oldPkg, newPkg);

        Element app = root.firstChild("application");
        if (app != null) {
            app.removeAttr(ATTR_TEST_ONLY, "testOnly");
            if (carDescId != 0) {
                if (label != null) {
                    app.setStringAttr(Axml.ANDROID_NS, "label", ATTR_LABEL, label);
                    labelLauncherActivities(app, label);
                }
                addCarMetadata(app, carDescId, info);
            }
        }
        return doc.toByteArray();
    }

    /** Builds res/xml/automotive_app_desc.xml: {@code <automotiveApp><uses name="media"/>...</automotiveApp>}. */
    public static byte[] buildAutomotiveDesc(List<String> uses) {
        Axml doc = new Axml();
        Element root = new Element(null, "automotiveApp");
        root.line = 1;
        root.endLine = uses.size() + 2;
        int line = 2;
        for (String u : uses) {
            Element e = new Element(null, "uses");
            e.line = line;
            e.endLine = line++;
            e.attrs.add(Attr.string(null, "name", 0, u));
            root.children.add(e);
        }
        doc.nodes.add(root);
        return doc.toByteArray();
    }

    private static void addCarMetadata(Element app, int carDescId, Info info) {
        for (Element m : app.childElements("meta-data")) {
            Attr n = m.attr(ATTR_NAME, "name");
            if (n != null && CAR_APP_META.equals(n.stringValue())) {
                // The app already describes itself to Android Auto (e.g. a Car App Library
                // "template" app). Its own description is the right one; keep it.
                info.hadCarMetadata = true;
                return;
            }
        }
        Element meta = new Element(null, "meta-data");
        meta.line = app.line;
        meta.endLine = app.line;
        meta.addAttr(Attr.string(Axml.ANDROID_NS, "name", ATTR_NAME, CAR_APP_META));
        meta.addAttr(Attr.reference(Axml.ANDROID_NS, "resource", ATTR_RESOURCE, carDescId));
        app.children.add(meta);
    }

    private static void labelLauncherActivities(Element app, String label) {
        for (Element act : app.childElements(null)) {
            if (!"activity".equals(act.name) && !"activity-alias".equals(act.name)) continue;
            if (act.attr(ATTR_LABEL, "label") == null) continue; // inherits the app label
            for (Element filter : act.childElements("intent-filter")) {
                for (Element cat : filter.childElements("category")) {
                    Attr n = cat.attr(ATTR_NAME, "name");
                    if (n != null && "android.intent.category.LAUNCHER".equals(n.stringValue())) {
                        act.setStringAttr(Axml.ANDROID_NS, "label", ATTR_LABEL, label);
                    }
                }
            }
        }
    }

    private static void renameExactValues(Element e, Map<String, String> renames) {
        if (renames.isEmpty() || "queries".equals(e.name)) return; // <queries> names other apps
        for (Attr a : e.attrs) {
            String v = a.stringValue();
            if (v != null && renames.containsKey(v)) a.setString(renames.get(v));
        }
        for (Element c : e.childElements(null)) renameExactValues(c, renames);
    }

    /**
     * Class names like ".MainActivity" are resolved against the package name, which we're
     * changing, so make them absolute. Also give providers and task affinities new names.
     */
    private static void absolutizeAndRename(Element e, String oldPkg, String newPkg) {
        switch (e.name) {
            case "application":
                absolutize(e, ATTR_NAME, "name", oldPkg);
                absolutize(e, ATTR_BACKUP_AGENT, "backupAgent", oldPkg);
                absolutize(e, ATTR_MANAGE_SPACE_ACTIVITY, "manageSpaceActivity", oldPkg);
                absolutize(e, ATTR_APP_COMPONENT_FACTORY, "appComponentFactory", oldPkg);
                absolutize(e, 0, "zygotePreloadName", oldPkg);
                renameAffinity(e, oldPkg, newPkg);
                break;
            case "activity":
            case "activity-alias":
                absolutize(e, ATTR_NAME, "name", oldPkg);
                absolutize(e, ATTR_TARGET_ACTIVITY, "targetActivity", oldPkg);
                absolutize(e, ATTR_PARENT_ACTIVITY_NAME, "parentActivityName", oldPkg);
                renameAffinity(e, oldPkg, newPkg);
                break;
            case "service":
            case "receiver":
            case "instrumentation":
                absolutize(e, ATTR_NAME, "name", oldPkg);
                break;
            case "provider": {
                absolutize(e, ATTR_NAME, "name", oldPkg);
                Attr auth = e.attr(ATTR_AUTHORITIES, "authorities");
                if (auth != null && auth.stringValue() != null) {
                    StringBuilder sb = new StringBuilder();
                    for (String s : auth.stringValue().split(";")) {
                        s = s.trim();
                        if (s.isEmpty()) continue;
                        if (sb.length() > 0) sb.append(';');
                        sb.append(renamePrefixed(s, oldPkg, newPkg));
                    }
                    auth.setString(sb.toString());
                }
                break;
            }
            default:
                break;
        }
        // Only the app's own components get renamed: those under <application>, plus
        // <instrumentation> under <manifest>. Anything else (e.g. <queries>) is left alone.
        if ("manifest".equals(e.name) || "application".equals(e.name)) {
            for (Element c : e.childElements(null)) absolutizeAndRename(c, oldPkg, newPkg);
        }
    }

    private static void absolutize(Element e, int resId, String name, String pkg) {
        Attr a = e.attr(resId, name);
        if (a == null) return;
        String v = a.stringValue();
        if (v == null || v.isEmpty()) return;
        if (v.startsWith(".")) a.setString(pkg + v);
        else if (v.indexOf('.') < 0) a.setString(pkg + "." + v);
    }

    private static void renameAffinity(Element e, String oldPkg, String newPkg) {
        Attr a = e.attr(ATTR_TASK_AFFINITY, "taskAffinity");
        String v = a == null ? null : a.stringValue();
        if (v != null && !v.isEmpty() && (v.equals(oldPkg) || v.startsWith(oldPkg + ".") || v.startsWith(oldPkg + ":"))) {
            a.setString(newPkg + v.substring(oldPkg.length()));
        }
    }

    /** "old.pkg.x" -> "new.pkg.x"; anything else gets the new package as a prefix. */
    static String renamePrefixed(String value, String oldPkg, String newPkg) {
        if (value.equals(oldPkg) || value.startsWith(oldPkg + ".")) {
            return newPkg + value.substring(oldPkg.length());
        }
        return newPkg + "." + value;
    }

    private static int intAttr(Attr a, int def) {
        if (a == null) return def;
        if (a.type >= Axml.TYPE_INT_DEC && a.type <= 0x1f) return a.data;
        String s = a.stringValue();
        if (s != null) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return 10_000; // a preview codename such as "VanillaIceCream"
            }
        }
        return def;
    }
}
