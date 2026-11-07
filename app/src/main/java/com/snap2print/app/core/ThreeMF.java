package com.snap2print.app.core;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a standard 3MF model file (3MF Core spec): a zip with an indexed triangle mesh in millimetres.
 * Opens in Bambu Studio, OrcaSlicer, PrusaSlicer, Cura and MakerWorld. It holds the model only,
 * not print settings or G-code, so it still needs slicing before a printer can run it.
 */
public final class ThreeMF {
    private ThreeMF() {}

    public static void write(Mesh mesh, String objectName, OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);

        zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
        text(zip, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n"
                + " <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n"
                + " <Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/>\n"
                + "</Types>\n");
        zip.closeEntry();

        zip.putNextEntry(new ZipEntry("_rels/.rels"));
        text(zip, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n"
                + " <Relationship Target=\"/3D/3dmodel.model\" Id=\"rel0\" "
                + "Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/>\n"
                + "</Relationships>\n");
        zip.closeEntry();

        zip.putNextEntry(new ZipEntry("3D/3dmodel.model"));
        Writer w = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
        writeModel(mesh, objectName, w);
        w.flush();
        zip.closeEntry();
        zip.finish();
        out.flush();
    }

    private static void writeModel(Mesh mesh, String name, Writer w) throws IOException {
        float[] t = mesh.tris;
        // 3MF stores each corner once and refers to it by index. Corners are merged at the precision
        // written to the file (0.0001 mm), so shared edges stay shared and the mesh stays closed.
        Map<Key, Integer> index = new HashMap<>();
        StringBuilder vertices = new StringBuilder(t.length * 12);
        int[] tri = new int[t.length / 3];
        for (int i = 0; i < t.length; i += 3) {
            Key k = new Key(Math.round(t[i] * 1e4), Math.round(t[i + 1] * 1e4), Math.round(t[i + 2] * 1e4));
            Integer found = index.get(k);
            if (found == null) {
                found = index.size();
                index.put(k, found);
                vertices.append("     <vertex x=\"").append(num(k.x)).append("\" y=\"").append(num(k.y))
                        .append("\" z=\"").append(num(k.z)).append("\"/>\n");
            }
            tri[i / 3] = found;
        }

        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        w.write("<model unit=\"millimeter\" xml:lang=\"en-US\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">\n");
        w.write(" <metadata name=\"Application\">Snap2Print</metadata>\n");
        w.write(" <resources>\n");
        w.write("  <object id=\"1\" type=\"model\" name=\"" + xml(name) + "\">\n");
        w.write("   <mesh>\n    <vertices>\n");
        w.write(vertices.toString());
        w.write("    </vertices>\n    <triangles>\n");
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i + 2 < tri.length; i += 3) {
            int a = tri[i], b = tri[i + 1], c = tri[i + 2];
            if (a == b || b == c || a == c) continue; // zero-area triangle
            sb.setLength(0);
            sb.append("     <triangle v1=\"").append(a).append("\" v2=\"").append(b).append("\" v3=\"").append(c).append("\"/>\n");
            w.write(sb.toString());
        }
        w.write("    </triangles>\n   </mesh>\n  </object>\n");
        w.write(" </resources>\n <build>\n  <item objectid=\"1\"/>\n </build>\n</model>\n");
    }

    private static final class Key {
        final long x, y, z;

        Key(long x, long y, long z) { this.x = x; this.y = y; this.z = z; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return k.x == x && k.y == y && k.z == z;
        }

        @Override public int hashCode() { return Long.hashCode(x * 73856093L ^ y * 19349663L ^ z * 83492791L); }
    }

    /** Fixed-point value in 1/10000 mm as plain decimal text, e.g. 125000 -> "12.5". */
    private static String num(long v) {
        if (v == 0) return "0";
        String sign = v < 0 ? "-" : "";
        long a = Math.abs(v);
        String frac = String.format(Locale.ROOT, "%04d", a % 10000).replaceAll("0+$", "");
        return sign + (a / 10000) + (frac.isEmpty() ? "" : "." + frac);
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void text(ZipOutputStream zip, String s) throws IOException {
        zip.write(s.getBytes(StandardCharsets.UTF_8));
    }
}
