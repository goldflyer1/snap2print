package com.snap2print.app.core;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Reads STL (binary or ASCII) and OBJ, writes binary STL. No Android dependencies. */
public final class MeshIO {
    private MeshIO() {}

    // ---------- Reading ----------

    /** Detects the format from the bytes (and optional file name) and parses it. */
    public static Mesh read(byte[] data, String nameHint) throws IOException {
        String name = nameHint == null ? "" : nameHint.toLowerCase();
        if (name.endsWith(".obj")) return readObj(data);
        if (name.endsWith(".stl")) return readStl(data);
        // Guess: OBJ files contain "v " lines near the top.
        String head = new String(data, 0, Math.min(data.length, 512), StandardCharsets.US_ASCII);
        if (head.contains("\nv ") || head.startsWith("v ") || head.startsWith("#")) return readObj(data);
        return readStl(data);
    }

    public static Mesh readStl(byte[] data) throws IOException {
        if (data.length >= 84) {
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            long count = bb.getInt(80) & 0xFFFFFFFFL;
            if (84 + count * 50 == data.length) return readBinaryStl(bb, (int) count);
        }
        String head = new String(data, 0, Math.min(data.length, 256), StandardCharsets.US_ASCII).trim();
        if (head.startsWith("solid")) return readAsciiStl(data);
        throw new IOException("Not a valid STL file");
    }

    private static Mesh readBinaryStl(ByteBuffer bb, int count) {
        float[] t = new float[count * 9];
        int p = 84;
        for (int i = 0; i < count; i++) {
            p += 12; // skip normal
            for (int k = 0; k < 9; k++) {
                t[i * 9 + k] = bb.getFloat(p);
                p += 4;
            }
            p += 2; // attribute byte count
        }
        return new Mesh(t);
    }

    private static Mesh readAsciiStl(byte[] data) throws IOException {
        FloatList out = new FloatList(1 << 16);
        try (BufferedReader r = reader(data)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("vertex")) {
                    String[] p = line.split("\\s+");
                    if (p.length < 4) throw new IOException("Bad STL vertex line: " + line);
                    out.add(Float.parseFloat(p[1]));
                    out.add(Float.parseFloat(p[2]));
                    out.add(Float.parseFloat(p[3]));
                }
            }
        }
        if (out.size == 0 || out.size % 9 != 0) throw new IOException("ASCII STL has no complete triangles");
        return new Mesh(out.toArray());
    }

    public static Mesh readObj(byte[] data) throws IOException {
        FloatList verts = new FloatList(1 << 16);
        FloatList tris = new FloatList(1 << 17);
        int[] face = new int[64];
        try (BufferedReader r = reader(data)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() < 2) continue;
                char c0 = line.charAt(0), c1 = line.charAt(1);
                if (c0 == 'v' && (c1 == ' ' || c1 == '\t')) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length < 4) throw new IOException("Bad OBJ vertex line: " + line);
                    verts.add(Float.parseFloat(p[1]));
                    verts.add(Float.parseFloat(p[2]));
                    verts.add(Float.parseFloat(p[3]));
                } else if (c0 == 'f' && (c1 == ' ' || c1 == '\t')) {
                    String[] p = line.trim().split("\\s+");
                    int n = 0;
                    int vCount = verts.size / 3;
                    for (int i = 1; i < p.length; i++) {
                        String tok = p[i];
                        int slash = tok.indexOf('/');
                        int idx = Integer.parseInt(slash < 0 ? tok : tok.substring(0, slash));
                        idx = idx > 0 ? idx - 1 : vCount + idx; // OBJ is 1-based; negatives are relative
                        if (idx < 0 || idx >= vCount) throw new IOException("OBJ face refers to missing vertex: " + line);
                        if (n == face.length) face = java.util.Arrays.copyOf(face, n * 2);
                        face[n++] = idx;
                    }
                    for (int i = 1; i + 1 < n; i++) { // fan triangulation
                        addVertex(tris, verts, face[0]);
                        addVertex(tris, verts, face[i]);
                        addVertex(tris, verts, face[i + 1]);
                    }
                }
            }
        }
        if (tris.size == 0) throw new IOException("OBJ file has no faces");
        return new Mesh(tris.toArray());
    }

    private static void addVertex(FloatList tris, FloatList verts, int idx) {
        tris.add(verts.data[idx * 3]);
        tris.add(verts.data[idx * 3 + 1]);
        tris.add(verts.data[idx * 3 + 2]);
    }

    private static BufferedReader reader(byte[] data) {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8), 1 << 16);
    }

    // ---------- Writing ----------

    public static void writeBinaryStl(Mesh mesh, OutputStream out) throws IOException {
        float[] t = mesh.tris;
        int count = mesh.triangleCount();
        ByteBuffer bb = ByteBuffer.allocate(84 + count * 50).order(ByteOrder.LITTLE_ENDIAN);
        byte[] header = "Snap2Print binary STL, units: mm".getBytes(StandardCharsets.US_ASCII);
        bb.put(header, 0, Math.min(header.length, 80));
        bb.position(80);
        bb.putInt(count);
        for (int i = 0; i < count; i++) {
            int o = i * 9;
            float ux = t[o + 3] - t[o], uy = t[o + 4] - t[o + 1], uz = t[o + 5] - t[o + 2];
            float vx = t[o + 6] - t[o], vy = t[o + 7] - t[o + 1], vz = t[o + 8] - t[o + 2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 0) { nx /= len; ny /= len; nz /= len; }
            bb.putFloat(nx).putFloat(ny).putFloat(nz);
            for (int k = 0; k < 9; k++) bb.putFloat(t[o + k]);
            bb.putShort((short) 0);
        }
        out.write(bb.array());
        out.flush();
    }

    // ---------- Helpers ----------

    static final class FloatList {
        float[] data;
        int size;

        FloatList(int cap) { data = new float[cap]; }

        void add(float f) {
            if (size == data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            data[size++] = f;
        }

        float[] toArray() { return java.util.Arrays.copyOf(data, size); }
    }
}
