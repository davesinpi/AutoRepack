package com.autorepack.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A ResStringPool chunk (used by binary XML and resources.arsc).
 * Existing string and style data is kept byte-for-byte; new strings are appended at the end,
 * so every existing index stays valid.
 */
final class ResStringPool {
    static final int TYPE = 0x0001;
    private static final int HEADER_SIZE = 28;
    private static final int FLAG_SORTED = 1;
    private static final int FLAG_UTF8 = 1 << 8;

    private int flags;
    private final List<Integer> stringOffsets = new ArrayList<>();
    private final int[] styleOffsets;
    private final ByteArrayOutputStream stringData = new ByteArrayOutputStream();
    private final byte[] styleData;
    /** Size of the chunk this pool was read from (0 for a new pool). */
    final int originalSize;

    /** Creates an empty pool. */
    ResStringPool(boolean utf8) {
        flags = utf8 ? FLAG_UTF8 : 0;
        styleOffsets = new int[0];
        styleData = new byte[0];
        originalSize = 0;
    }

    /** Parses the pool chunk that starts at {@code start}. */
    ResStringPool(byte[] buf, int start) {
        ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        if (start + HEADER_SIZE > buf.length || (b.getShort(start) & 0xffff) != TYPE) {
            throw new ApkFormatException("Expected a string pool at offset " + start);
        }
        int headerSize = b.getShort(start + 2) & 0xffff;
        int size = b.getInt(start + 4);
        int stringCount = b.getInt(start + 8);
        int styleCount = b.getInt(start + 12);
        flags = b.getInt(start + 16);
        int stringsStart = b.getInt(start + 20);
        int stylesStart = b.getInt(start + 24);
        if (size < headerSize || start + size > buf.length || stringCount < 0 || styleCount < 0
                || headerSize + 4L * (stringCount + (long) styleCount) > size) {
            throw new ApkFormatException("Corrupt string pool at offset " + start);
        }
        originalSize = size;
        int off = start + headerSize;
        for (int i = 0; i < stringCount; i++) stringOffsets.add(b.getInt(off + 4 * i));
        off += 4 * stringCount;
        styleOffsets = new int[styleCount];
        for (int i = 0; i < styleCount; i++) styleOffsets[i] = b.getInt(off + 4 * i);

        if (stringCount > 0) {
            int end = (styleCount > 0 && stylesStart > stringsStart) ? stylesStart : size;
            if (stringsStart <= 0 || stringsStart > end || end > size) {
                throw new ApkFormatException("Corrupt string pool data at offset " + start);
            }
            stringData.write(buf, start + stringsStart, end - stringsStart);
        }
        if (styleCount > 0 && stylesStart > 0 && stylesStart <= size) {
            styleData = Arrays.copyOfRange(buf, start + stylesStart, start + size);
        } else {
            styleData = new byte[0];
        }
    }

    int size() {
        return stringOffsets.size();
    }

    boolean isUtf8() {
        return (flags & FLAG_UTF8) != 0;
    }

    /** Decodes string {@code index}; returns null for index -1 or an out-of-range index. */
    String get(int index) {
        if (index < 0 || index >= stringOffsets.size()) return null;
        byte[] d = stringData.toByteArray();
        int p = stringOffsets.get(index);
        if (p < 0 || p >= d.length) return null;
        try {
            if (isUtf8()) {
                p += (d[p] & 0x80) != 0 ? 2 : 1; // length in UTF-16 units (ignored)
                int len = d[p] & 0xff;
                if ((len & 0x80) != 0) {
                    len = ((len & 0x7f) << 8) | (d[p + 1] & 0xff);
                    p += 2;
                } else {
                    p += 1;
                }
                return new String(d, p, Math.min(len, d.length - p), StandardCharsets.UTF_8);
            } else {
                int len = u16(d, p);
                if ((len & 0x8000) != 0) {
                    len = ((len & 0x7fff) << 16) | u16(d, p + 2);
                    p += 4;
                } else {
                    p += 2;
                }
                len = Math.min(len, (d.length - p) / 2);
                char[] c = new char[len];
                for (int i = 0; i < len; i++) c[i] = (char) u16(d, p + 2 * i);
                return new String(c);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }

    /** Index of the first string equal to {@code s}, or -1. */
    int indexOf(String s) {
        for (int i = 0; i < stringOffsets.size(); i++) {
            if (s.equals(get(i))) return i;
        }
        return -1;
    }

    /** Appends a string and returns its index. */
    int add(String s) {
        int offset = stringData.size();
        byte[] enc = isUtf8() ? encodeUtf8(s) : encodeUtf16(s);
        stringData.write(enc, 0, enc.length);
        stringOffsets.add(offset);
        flags &= ~FLAG_SORTED;
        return stringOffsets.size() - 1;
    }

    byte[] toByteArray() {
        int count = stringOffsets.size();
        int styleCount = styleOffsets.length;
        byte[] strings = stringData.toByteArray();
        int stringsLen = align4(strings.length);
        int stringsStart = HEADER_SIZE + 4 * count + 4 * styleCount;
        int stylesStart = styleCount > 0 ? stringsStart + stringsLen : 0;
        int size = align4(stringsStart + stringsLen + styleData.length);

        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) TYPE).putShort((short) HEADER_SIZE).putInt(size);
        b.putInt(count).putInt(styleCount).putInt(flags);
        b.putInt(count > 0 ? stringsStart : 0).putInt(stylesStart);
        for (int o : stringOffsets) b.putInt(o);
        for (int o : styleOffsets) b.putInt(o);
        b.put(strings);
        b.position(stringsStart + stringsLen);
        b.put(styleData);
        return b.array();
    }

    private static byte[] encodeUtf8(String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        if (s.length() > 0x7fff || utf8.length > 0x7fff) {
            throw new ApkFormatException("String too long for a UTF-8 pool");
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeLen8(o, s.length());
        writeLen8(o, utf8.length);
        o.write(utf8, 0, utf8.length);
        o.write(0);
        return o.toByteArray();
    }

    private static void writeLen8(ByteArrayOutputStream o, int len) {
        if (len > 0x7f) o.write(0x80 | (len >> 8));
        o.write(len & 0xff);
    }

    private static byte[] encodeUtf16(String s) {
        int len = s.length();
        ByteBuffer b = ByteBuffer.allocate(4 + 2 * len + 2).order(ByteOrder.LITTLE_ENDIAN);
        if (len > 0x7fff) {
            b.putShort((short) (0x8000 | (len >>> 16))).putShort((short) len);
        } else {
            b.putShort((short) len);
        }
        for (int i = 0; i < len; i++) b.putChar(s.charAt(i));
        b.putShort((short) 0);
        return Arrays.copyOf(b.array(), b.position());
    }

    private static int u16(byte[] d, int p) {
        return (d[p] & 0xff) | ((d[p + 1] & 0xff) << 8);
    }

    static int align4(int n) {
        return (n + 3) & ~3;
    }
}
