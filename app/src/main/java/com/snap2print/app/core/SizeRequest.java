package com.snap2print.app.core;

import java.util.Locale;

/** "Make it N millimetres along this axis." */
public final class SizeRequest {
    public enum Axis { HEIGHT, WIDTH, DEPTH, LONGEST }

    public final double millimetres;
    public final Axis axis;

    public SizeRequest(double millimetres, Axis axis) {
        this.millimetres = millimetres;
        this.axis = axis;
    }

    public String describe() {
        String what;
        switch (axis) {
            case HEIGHT: what = "tall"; break;
            case WIDTH: what = "wide"; break;
            case DEPTH: what = "deep"; break;
            default: what = "on its longest side";
        }
        String mm = millimetres == Math.rint(millimetres)
                ? String.format(Locale.US, "%d", (long) millimetres)
                : String.format(Locale.US, "%.1f", millimetres);
        return mm + " mm " + what;
    }

    @Override
    public String toString() { return describe(); }
}
