package de.dennisguse.opentracks.analytics;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes heart rate zone boundaries for three models:
 *   A - Percentage of MaxHR (5 zones)
 *   B - Karvonen / Heart Rate Reserve (5 zones)
 *   C - LTHR-Based Friel (7 zones: 1, 2, 3, 4, 5a, 5b, 5c)
 *
 * Also computes time-in-zone from a list of (hr, durationSeconds) data points.
 */
public class HeartRateZoneCalculator {

    public static final String MODEL_MAX_HR_PERCENT = "max_hr_percent";
    public static final String MODEL_KARVONEN = "karvonen";
    public static final String MODEL_LTHR_FRIEL = "lthr_friel";

    public static final int ZONES_COUNT_STANDARD = 5;
    public static final int ZONES_COUNT_FRIEL = 7;

    private final String model;
    private final int maxHR;
    private final int rhr;
    private final int lthr;

    // Computed boundaries: lowerBound[i] is the minimum HR (inclusive) for zone i
    private final int[] lowerBounds;
    private final String[] zoneNames;

    public HeartRateZoneCalculator(String model, int maxHR, int rhr, int lthr) {
        this.model = model;
        this.maxHR = maxHR;
        this.rhr = rhr;
        this.lthr = lthr;

        if (MODEL_LTHR_FRIEL.equals(model)) {
            lowerBounds = computeFreilBounds();
            zoneNames = new String[]{"Z1", "Z2", "Z3", "Z4", "Z5a", "Z5b", "Z5c"};
        } else if (MODEL_KARVONEN.equals(model)) {
            lowerBounds = computeKarvonenBounds();
            zoneNames = new String[]{"Z1", "Z2", "Z3", "Z4", "Z5"};
        } else {
            lowerBounds = computeMaxHrPercentBounds();
            zoneNames = new String[]{"Z1", "Z2", "Z3", "Z4", "Z5"};
        }
    }

    /** Returns the zone index (0-based) for the given heart rate. */
    public int getZone(int hr) {
        int zone = 0;
        for (int i = 1; i < lowerBounds.length; i++) {
            if (hr >= lowerBounds[i]) zone = i;
        }
        return zone;
    }

    public int getZoneCount() {
        return lowerBounds.length;
    }

    public String getZoneName(int zoneIndex) {
        if (zoneIndex >= 0 && zoneIndex < zoneNames.length) return zoneNames[zoneIndex];
        return "Z?";
    }

    /** Lower bound (bpm) for a zone. */
    public int getLowerBound(int zoneIndex) {
        return lowerBounds[zoneIndex];
    }

    /** Upper bound (bpm, exclusive) for a zone. */
    public int getUpperBound(int zoneIndex) {
        if (zoneIndex + 1 < lowerBounds.length) return lowerBounds[zoneIndex + 1];
        return maxHR + 50; // no upper cap on top zone
    }

    /**
     * Given a list of (heartRate, durationSeconds) pairs, returns seconds spent in each zone.
     * The returned array has getZoneCount() entries.
     */
    public long[] computeTimeInZones(List<int[]> hrDurationPairs) {
        long[] result = new long[getZoneCount()];
        for (int[] pair : hrDurationPairs) {
            int hr = pair[0];
            int dt = pair[1];
            if (hr > 0 && dt > 0) {
                result[getZone(hr)] += dt;
            }
        }
        return result;
    }

    // ---- Zone boundary computations ----

    private int[] computeMaxHrPercentBounds() {
        return new int[]{
                (int) (maxHR * 0.50),
                (int) (maxHR * 0.60),
                (int) (maxHR * 0.70),
                (int) (maxHR * 0.80),
                (int) (maxHR * 0.90)
        };
    }

    private int[] computeKarvonenBounds() {
        int hrr = maxHR - rhr;
        return new int[]{
                rhr + (int) (hrr * 0.50),
                rhr + (int) (hrr * 0.60),
                rhr + (int) (hrr * 0.70),
                rhr + (int) (hrr * 0.80),
                rhr + (int) (hrr * 0.90)
        };
    }

    private int[] computeFreilBounds() {
        return new int[]{
                0,                              // Z1: below 85% LTHR
                (int) (lthr * 0.85),            // Z2: 85-89%
                (int) (lthr * 0.90),            // Z3: 90-94%
                (int) (lthr * 0.95),            // Z4: 95-99%
                lthr,                           // Z5a: 100-102%
                (int) (lthr * 1.03),            // Z5b: 103-106%
                (int) (lthr * 1.06)             // Z5c: above 106%
        };
    }
}
