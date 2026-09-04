package com.overdrive.app.geo;

/**
 * Geohash encoder. Cache key precision lives in {@link #PRECISION_DEFAULT}.
 *
 * <p>Geohash7 ≈ 153 m × 153 m at the equator — appropriate for "this clip
 * happened in this district" rather than "this clip happened on this street."
 * Coarser hashes raise cache hit rate dramatically; finer ones produce
 * misleadingly precise place names that can disagree as the GPS jitters
 * across a hash-cell boundary while the car is parked.
 */
public final class Geohash {

    public static final int PRECISION_DEFAULT = 7;

    private static final char[] BASE32 = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'b', 'c', 'd', 'e', 'f', 'g',
            'h', 'j', 'k', 'm', 'n', 'p', 'q', 'r',
            's', 't', 'u', 'v', 'w', 'x', 'y', 'z'
    };

    private Geohash() {}

    public static String encode(double lat, double lng) {
        return encode(lat, lng, PRECISION_DEFAULT);
    }

    public static String encode(double lat, double lng, int precision) {
        if (precision < 1) precision = 1;
        if (precision > 12) precision = 12;

        double latLow = -90.0,  latHigh = 90.0;
        double lngLow = -180.0, lngHigh = 180.0;
        boolean even = true;
        int bit = 0, ch = 0;
        StringBuilder sb = new StringBuilder(precision);

        while (sb.length() < precision) {
            double mid;
            if (even) {
                mid = (lngLow + lngHigh) / 2.0;
                if (lng >= mid) { ch = (ch << 1) | 1; lngLow = mid; }
                else            { ch = (ch << 1);     lngHigh = mid; }
            } else {
                mid = (latLow + latHigh) / 2.0;
                if (lat >= mid) { ch = (ch << 1) | 1; latLow = mid; }
                else            { ch = (ch << 1);     latHigh = mid; }
            }
            even = !even;
            if (++bit == 5) {
                sb.append(BASE32[ch]);
                bit = 0;
                ch = 0;
            }
        }
        return sb.toString();
    }
}
