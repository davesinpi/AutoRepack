package com.autorepack.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Edits resources.arsc: renames the resource package to match the new manifest package and,
 * for the base APK, appends one new resource (a file path) in a new type so the manifest can
 * reference the generated automotive_app_desc XML.
 */
public final class ArscPatcher {
    private static final int RES_TABLE_TYPE = 0x0002;
    private static final int RES_TABLE_PACKAGE_TYPE = 0x0200;
    private static final int RES_TABLE_TYPE_TYPE = 0x0201;
    private static final int RES_TABLE_TYPE_SPEC_TYPE = 0x0202;
    private static final int PKG_NAME_OFFSET = 12;
    private static final int PKG_NAME_CHARS = 128;

    public static final class Result {
        public byte[] arsc;
        /** Id of the added resource, or 0 if none was requested. */
        public int resourceId;
    }

    private ArscPatcher() {}

    /**
     * @param newFilePath path inside the APK of a file to register as a resource, or null
     */
    public static Result patch(byte[] arsc, String oldPkg, String newPkg, String newFilePath) {
        ByteBuffer b = ByteBuffer.wrap(arsc).order(ByteOrder.LITTLE_ENDIAN);
        if (arsc.length < 12 || (b.getShort(0) & 0xffff) != RES_TABLE_TYPE) {
            throw new ApkFormatException("resources.arsc is not a resource table");
        }
        int headerSize = b.getShort(2) & 0xffff;
        int tableSize = Math.min(b.getInt(4), arsc.length);

        ResStringPool globalPool = null;
        ByteArrayOutputStream packages = new ByteArrayOutputStream();
        int target = -1; // offset of the package that gets the new resource
        int pos = headerSize;
        while (pos + 8 <= tableSize) {
            int type = b.getShort(pos) & 0xffff;
            int size = b.getInt(pos + 4);
            if (size < 8 || pos + size > tableSize) throw new ApkFormatException("Corrupt resources.arsc chunk at " + pos);
            if (type == ResStringPool.TYPE && globalPool == null) {
                globalPool = new ResStringPool(arsc, pos);
            } else if (type == RES_TABLE_PACKAGE_TYPE) {
                int id = b.getInt(pos + 8);
                if (target < 0 || id == 0x7f) target = pos;
            }
            pos += size;
        }
        if (globalPool == null) throw new ApkFormatException("resources.arsc has no string pool");

        Result result = new Result();
        int pathIndex = newFilePath != null && target >= 0 ? globalPool.add(newFilePath) : -1;

        pos = headerSize;
        int packageCount = 0;
        while (pos + 8 <= tableSize) {
            int type = b.getShort(pos) & 0xffff;
            int size = b.getInt(pos + 4);
            if (type == RES_TABLE_PACKAGE_TYPE) {
                packageCount++;
                byte[] pkg = Arrays.copyOfRange(arsc, pos, pos + size);
                renamePackage(pkg, oldPkg, newPkg);
                if (pos == target && pathIndex >= 0) {
                    pkg = addFileResource(pkg, pathIndex, result);
                }
                packages.write(pkg, 0, pkg.length);
            } else if (type != ResStringPool.TYPE) {
                packages.write(arsc, pos, size);
            }
            pos += size;
        }

        byte[] pool = globalPool.toByteArray();
        int total = headerSize + pool.length + packages.size();
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.put(arsc, 0, headerSize);
        out.putInt(4, total);
        out.putInt(8, packageCount);
        out.position(headerSize);
        out.put(pool);
        out.put(packages.toByteArray());
        result.arsc = out.array();
        return result;
    }

    private static void renamePackage(byte[] pkg, String oldPkg, String newPkg) {
        ByteBuffer b = ByteBuffer.wrap(pkg).order(ByteOrder.LITTLE_ENDIAN);
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < PKG_NAME_CHARS; i++) {
            char c = b.getChar(PKG_NAME_OFFSET + 2 * i);
            if (c == 0) break;
            name.append(c);
        }
        if (!name.toString().equals(oldPkg)) return;
        if (newPkg.length() >= PKG_NAME_CHARS) throw new ApkFormatException("New package name is too long");
        for (int i = 0; i < PKG_NAME_CHARS; i++) {
            b.putChar(PKG_NAME_OFFSET + 2 * i, i < newPkg.length() ? newPkg.charAt(i) : 0);
        }
    }

    /** Appends a type spec + type with a single entry whose value is the given global string. */
    private static byte[] addFileResource(byte[] pkg, int pathIndex, Result result) {
        ByteBuffer b = ByteBuffer.wrap(pkg).order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = b.getShort(2) & 0xffff;
        int pkgId = b.getInt(8);
        int typeStringsOff = b.getInt(268);
        int keyStringsOff = b.getInt(276);
        int typeIdOffset = headerSize >= 288 ? b.getInt(284) : 0;
        if (typeIdOffset != 0) throw new ApkFormatException("Unsupported resource table (type id offset)");

        ResStringPool typeStrings = new ResStringPool(pkg, typeStringsOff);
        ResStringPool keyStrings = new ResStringPool(pkg, keyStringsOff);
        int childrenStart = Math.max(typeStringsOff + typeStrings.originalSize, keyStringsOff + keyStrings.originalSize);

        // Find the highest type id in use and the config size the table uses.
        int maxTypeId = typeStrings.size();
        int configSize = 64;
        boolean configSizeFound = false;
        for (int p = childrenStart; p + 8 <= pkg.length; ) {
            int type = b.getShort(p) & 0xffff;
            int size = b.getInt(p + 4);
            if (size < 8 || p + size > pkg.length) throw new ApkFormatException("Corrupt resource package chunk");
            if (type == RES_TABLE_TYPE_SPEC_TYPE || type == RES_TABLE_TYPE_TYPE) {
                maxTypeId = Math.max(maxTypeId, pkg[p + 8] & 0xff);
            }
            if (type == RES_TABLE_TYPE_TYPE && !configSizeFound) {
                int cs = b.getInt(p + 20);
                if (cs >= 28 && cs <= 256 && cs % 4 == 0) configSize = cs;
                configSizeFound = true;
            }
            p += size;
        }
        int newTypeId = maxTypeId + 1;
        if (newTypeId > 0xff) throw new ApkFormatException("No free resource type id");
        while (typeStrings.size() < newTypeId - 1) typeStrings.add("aarepack_pad" + typeStrings.size());
        typeStrings.add(freeTypeName(typeStrings));
        int keyIndex = keyStrings.add("aarepack_automotive_app_desc");

        // ResTable_typeSpec with one entry.
        ByteBuffer spec = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        spec.putShort((short) RES_TABLE_TYPE_SPEC_TYPE).putShort((short) 16).putInt(20);
        spec.put((byte) newTypeId).put((byte) 0).putShort((short) 1).putInt(1);
        spec.putInt(0);

        // ResTable_type (default config) with one string entry.
        int typeHeader = 20 + configSize;
        int entriesStart = typeHeader + 4;
        int typeSize = entriesStart + 16;
        ByteBuffer t = ByteBuffer.allocate(typeSize).order(ByteOrder.LITTLE_ENDIAN);
        t.putShort((short) RES_TABLE_TYPE_TYPE).putShort((short) typeHeader).putInt(typeSize);
        t.put((byte) newTypeId).put((byte) 0).putShort((short) 0);
        t.putInt(1).putInt(entriesStart);
        t.putInt(configSize); // ResTable_config.size; the rest stays zero = default config
        t.position(typeHeader);
        t.putInt(0); // entry 0 at offset 0
        t.putShort((short) 8).putShort((short) 0).putInt(keyIndex); // ResTable_entry
        t.putShort((short) 8).put((byte) 0).put((byte) Axml.TYPE_STRING).putInt(pathIndex); // Res_value

        byte[] typePool = typeStrings.toByteArray();
        byte[] keyPool = keyStrings.toByteArray();
        int restLen = pkg.length - childrenStart;
        int total = headerSize + typePool.length + keyPool.length + restLen + spec.capacity() + typeSize;

        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.put(pkg, 0, headerSize);
        out.putInt(4, total);
        out.putInt(268, headerSize);
        out.putInt(276, headerSize + typePool.length);
        out.position(headerSize);
        out.put(typePool).put(keyPool);
        out.put(pkg, childrenStart, restLen);
        out.put(spec.array()).put(t.array());

        result.resourceId = (pkgId << 24) | (newTypeId << 16);
        return out.array();
    }

    /**
     * The new type only needs to be unique; the framework loads the file by path whatever the
     * type is called. Prefer a standard type name the app doesn't use so tools stay happy.
     */
    private static String freeTypeName(ResStringPool typeStrings) {
        String[] candidates = {"xml", "raw", "transition", "navigation", "menu", "interpolator",
                "animator", "font", "fraction", "macro"};
        for (String c : candidates) if (typeStrings.indexOf(c) < 0) return c;
        return "aarepack_xml";
    }
}
