package com.snap2print.app.core;

/** Turns a generated model into a print-ready mesh: Z-up, exact size in mm, centred, resting on the bed. */
public final class MeshFit {
    private MeshFit() {}

    /**
     * AI generators export glTF-style "Y-up" models; printers and slicers use "Z-up".
     * Maps (x, y, z) to (x, -z, y), a +90° rotation around the X axis.
     */
    public static Mesh yUpToZUp(Mesh m) {
        float[] s = m.tris;
        float[] t = new float[s.length];
        for (int i = 0; i < s.length; i += 3) {
            t[i] = s[i];
            t[i + 1] = -s[i + 2];
            t[i + 2] = s[i + 1];
        }
        return new Mesh(t);
    }

    /**
     * Scales uniformly so the requested dimension becomes {@code targetMm}, then centres the model
     * on X/Y and places its lowest point at Z = 0.
     */
    public static Mesh fit(Mesh m, SizeRequest req) {
        float[] size = m.size();
        int axis;
        switch (req.axis) {
            case WIDTH: axis = 0; break;
            case DEPTH: axis = 1; break;
            case HEIGHT: axis = 2; break;
            default: axis = size[0] >= size[1] && size[0] >= size[2] ? 0 : (size[1] >= size[2] ? 1 : 2);
        }
        float current = size[axis];
        if (current <= 0) throw new IllegalStateException("Model has zero size along the chosen axis");
        float k = (float) (req.millimetres / current);

        float[] b = m.bounds();
        float cx = (b[0] + b[3]) / 2f, cy = (b[1] + b[4]) / 2f, minZ = b[2];
        float[] s = m.tris;
        float[] t = new float[s.length];
        for (int i = 0; i < s.length; i += 3) {
            t[i] = (s[i] - cx) * k;
            t[i + 1] = (s[i + 1] - cy) * k;
            t[i + 2] = (s[i + 2] - minZ) * k;
        }
        return new Mesh(t);
    }
}
