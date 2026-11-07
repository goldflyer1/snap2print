package com.snap2print.app.core;

/**
 * A triangle soup: every triangle is 9 floats (x1,y1,z1, x2,y2,z2, x3,y3,z3).
 * Units are whatever the source used until {@link MeshFit} scales it to millimetres.
 */
public final class Mesh {
    public final float[] tris;

    public Mesh(float[] tris) {
        if (tris.length % 9 != 0) throw new IllegalArgumentException("Triangle data must be a multiple of 9 floats");
        this.tris = tris;
    }

    public int triangleCount() {
        return tris.length / 9;
    }

    /** Returns {minX, minY, minZ, maxX, maxY, maxZ}. */
    public float[] bounds() {
        if (tris.length == 0) throw new IllegalStateException("Mesh is empty");
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < tris.length; i += 3) {
            float x = tris[i], y = tris[i + 1], z = tris[i + 2];
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }
        return new float[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** Returns {sizeX, sizeY, sizeZ}. */
    public float[] size() {
        float[] b = bounds();
        return new float[]{b[3] - b[0], b[4] - b[1], b[5] - b[2]};
    }
}
