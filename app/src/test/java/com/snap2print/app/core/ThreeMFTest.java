package com.snap2print.app.core;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.Test;

public class ThreeMFTest {

    private static Map<String, String> unzip(byte[] data) throws Exception {
        Map<String, String> files = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) files.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
        }
        return files;
    }

    @Test
    public void writesValidClosedIndexedMesh() throws Exception {
        float[] knob = Shapes.revolve(new double[][]{{0, 0}, {12, 0}, {12, 5}, {8, 5}, {8, 15}, {0, 15}});
        Mesh m = MeshFit.fit(Shapes.combine(Arrays.asList(knob, Shapes.place(Shapes.box(4, 30, 3), 0, 0, 16, 0, 0, 0))),
                new SizeRequest(40, SizeRequest.Axis.HEIGHT));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ThreeMF.write(m, "knob & <grip>", out);
        Map<String, String> f = unzip(out.toByteArray());

        assertTrue(f.containsKey("[Content_Types].xml"));
        assertTrue(f.get("_rels/.rels").contains("/3D/3dmodel.model"));
        String model = f.get("3D/3dmodel.model");
        assertTrue(model.contains("unit=\"millimeter\""));
        assertTrue(model.contains("name=\"knob &amp; &lt;grip&gt;\""));
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(model.getBytes(StandardCharsets.UTF_8))); // well-formed XML

        Matcher v = Pattern.compile("<vertex x=\"([-0-9.]+)\" y=\"([-0-9.]+)\" z=\"([-0-9.]+)\"/>").matcher(model);
        int vertices = 0;
        double maxZ = 0;
        while (v.find()) { vertices++; maxZ = Math.max(maxZ, Double.parseDouble(v.group(3))); }
        assertEquals(40.0, maxZ, 1e-3);

        Matcher t = Pattern.compile("<triangle v1=\"(\\d+)\" v2=\"(\\d+)\" v3=\"(\\d+)\"/>").matcher(model);
        Map<String, Integer> edges = new HashMap<>();
        int triangles = 0;
        while (t.find()) {
            int[] ix = {Integer.parseInt(t.group(1)), Integer.parseInt(t.group(2)), Integer.parseInt(t.group(3))};
            for (int i : ix) assertTrue(i < vertices);
            for (int e = 0; e < 3; e++) edges.merge(ix[e] + ">" + ix[(e + 1) % 3], 1, Integer::sum);
            triangles++;
        }
        assertTrue("vertices must be shared, not repeated per triangle", vertices < triangles);
        // Each shape is closed on its own: every directed edge has its reverse.
        Set<String> open = new HashSet<>();
        for (String e : edges.keySet()) {
            String[] ab = e.split(">");
            if (!edges.containsKey(ab[1] + ">" + ab[0])) open.add(e);
        }
        assertTrue("open edges: " + open, open.isEmpty());
    }
}
