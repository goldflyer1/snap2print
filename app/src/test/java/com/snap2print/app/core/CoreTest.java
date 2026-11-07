package com.snap2print.app.core;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class CoreTest {

    private static void assertSize(String phrase, double mm, SizeRequest.Axis axis) {
        SizeRequest r = SizeParser.parse(phrase);
        assertNotNull("No size parsed from: " + phrase, r);
        assertEquals(phrase, mm, r.millimetres, 0.01);
        assertEquals(phrase, axis, r.axis);
    }

    @Test
    public void parsesEnglish() {
        assertSize("make it 8 centimeters tall", 80, SizeRequest.Axis.HEIGHT);
        assertSize("80 mm wide", 80, SizeRequest.Axis.WIDTH);
        assertSize("3 inches high", 76.2, SizeRequest.Axis.HEIGHT);
        assertSize("twelve centimetres long", 120, SizeRequest.Axis.LONGEST);
        assertSize("5.5 cm deep", 55, SizeRequest.Axis.DEPTH);
        assertSize("seven and a half centimeters tall", 75, SizeRequest.Axis.HEIGHT);
        assertSize("Make it 10 cm", 100, SizeRequest.Axis.LONGEST);
    }

    @Test
    public void parsesDutchAndFrench() {
        assertSize("7,5 cm hoog", 75, SizeRequest.Axis.HEIGHT);
        assertSize("maak het tien centimeter breed", 100, SizeRequest.Axis.WIDTH);
        assertSize("10 centimètres de haut", 100, SizeRequest.Axis.HEIGHT);
        assertSize("vijftig millimeter", 50, SizeRequest.Axis.LONGEST);
    }

    @Test
    public void unitlessGuess() {
        assertSize("8", 80, SizeRequest.Axis.LONGEST);
        assertSize("120 tall", 120, SizeRequest.Axis.HEIGHT);
    }

    @Test
    public void rejectsNoNumber() {
        assertNull(SizeParser.parse("make it big"));
        assertNull(SizeParser.parse(""));
    }

    private static final String CUBE_OBJ =
            "# unit cube, Y-up, 2 units tall\n" +
            "v 0 0 0\nv 1 0 0\nv 1 2 0\nv 0 2 0\nv 0 0 1\nv 1 0 1\nv 1 2 1\nv 0 2 1\n" +
            "f 1/1/1 2/2/2 3/3/3 4/4/4\nf 5 6 7 8\nf 1 2 6 5\nf 2 3 7 6\nf 3 4 8 7\nf -4 -8 -5 -1\n";

    @Test
    public void objToZUpAndFit() throws Exception {
        Mesh m = MeshIO.read(CUBE_OBJ.getBytes(StandardCharsets.UTF_8), "model.obj");
        assertEquals(12, m.triangleCount());
        Mesh z = MeshFit.yUpToZUp(m);
        float[] s = z.size();
        assertEquals(1f, s[0], 1e-5);
        assertEquals(1f, s[1], 1e-5);
        assertEquals(2f, s[2], 1e-5); // the 2-unit Y height is now Z

        Mesh fitted = MeshFit.fit(z, new SizeRequest(80, SizeRequest.Axis.HEIGHT));
        float[] b = fitted.bounds();
        assertEquals(0f, b[2], 1e-4);            // on the bed
        assertEquals(80f, b[5], 1e-3);           // 80 mm tall
        assertEquals(-20f, b[0], 1e-3);          // centred, uniform scale 40x
        assertEquals(20f, b[3], 1e-3);

        Mesh longest = MeshFit.fit(z, new SizeRequest(50, SizeRequest.Axis.LONGEST));
        assertEquals(50f, longest.size()[2], 1e-3);
    }

    @Test
    public void stlRoundTrip() throws Exception {
        Mesh m = MeshFit.fit(MeshFit.yUpToZUp(MeshIO.read(CUBE_OBJ.getBytes(StandardCharsets.UTF_8), "a.obj")),
                new SizeRequest(30, SizeRequest.Axis.WIDTH));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MeshIO.writeBinaryStl(m, out);
        byte[] bytes = out.toByteArray();
        assertEquals(84 + 12 * 50, bytes.length);
        Mesh back = MeshIO.read(bytes, "x.stl");
        assertArrayEquals(m.tris, back.tris, 0f);

        String ascii = "solid t\nfacet normal 0 0 1\nouter loop\nvertex 0 0 0\nvertex 1 0 0\nvertex 0 1 0\nendloop\nendfacet\nendsolid t\n";
        Mesh a = MeshIO.read(ascii.getBytes(StandardCharsets.US_ASCII), "t.stl");
        assertEquals(1, a.triangleCount());
    }
}
