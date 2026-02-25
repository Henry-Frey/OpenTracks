package de.dennisguse.opentracks.analytics;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import de.dennisguse.opentracks.data.ContentProviderUtils;
import de.dennisguse.opentracks.data.TrackPointIterator;
import de.dennisguse.opentracks.data.models.HeartRate;
import de.dennisguse.opentracks.data.models.Speed;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.data.models.TrackPoint;
import de.dennisguse.opentracks.settings.PreferencesUtils;

/**
 * Computes per-activity analytics: time-in-zones, aerobic decoupling, suffer score, VO2max.
 * Results are stored as a plain data class to pass to the UI.
 */
public class ActivityAnalytics {

    public final long[] timeInZoneSeconds;  // seconds per zone
    public final double aerobicDecouplingPercent; // < 5% = aerobically efficient
    public final double sufferScore;
    public final double vo2maxEstimate; // 0 if not computable
    public final boolean hasHeartRateData;
    public final int zoneCount;

    private ActivityAnalytics(long[] timeInZone, double decoupling, double suffer,
                               double vo2max, boolean hasHR) {
        this.timeInZoneSeconds = timeInZone;
        this.aerobicDecouplingPercent = decoupling;
        this.sufferScore = suffer;
        this.vo2maxEstimate = vo2max;
        this.hasHeartRateData = hasHR;
        this.zoneCount = timeInZone.length;
    }

    public static ActivityAnalytics compute(Context context, Track.Id trackId) {
        int maxHR = PreferencesUtils.getFitnessEffectiveMaxHeartRate();
        int rhr = PreferencesUtils.getFitnessRestingHeartRate();
        int lthr = PreferencesUtils.getFitnessEffectiveLTHR();
        String zoneModel = PreferencesUtils.getFitnessZoneModel();

        HeartRateZoneCalculator zoneCalc = new HeartRateZoneCalculator(zoneModel, maxHR, rhr, lthr);

        ContentProviderUtils utils = new ContentProviderUtils(context);
        Track track = utils.getTrack(trackId);

        long totalDurationMs = track != null ? track.getTrackStatistics().getTotalTime().toMillis() : 0;

        List<int[]> hrDtPairs = new ArrayList<>();
        List<Double> firstHalfHR = new ArrayList<>();
        List<Double> firstHalfSpeed = new ArrayList<>();
        List<Double> secondHalfHR = new ArrayList<>();
        List<Double> secondHalfSpeed = new ArrayList<>();

        boolean hasHR = false;
        double sumHR = 0;
        double sumSpeed = 0;
        int count = 0;
        double vo2maxSum = 0;
        int vo2Count = 0;

        long startMs = -1;

        try (TrackPointIterator it = utils.getTrackPointLocationIterator(trackId, null)) {
            long prevMs = -1;
            while (it.hasNext()) {
                TrackPoint tp = it.next();
                if (tp.getTime() == null) continue;

                long tMs = tp.getTime().toEpochMilli();
                if (startMs < 0) startMs = tMs;

                HeartRate hr = tp.getHeartRate();
                Speed speed = tp.getSpeed();

                if (prevMs > 0 && hr != null && hr.getBPM() > 0) {
                    hasHR = true;
                    int dt = (int) ((tMs - prevMs) / 1000);
                    hrDtPairs.add(new int[]{hr.getBPM(), dt});

                    // split halves
                    long elapsed = tMs - startMs;
                    if (elapsed <= totalDurationMs / 2) {
                        firstHalfHR.add((double) hr.getBPM());
                        if (speed != null) firstHalfSpeed.add(speed.toMPS());
                    } else {
                        secondHalfHR.add((double) hr.getBPM());
                        if (speed != null) secondHalfSpeed.add(speed.toMPS());
                    }

                    // VO2max from steady-state runs (any point with speed + HR)
                    if (speed != null && speed.toMPS() > 0.5) {
                        double pctMaxHR = (double) hr.getBPM() / maxHR;
                        if (pctMaxHR > 0.37) {
                            double pctVo2 = (pctMaxHR - 0.37) / 0.64;
                            double speedMpm = speed.toMPS() * 60; // m/min
                            double actualVo2 = speedMpm * 0.2 + 3.5;
                            if (pctVo2 > 0) {
                                vo2maxSum += actualVo2 / pctVo2;
                                vo2Count++;
                            }
                        }
                    }
                }
                prevMs = tMs;
            }
        }

        long[] timeInZone = zoneCalc.computeTimeInZones(hrDtPairs);

        // Aerobic decoupling
        double decoupling = 0;
        if (!firstHalfHR.isEmpty() && !secondHalfHR.isEmpty()
                && !firstHalfSpeed.isEmpty() && !secondHalfSpeed.isEmpty()) {
            double avgHR1 = mean(firstHalfHR);
            double avgHR2 = mean(secondHalfHR);
            double avgSpd1 = mean(firstHalfSpeed);
            double avgSpd2 = mean(secondHalfSpeed);
            if (avgHR1 > 0 && avgHR2 > 0) {
                double eff1 = avgSpd1 / avgHR1;
                double eff2 = avgSpd2 / avgHR2;
                if (eff1 > 0) {
                    decoupling = ((eff1 - eff2) / eff1) * 100.0;
                }
            }
        }

        // Suffer score: zone weight × time_minutes
        int[] zoneWeights = {1, 2, 3, 4, 8};
        double suffer = 0;
        int zCount = Math.min(timeInZone.length, zoneWeights.length);
        for (int i = 0; i < zCount; i++) {
            suffer += (timeInZone[i] / 60.0) * zoneWeights[i];
        }

        double vo2max = vo2Count > 0 ? vo2maxSum / vo2Count : 0;

        return new ActivityAnalytics(timeInZone, decoupling, suffer, vo2max, hasHR);
    }

    private static double mean(List<Double> list) {
        if (list.isEmpty()) return 0;
        double s = 0;
        for (double v : list) s += v;
        return s / list.size();
    }
}
