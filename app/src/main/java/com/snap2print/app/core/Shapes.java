package com.snap2print.app.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds closed, outward-facing triangle meshes from simple solid shapes, in millimetres, Z-up.
 * Every shape is created centred on the origin, then rotated (degrees, X then Y then Z) and moved to its centre.
 * Claude describes an object as a list of these shapes; overlapping shapes are merged by the slicer.
 */
public final class Shapes {
    private Shapes() {}

    public static final int SEGMENTS = 64;

    // ---------- Primitives ----------

    public static float[] box(double sx, double sy, double sz) {
        double x = sx / 2, y = sy / 2, z = sz / 2;
        double[][] v = {
                {-x, -y, -z}, {x, -y, -z}, {x, y, -z}, {-x, y, -z},
                {-x, -y, z}, {x, -y, z}, {x, y, z}, {-x, y, z}};
        int[][] f = {
                {0, 2, 1}, {0, 3, 2}, // bottom
                {4, 5, 6}, {4, 6, 7}, // top
                {0, 1, 5}, {0, 5, 4}, // front (-y)
                {1, 2, 6}, {1, 6, 5}, // right (+x)
                {2, 3, 7}, {2, 7, 6}, // back (+y)
                {3, 0, 4}, {3, 4, 7}};// left (-x)
        float[] t = new float[f.length * 9];
        int p = 0;
        for (int[] tri : f) for (int i : tri) for (int k = 0; k < 3; k++) t[p++] = (float) v[i][k];
        return t;
    }

    public static float[] cylinder(double radius, double height) {
        return cone(radius, radius, height);
    }

    /** Cone or frustum along Z. Either radius may be 0 for a point. */
    public static float[] cone(double radiusBottom, double radiusTop, double height) {
        double h = height / 2;
        return revolve(new double[][]{{0, -h}, {radiusBottom, -h}, {radiusTop, h}, {0, h}});
    }

    /** Hollow cylinder (pipe, ring, washer, bushing) along Z. */
    public static float[] tube(double outerRadius, double innerRadius, double height) {
        if (innerRadius <= 0 || innerRadius >= outerRadius) return cylinder(outerRadius, height);
        double h = height / 2;
        return revolve(new double[][]{{innerRadius, -h}, {outerRadius, -h}, {outerRadius, h}, {innerRadius, h}});
    }

    public static float[] sphere(double radius) {
        int n = SEGMENTS / 2;
        double[][] prof = new double[n + 1][];
        for (int i = 0; i <= n; i++) {
            double a = -Math.PI / 2 + Math.PI * i / n;
            prof[i] = new double[]{i == 0 || i == n ? 0 : radius * Math.cos(a), radius * Math.sin(a)};
        }
        return revolve(prof);
    }

    /**
     * Turns a closed 2D profile of (radius, z) points around the Z axis, like a lathe.
     * Good for knobs, bottles, caps, pulleys, cups (include the inner wall in the profile).
     */
    public static float[] revolve(double[][] profile) {
        List<double[]> pts = new ArrayList<>();
        for (double[] p : profile) {
            if (p.length < 2) throw new IllegalArgumentException("Profile points need [radius, z]");
            double r = Math.max(0, p[0]);
            if (!pts.isEmpty()) {
                double[] last = pts.get(pts.size() - 1);
                if (Math.abs(last[0] - r) < 1e-9 && Math.abs(last[1] - p[1]) < 1e-9) continue;
            }
            pts.add(new double[]{r, p[1]});
        }
        if (pts.size() > 1) {
            double[] a = pts.get(0), b = pts.get(pts.size() - 1);
            if (Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9) pts.remove(pts.size() - 1);
        }
        if (pts.size() < 3) throw new IllegalArgumentException("A revolve profile needs at least 3 points");

        int n = SEGMENTS;
        double[] cos = new double[n + 1], sin = new double[n + 1];
        for (int j = 0; j <= n; j++) {
            double a = 2 * Math.PI * j / n;
            cos[j] = j == n ? 1 : Math.cos(a);
            sin[j] = j == n ? 0 : Math.sin(a);
        }
        FloatOut out = new FloatOut();
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i), q = pts.get((i + 1) % pts.size());
            if (p[0] == 0 && q[0] == 0) continue; // edge on the axis sweeps no surface
            for (int j = 0; j < n; j++) {
                double[] p0 = rev(p, cos[j], sin[j]), p1 = rev(p, cos[j + 1], sin[j + 1]);
                double[] q0 = rev(q, cos[j], sin[j]), q1 = rev(q, cos[j + 1], sin[j + 1]);
                if (p[0] == 0) out.tri(p0, q1, q0);
                else if (q[0] == 0) out.tri(p0, p1, q0);
                else { out.tri(p0, p1, q1); out.tri(p0, q1, q0); }
            }
        }
        return outward(out.toArray());
    }

    /** Extrudes a closed 2D outline (x, y points, any winding, no self-crossings) straight up by [height]. */
    public static float[] extrude(double[][] polygon, double height) {
        List<double[]> pts = new ArrayList<>();
        for (double[] p : polygon) {
            if (p.length < 2) throw new IllegalArgumentException("Outline points need [x, y]");
            if (!pts.isEmpty()) {
                double[] last = pts.get(pts.size() - 1);
                if (Math.abs(last[0] - p[0]) < 1e-9 && Math.abs(last[1] - p[1]) < 1e-9) continue;
            }
            pts.add(new double[]{p[0], p[1]});
        }
        if (pts.size() > 1) {
            double[] a = pts.get(0), b = pts.get(pts.size() - 1);
            if (Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9) pts.remove(pts.size() - 1);
        }
        if (pts.size() < 3) throw new IllegalArgumentException("An outline needs at least 3 points");
        if (signedArea(pts) < 0) java.util.Collections.reverse(pts); // make counter-clockwise

        double lo = -height / 2, hi = height / 2;
        FloatOut out = new FloatOut();
        for (int[] t : earClip(pts)) {
            double[] a = pts.get(t[0]), b = pts.get(t[1]), c = pts.get(t[2]);
            out.tri(new double[]{a[0], a[1], hi}, new double[]{b[0], b[1], hi}, new double[]{c[0], c[1], hi});
            out.tri(new double[]{a[0], a[1], lo}, new double[]{c[0], c[1], lo}, new double[]{b[0], b[1], lo});
        }
        for (int i = 0; i < pts.size(); i++) {
            double[] a = pts.get(i), b = pts.get((i + 1) % pts.size());
            double[] a0 = {a[0], a[1], lo}, b0 = {b[0], b[1], lo}, a1 = {a[0], a[1], hi}, b1 = {b[0], b[1], hi};
            out.tri(a0, b0, b1);
            out.tri(a0, b1, a1);
        }
        return outward(out.toArray());
    }

    // ---------- Placement ----------

    /** Rotates (degrees, applied X, then Y, then Z) and then moves the shape to [cx, cy, cz]. */
    public static float[] place(float[] t, double cx, double cy, double cz, double rxDeg, double ryDeg, double rzDeg) {
        double ax = Math.toRadians(rxDeg), ay = Math.toRadians(ryDeg), az = Math.toRadians(rzDeg);
        double cxr = Math.cos(ax), sxr = Math.sin(ax), cyr = Math.cos(ay), syr = Math.sin(ay), czr = Math.cos(az), szr = Math.sin(az);
        float[] o = new float[t.length];
        for (int i = 0; i < t.length; i += 3) {
            double x = t[i], y = t[i + 1], z = t[i + 2];
            double y1 = y * cxr - z * sxr, z1 = y * sxr + z * cxr;            // X
            double x2 = x * cyr + z1 * syr, z2 = -x * syr + z1 * cyr;         // Y
            double x3 = x2 * czr - y1 * szr, y3 = x2 * szr + y1 * czr;        // Z
            o[i] = (float) (x3 + cx);
            o[i + 1] = (float) (y3 + cy);
            o[i + 2] = (float) (z2 + cz);
        }
        return o;
    }

    public static Mesh combine(List<float[]> parts) {
        int n = 0;
        for (float[] p : parts) n += p.length;
        float[] all = new float[n];
        int o = 0;
        for (float[] p : parts) { System.arraycopy(p, 0, all, o, p.length); o += p.length; }
        return new Mesh(all);
    }

    // ---------- Geometry helpers ----------

    /** Signed volume (divergence theorem); positive when triangles face outward. */
    public static double signedVolume(float[] t) {
        double v = 0;
        for (int i = 0; i < t.length; i += 9) {
            v += t[i] * (t[i + 4] * (double) t[i + 8] - t[i + 5] * (double) t[i + 7])
                    - t[i + 1] * (t[i + 3] * (double) t[i + 8] - t[i + 5] * (double) t[i + 6])
                    + t[i + 2] * (t[i + 3] * (double) t[i + 7] - t[i + 4] * (double) t[i + 6]);
        }
        return v / 6.0;
    }

    private static float[] outward(float[] t) {
        if (signedVolume(t) < 0) {
            for (int i = 0; i < t.length; i += 9) {
                for (int k = 0; k < 3; k++) {
                    float tmp = t[i + 3 + k];
                    t[i + 3 + k] = t[i + 6 + k];
                    t[i + 6 + k] = tmp;
                }
            }
        }
        return t;
    }

    private static double[] rev(double[] p, double c, double s) {
        return new double[]{p[0] * c, p[0] * s, p[1]};
    }

    private static double signedArea(List<double[]> p) {
        double a = 0;
        for (int i = 0; i < p.size(); i++) {
            double[] u = p.get(i), w = p.get((i + 1) % p.size());
            a += u[0] * w[1] - w[0] * u[1];
        }
        return a / 2;
    }

    /** Ear clipping for a simple counter-clockwise polygon. */
    private static List<int[]> earClip(List<double[]> p) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < p.size(); i++) idx.add(i);
        List<int[]> out = new ArrayList<>();
        int guard = 0;
        while (idx.size() > 3 && guard++ < 10000) {
            boolean clipped = false;
            for (int i = 0; i < idx.size(); i++) {
                int ia = idx.get((i + idx.size() - 1) % idx.size()), ib = idx.get(i), ic = idx.get((i + 1) % idx.size());
                double[] a = p.get(ia), b = p.get(ib), c = p.get(ic);
                if (cross(a, b, c) <= 1e-12) continue; // reflex or flat
                boolean inside = false;
                for (int j : idx) {
                    if (j == ia || j == ib || j == ic) continue;
                    if (inTriangle(p.get(j), a, b, c)) { inside = true; break; }
                }
                if (inside) continue;
                out.add(new int[]{ia, ib, ic});
                idx.remove(i);
                clipped = true;
                break;
            }
            if (!clipped) { // self-crossing or degenerate outline: fall back to a fan so we still get a solid
                for (int i = 1; i + 1 < idx.size(); i++) out.add(new int[]{idx.get(0), idx.get(i), idx.get(i + 1)});
                return out;
            }
        }
        if (idx.size() == 3) out.add(new int[]{idx.get(0), idx.get(1), idx.get(2)});
        return out;
    }

    private static double cross(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    private static boolean inTriangle(double[] p, double[] a, double[] b, double[] c) {
        return cross(a, b, p) >= 0 && cross(b, c, p) >= 0 && cross(c, a, p) >= 0;
    }

    private static final class FloatOut {
        float[] d = new float[1024];
        int n;

        void tri(double[] a, double[] b, double[] c) {
            if (n + 9 > d.length) d = java.util.Arrays.copyOf(d, d.length * 2);
            for (double[] v : new double[][]{a, b, c}) {
                d[n++] = (float) v[0];
                d[n++] = (float) v[1];
                d[n++] = (float) v[2];
            }
        }

        float[] toArray() { return java.util.Arrays.copyOf(d, n); }
    }
}
