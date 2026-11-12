package com.autorepack.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes Android binary XML (the compiled AndroidManifest.xml / res/xml format).
 * The document is held as a tree with real strings instead of pool indices, and a fresh
 * string pool and resource map are built on write, so attributes can be added freely.
 */
public final class Axml {
    public static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    static final int TYPE_REFERENCE = 0x01;
    static final int TYPE_STRING = 0x03;
    static final int TYPE_INT_DEC = 0x10;
    static final int TYPE_INT_BOOLEAN = 0x12;

    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_XML_RESOURCE_MAP_TYPE = 0x0180;
    private static final int START_NAMESPACE = 0x0100;
    private static final int END_NAMESPACE = 0x0101;
    private static final int START_ELEMENT = 0x0102;
    private static final int END_ELEMENT = 0x0103;
    private static final int CDATA = 0x0104;

    /** Base for everything that can appear in the node stream. */
    public abstract static class Node {
        int line;
        String comment;
    }

    public static final class Namespace extends Node {
        final boolean start;
        String prefix, uri;

        Namespace(boolean start, String prefix, String uri) {
            this.start = start;
            this.prefix = prefix;
            this.uri = uri;
        }
    }

    public static final class Text extends Node {
        String text;
        int type;
        int data;
        String stringData; // used when type == TYPE_STRING
    }

    public static final class Attr {
        String ns, name;
        int resId;
        String raw;
        int type;
        int data;
        String stringData; // used when type == TYPE_STRING

        static Attr string(String ns, String name, int resId, String value) {
            Attr a = new Attr();
            a.ns = ns;
            a.name = name;
            a.resId = resId;
            a.setString(value);
            return a;
        }

        static Attr reference(String ns, String name, int resId, int ref) {
            Attr a = new Attr();
            a.ns = ns;
            a.name = name;
            a.resId = resId;
            a.type = TYPE_REFERENCE;
            a.data = ref;
            return a;
        }

        void setString(String value) {
            raw = value;
            type = TYPE_STRING;
            stringData = value;
            data = 0;
        }

        /** The string value if this is a plain string attribute, otherwise null. */
        String stringValue() {
            return type == TYPE_STRING ? stringData : null;
        }
    }

    public static final class Element extends Node {
        String ns, name;
        final List<Attr> attrs = new ArrayList<>();
        final List<Node> children = new ArrayList<>();
        int endLine;
        String endComment;

        Element(String ns, String name) {
            this.ns = ns;
            this.name = name;
        }

        List<Element> childElements(String tag) {
            List<Element> out = new ArrayList<>();
            for (Node n : children) {
                if (n instanceof Element && (tag == null || tag.equals(((Element) n).name))) {
                    out.add((Element) n);
                }
            }
            return out;
        }

        Element firstChild(String tag) {
            List<Element> l = childElements(tag);
            return l.isEmpty() ? null : l.get(0);
        }

        /** Finds an attribute by android resource id, falling back to its name (namespace-insensitive). */
        Attr attr(int resId, String name) {
            for (Attr a : attrs) if (resId != 0 && a.resId == resId) return a;
            for (Attr a : attrs) if (name.equals(a.name) && (a.resId == 0 || resId == 0)) return a;
            return null;
        }

        void removeAttr(int resId, String name) {
            Attr a;
            while ((a = attr(resId, name)) != null) attrs.remove(a);
        }

        /** Adds an attribute keeping aapt's order: resource-id attributes ascending, then the rest. */
        void addAttr(Attr a) {
            int i = 0;
            if (a.resId != 0) {
                while (i < attrs.size() && attrs.get(i).resId != 0
                        && (attrs.get(i).resId & 0xffffffffL) < (a.resId & 0xffffffffL)) i++;
            } else {
                i = attrs.size();
            }
            attrs.add(i, a);
        }

        void setStringAttr(String ns, String name, int resId, String value) {
            Attr a = attr(resId, name);
            if (a != null) {
                a.setString(value);
            } else {
                addAttr(Attr.string(ns, name, resId, value));
            }
        }
    }

    /** Top-level node stream (namespace nodes and the root element). */
    public final List<Node> nodes = new ArrayList<>();

    public Element root() {
        for (Node n : nodes) if (n instanceof Element) return (Element) n;
        throw new ApkFormatException("Binary XML has no root element");
    }

    // ------------------------------------------------------------------ parsing

    public static Axml parse(byte[] buf) {
        ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.length < 8 || (b.getShort(0) & 0xffff) != RES_XML_TYPE) {
            throw new ApkFormatException("Not a binary XML file");
        }
        int fileEnd = Math.min(b.getInt(4), buf.length);
        int pos = b.getShort(2) & 0xffff;

        Axml doc = new Axml();
        ResStringPool pool = null;
        int[] resMap = new int[0];
        Deque<Element> stack = new ArrayDeque<>();

        while (pos + 8 <= fileEnd) {
            int type = b.getShort(pos) & 0xffff;
            int hsize = b.getShort(pos + 2) & 0xffff;
            int csize = b.getInt(pos + 4);
            if (csize < 8 || pos + csize > fileEnd) throw new ApkFormatException("Corrupt XML chunk at " + pos);

            if (type == ResStringPool.TYPE) {
                pool = new ResStringPool(buf, pos);
            } else if (type == RES_XML_RESOURCE_MAP_TYPE) {
                resMap = new int[(csize - hsize) / 4];
                for (int i = 0; i < resMap.length; i++) resMap[i] = b.getInt(pos + hsize + 4 * i);
            } else if (type >= START_NAMESPACE && type <= CDATA) {
                if (pool == null) throw new ApkFormatException("XML node before string pool");
                int line = b.getInt(pos + 8);
                String comment = pool.get(b.getInt(pos + 12));
                int ext = pos + hsize;
                Node node;
                switch (type) {
                    case START_NAMESPACE:
                    case END_NAMESPACE:
                        node = new Namespace(type == START_NAMESPACE,
                                pool.get(b.getInt(ext)), pool.get(b.getInt(ext + 4)));
                        break;
                    case START_ELEMENT: {
                        Element e = new Element(pool.get(b.getInt(ext)), pool.get(b.getInt(ext + 4)));
                        int attrStart = b.getShort(ext + 8) & 0xffff;
                        int attrSize = b.getShort(ext + 10) & 0xffff;
                        int count = b.getShort(ext + 12) & 0xffff;
                        if (attrSize < 20 || ext + attrStart + (long) attrSize * count > pos + csize) {
                            throw new ApkFormatException("Corrupt XML element at " + pos);
                        }
                        for (int i = 0; i < count; i++) {
                            int ap = ext + attrStart + i * attrSize;
                            Attr a = new Attr();
                            a.ns = pool.get(b.getInt(ap));
                            int nameIdx = b.getInt(ap + 4);
                            a.name = pool.get(nameIdx);
                            if (a.name == null) a.name = "";
                            a.resId = (nameIdx >= 0 && nameIdx < resMap.length) ? resMap[nameIdx] : 0;
                            a.raw = pool.get(b.getInt(ap + 8));
                            a.type = b.get(ap + 15) & 0xff;
                            a.data = b.getInt(ap + 16);
                            if (a.type == TYPE_STRING) a.stringData = pool.get(a.data);
                            e.attrs.add(a);
                        }
                        node = e;
                        break;
                    }
                    case END_ELEMENT: {
                        Element open = stack.poll();
                        if (open == null) throw new ApkFormatException("Unbalanced XML end tag at " + pos);
                        open.endLine = line;
                        open.endComment = comment;
                        pos += csize;
                        continue;
                    }
                    default: { // CDATA
                        Text t = new Text();
                        t.text = pool.get(b.getInt(ext));
                        t.type = b.get(ext + 7) & 0xff;
                        t.data = b.getInt(ext + 8);
                        if (t.type == TYPE_STRING) t.stringData = pool.get(t.data);
                        node = t;
                    }
                }
                node.line = line;
                node.comment = comment;
                Element parent = stack.peek();
                (parent != null ? parent.children : doc.nodes).add(node);
                if (node instanceof Element) stack.push((Element) node);
            }
            pos += csize;
        }
        if (!stack.isEmpty()) throw new ApkFormatException("Unclosed XML element <" + stack.peek().name + ">");
        return doc;
    }

    // ------------------------------------------------------------------ writing

    public byte[] toByteArray() {
        Pool pool = new Pool();
        collectMapped(nodes, pool);
        collectOther(nodes, pool);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeNodes(nodes, pool, body);

        byte[] poolBytes = pool.strings.toByteArray();
        int mapSize = 8 + 4 * pool.resIds.size();
        int total = 8 + poolBytes.length + mapSize + body.size();

        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) RES_XML_TYPE).putShort((short) 8).putInt(total);
        out.put(poolBytes);
        out.putShort((short) RES_XML_RESOURCE_MAP_TYPE).putShort((short) 8).putInt(mapSize);
        for (int id : pool.resIds) out.putInt(id);
        out.put(body.toByteArray());
        return out.array();
    }

    /**
     * Strings for attribute names that carry a resource id must come first in the pool,
     * one-to-one with the resource map. Everything else goes after them.
     */
    private static final class Pool {
        final ResStringPool strings = new ResStringPool(false);
        final List<Integer> resIds = new ArrayList<>();
        final Map<String, Integer> mapped = new LinkedHashMap<>();
        final Map<String, Integer> other = new HashMap<>();

        void addMapped(String name, int resId) {
            String key = resId + ":" + name;
            if (!mapped.containsKey(key)) {
                mapped.put(key, strings.add(name));
                resIds.add(resId);
            }
        }

        int mapped(String name, int resId) {
            return mapped.get(resId + ":" + name);
        }

        void addOther(String s) {
            if (s != null && !other.containsKey(s)) other.put(s, strings.add(s));
        }

        int idx(String s) {
            return s == null ? -1 : other.get(s);
        }
    }

    private static void collectMapped(List<Node> list, Pool p) {
        for (Node n : list) {
            if (n instanceof Element) {
                Element e = (Element) n;
                for (Attr a : e.attrs) if (a.resId != 0) p.addMapped(a.name, a.resId);
                collectMapped(e.children, p);
            }
        }
    }

    private static void collectOther(List<Node> list, Pool p) {
        for (Node n : list) {
            p.addOther(n.comment);
            if (n instanceof Namespace) {
                p.addOther(((Namespace) n).prefix);
                p.addOther(((Namespace) n).uri);
            } else if (n instanceof Text) {
                p.addOther(((Text) n).text);
                p.addOther(((Text) n).stringData);
            } else if (n instanceof Element) {
                Element e = (Element) n;
                p.addOther(e.ns);
                p.addOther(e.name);
                p.addOther(e.endComment);
                for (Attr a : e.attrs) {
                    p.addOther(a.ns);
                    if (a.resId == 0) p.addOther(a.name);
                    p.addOther(a.raw);
                    if (a.type == TYPE_STRING) p.addOther(a.stringData == null ? "" : a.stringData);
                }
                collectOther(e.children, p);
            }
        }
    }

    private static void writeNodes(List<Node> list, Pool p, ByteArrayOutputStream out) {
        for (Node n : list) {
            if (n instanceof Namespace) {
                Namespace ns = (Namespace) n;
                ByteBuffer b = node(ns.start ? START_NAMESPACE : END_NAMESPACE, 8, n.line, p.idx(n.comment));
                b.putInt(p.idx(ns.prefix)).putInt(p.idx(ns.uri));
                out.write(b.array(), 0, b.capacity());
            } else if (n instanceof Text) {
                Text t = (Text) n;
                ByteBuffer b = node(CDATA, 12, n.line, p.idx(n.comment));
                b.putInt(p.idx(t.text));
                b.putShort((short) 8).put((byte) 0).put((byte) t.type);
                b.putInt(t.type == TYPE_STRING ? p.idx(t.stringData) : t.data);
                out.write(b.array(), 0, b.capacity());
            } else if (n instanceof Element) {
                Element e = (Element) n;
                int count = e.attrs.size();
                ByteBuffer b = node(START_ELEMENT, 20 + 20 * count, n.line, p.idx(n.comment));
                int id = 0, cls = 0, style = 0;
                for (int i = 0; i < count; i++) {
                    Attr a = e.attrs.get(i);
                    if (a.ns != null) continue;
                    if ("id".equals(a.name)) id = i + 1;
                    else if ("class".equals(a.name)) cls = i + 1;
                    else if ("style".equals(a.name)) style = i + 1;
                }
                b.putInt(p.idx(e.ns)).putInt(p.idx(e.name));
                b.putShort((short) 20).putShort((short) 20).putShort((short) count);
                b.putShort((short) id).putShort((short) cls).putShort((short) style);
                for (Attr a : e.attrs) {
                    b.putInt(p.idx(a.ns));
                    b.putInt(a.resId != 0 ? p.mapped(a.name, a.resId) : p.idx(a.name));
                    b.putInt(p.idx(a.raw));
                    b.putShort((short) 8).put((byte) 0).put((byte) a.type);
                    b.putInt(a.type == TYPE_STRING ? p.idx(a.stringData == null ? "" : a.stringData) : a.data);
                }
                out.write(b.array(), 0, b.capacity());

                writeNodes(e.children, p, out);

                ByteBuffer end = node(END_ELEMENT, 8, e.endLine, p.idx(e.endComment));
                end.putInt(p.idx(e.ns)).putInt(p.idx(e.name));
                out.write(end.array(), 0, end.capacity());
            }
        }
    }

    private static ByteBuffer node(int type, int extSize, int line, int comment) {
        int size = 16 + extSize;
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) type).putShort((short) 16).putInt(size);
        b.putInt(line).putInt(comment);
        return b;
    }
}
