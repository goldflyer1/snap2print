package com.snap2print.app.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Understands spoken or typed sizes in English, Dutch and French, e.g.
 * "make it 8 centimeters tall", "80 mm wide", "7,5 cm hoog", "3 inches", "10 centimètres de haut".
 * Without a unit, the number is taken as centimetres if it is below 30, otherwise as millimetres.
 * Without a dimension word, the size applies to the longest side.
 */
public final class SizeParser {
    private SizeParser() {}

    private static final Pattern NUMBER_UNIT = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(millimet(?:er|re|res|ers|res)?|millimètres?|mm|centimet(?:er|re|res|ers)?|centimètres?|cm|inch(?:es)?|in\\b|\"|meters?|metres?|mètres?|m\\b)?");

    public static SizeRequest parse(String input) {
        if (input == null) return null;
        String s = " " + input.toLowerCase(Locale.ROOT).replace("½", ".5") + " ";
        s = replaceNumberWords(s);
        s = s.replaceAll("(\\d)\\s+(?:and a half|en 1 half|et demi)", "$1.5");
        s = s.replaceAll("(\\d)\\s+(?:point|komma|virgule)\\s+(\\d)", "$1.$2");

        Matcher m = NUMBER_UNIT.matcher(s);
        if (!m.find()) return null;
        double value = Double.parseDouble(m.group(1).replace(',', '.'));
        String unit = m.group(2);
        double mm;
        if (unit == null) {
            mm = value < 30 ? value * 10 : value;
        } else if (unit.startsWith("mm") || unit.startsWith("milli")) {
            mm = value;
        } else if (unit.startsWith("cm") || unit.startsWith("centi")) {
            mm = value * 10;
        } else if (unit.startsWith("in") || unit.equals("\"")) {
            mm = value * 25.4;
        } else {
            mm = value * 1000; // metres
        }
        if (mm <= 0) return null;
        return new SizeRequest(round1(mm), axisOf(s));
    }

    private static SizeRequest.Axis axisOf(String s) {
        if (containsAny(s, " tall", " high", "height", " hoog", "hoogte", " haut", "hauteur")) return SizeRequest.Axis.HEIGHT;
        if (containsAny(s, " wide", "width", " breed", "breedte", " large", "largeur")) return SizeRequest.Axis.WIDTH;
        if (containsAny(s, " deep", "depth", " diep", "diepte", "profond")) return SizeRequest.Axis.DEPTH;
        if (containsAny(s, " long ", "length", " lang ", "lengte", "longueur")) return SizeRequest.Axis.LONGEST;
        return SizeRequest.Axis.LONGEST;
    }

    private static boolean containsAny(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private static final String[][] NUMBER_WORDS = {
            {"one", "1"}, {"two", "2"}, {"three", "3"}, {"four", "4"}, {"five", "5"}, {"six", "6"},
            {"seven", "7"}, {"eight", "8"}, {"nine", "9"}, {"ten", "10"}, {"eleven", "11"}, {"twelve", "12"},
            {"fifteen", "15"}, {"twenty", "20"}, {"thirty", "30"}, {"forty", "40"}, {"fifty", "50"}, {"hundred", "100"},
            {"een", "1"}, {"twee", "2"}, {"drie", "3"}, {"vier", "4"}, {"vijf", "5"}, {"zes", "6"},
            {"zeven", "7"}, {"acht", "8"}, {"negen", "9"}, {"tien", "10"}, {"twaalf", "12"}, {"vijftien", "15"}, {"twintig", "20"}, {"dertig", "30"}, {"veertig", "40"}, {"vijftig", "50"}, {"honderd", "100"},
            {"un", "1"}, {"deux", "2"}, {"trois", "3"}, {"quatre", "4"}, {"cinq", "5"}, {"sept", "7"},
            {"huit", "8"}, {"neuf", "9"}, {"dix", "10"}, {"douze", "12"}, {"quinze", "15"}, {"vingt", "20"}, {"trente", "30"}, {"quarante", "40"}, {"cinquante", "50"}, {"cent", "100"},
    };

    private static String replaceNumberWords(String s) {
        for (String[] w : NUMBER_WORDS) s = s.replaceAll("\\b" + w[0] + "\\b", w[1]);
        return s;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
