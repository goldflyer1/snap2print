package com.snap2print.app.core;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class ShapesTest {

    /** Every directed edge must be matched by exactly one opposite edge: closed and consistently oriented. */
    static void assertWatertight(String name, float[] t) {
        Map<String, Integer> edges = new HashMap<>();
        for (int i = 0; i < t.length; i += 9) {
            for (int e = 0; e < 3; e++) {
                int a = i + e * 3, b = i + ((e + 1) % 3) * 3;
                String key = key(t, a) + ">" + key(t, b);
                edges.merge(key, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> en : edges.entrySet()) {
            String[] ab = en.getKey().split(">");
            Integer back = edges.get(ab[1] + ">" + ab[0]);
            assertEquals(name + ": edge used more than once in one direction", 1, (int) en.getValue());
            assertNotNull(name + ": open edge " + en.getKey(), back);
        }
        assertTrue(name + ": volume must be positive (outward faces)", Shapes.signedVolume(t) > 0);
    }

    private static String key(float[] t, int o) {
        return Math.round(t[o] * 1e4) + "," + Math.round(t[o + 1] * 1e4) + "," + Math.round(t[o + 2] * 1e4);
    }

    @Test
    public void primitivesAreClosedWithCorrectVolume() {
        float[] box = Shapes.box(10, 20, 30);
        assertWatertight("box", box);
        assertEquals(6000, Shapes.signedVolume(box), 1e-3);

        float[] cyl = Shapes.cylinder(5, 10);
        assertWatertight("cylinder", cyl);
        assertEquals(Math.PI * 25 * 10, Shapes.signedVolume(cyl), 785.4 * 0.01); // 64-gon ≈ circle within 1%

        float[] cone = Shapes.cone(5, 0, 12);
        assertWatertight("cone", cone);
        assertEquals(Math.PI * 25 * 12 / 3, Shapes.signedVolume(cone), 314.2 * 0.01);

        float[] tube = Shapes.tube(10, 6, 5);
        assertWatertight("tube", tube);
        assertEquals(Math.PI * (100 - 36) * 5, Shapes.signedVolume(tube), 1005.3 * 0.01);

        float[] sphere = Shapes.sphere(10);
        assertWatertight("sphere", sphere);
        assertEquals(4.0 / 3 * Math.PI * 1000, Shapes.signedVolume(sphere), 4188.8 * 0.02);
    }

    @Test
    public void revolveWithAnyWindingAndCupProfile() {
        // Knob: base disc + narrower grip, profile given clockwise
        double[][] knob = {{0, 0}, {0, 15}, {8, 15}, {8, 5}, {12, 5}, {12, 0}};
        assertWatertight("knob", Shapes.revolve(knob));
        // Cup with a 2 mm wall and 2 mm floor
        double[][] cup = {{0, 0}, {20, 0}, {20, 30}, {18, 30}, {18, 2}, {0, 2}};
        float[] c = Shapes.revolve(cup);
        assertWatertight("cup", c);
        double expected = Math.PI * (400 * 30 - 324 * 28);
        assertEquals(expected, Shapes.signedVolume(c), expected * 0.01);
    }

    @Test
    public void extrudeConcaveOutline() {
        // L-shaped bracket outline, clockwise on purpose
        double[][] l = {{0, 0}, {0, 30}, {5, 30}, {5, 5}, {20, 5}, {20, 0}};
        float[] e = Shapes.extrude(l, 4);
        assertWatertight("L-extrude", e);
        assertEquals((5 * 30 + 15 * 5) * 4, Shapes.signedVolume(e), 1e-2);
    }

    @Test
    public void placeRotatesAndMoves() {
        float[] cyl = Shapes.place(Shapes.cylinder(5, 40), 10, 0, 5, 0, 90, 0); // lying along X
        Mesh m = new Mesh(cyl);
        float[] s = m.size();
        assertEquals(40, s[0], 1e-3);
        assertEquals(10, s[2], 1e-3);
        float[] b = m.bounds();
        assertEquals(-10, b[0], 1e-3);
        assertEquals(0, b[2], 1e-3);
        assertWatertight("placed", cyl);

        Mesh all = Shapes.combine(Arrays.asList(Shapes.box(10, 10, 10), cyl));
        assertEquals(12 + cyl.length / 9, all.triangleCount());
    }
}
