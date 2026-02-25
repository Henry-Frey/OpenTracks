package de.dennisguse.opentracks.analytics;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import de.dennisguse.opentracks.data.ContentProviderUtils;
import de.dennisguse.opentracks.data.TrackPointIterator;
import de.dennisguse.opentracks.data.models.HeartRate;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.data.models.TrackPoint;
import de.dennisguse.opentracks.data.tables.TracksColumns;
import de.dennisguse.opentracks.settings.PreferencesUtils;

/**
 * Computes TRIMP, hrTSS, and the CTL/ATL/TSB fitness model.
 */
public class TrainingLoadCalculator {

    /** Compute and persist TRIMP + hrTSS for a single track. */
    public static void computeAndSaveForTrack(Context context, Track.Id trackId) {
        ContentProviderUtils utils = new ContentProviderUtils(context);
        Track track = utils.getTrack(trackId);
        if (track == null) return;

        int maxHR = PreferencesUtils.getFitnessEffectiveMaxHeartRate();
        int rhr = PreferencesUtils.getFitnessRestingHeartRate();
        int lthr = PreferencesUtils.getFitnessEffectiveLTHR();
        boolean isMale = PreferencesUtils.isFitnessGenderMale();
        double k = isMale ? 1.92 : 1.67;

        double totalTrimp = 0;
        double totalHrTss = 0;

        long prevTimeMs = -1;

        try (TrackPointIterator it = utils.getTrackPointLocationIterator(trackId, null)) {
            while (it.hasNext()) {
                TrackPoint tp = it.next();
                if (tp.getTime() == null) continue;

                long timeMs = tp.getTime().toEpochMilli();
                HeartRate hr = tp.getHeartRate();

                if (prevTimeMs > 0 && hr != null && hr.getBPM() > 0) {
                    double dtMin = (timeMs - prevTimeMs) / 60000.0;
                    double dtHr = dtMin / 60.0;
                    double hrVal = hr.getBPM();

                    // TRIMP
                    double hrr = (double) maxHR - rhr;
                    if (hrr > 0) {
                        double ratio = (hrVal - rhr) / hrr;
                        ratio = Math.max(0, Math.min(1, ratio));
                        totalTrimp += dtMin * ratio * Math.exp(k * ratio);
                    }

                    // hrTSS
                    if (lthr > 0) {
                        double intensityFactor = hrVal / lthr;
                        totalHrTss += dtHr * intensityFactor * intensityFactor * 100.0;
                    }
                }
                prevTimeMs = timeMs;
            }
        }

        // Persist back to DB
        ContentValues cv = new ContentValues();
        cv.put(TracksColumns.TRIMP, totalTrimp);
        cv.put(TracksColumns.HRTSS, totalHrTss);
        context.getContentResolver().update(TracksColumns.CONTENT_URI, cv,
                TracksColumns._ID + "=?", new String[]{String.valueOf(trackId.id())});
    }

    // ---- CTL / ATL / TSB model ----

    public static class DayLoad {
        public final LocalDate date;
        public final double load; // daily TSS/TRIMP
        public double ctl;
        public double atl;
        public double tsb;

        public DayLoad(LocalDate date, double load) {
            this.date = date;
            this.load = load;
        }
    }

    /**
     * Build the full CTL/ATL/TSB history from all stored track data.
     * Uses TRIMP as load metric (falls back to hrTSS if TRIMP is 0 for a track).
     */
    public static List<DayLoad> buildFitnessHistory(Context context) {
        ContentProviderUtils utils = new ContentProviderUtils(context);

        // 1. Aggregate daily load from tracks that have TRIMP/hrTSS stored
        TreeMap<LocalDate, Double> dailyLoad = new TreeMap<>();

        try (Cursor cursor = context.getContentResolver().query(
                TracksColumns.CONTENT_URI,
                new String[]{TracksColumns.STARTTIME, TracksColumns.TRIMP, TracksColumns.HRTSS},
                null, null, TracksColumns.STARTTIME + " ASC")) {

            if (cursor != null) {
                int timeIdx = cursor.getColumnIndexOrThrow(TracksColumns.STARTTIME);
                int trimpIdx = cursor.getColumnIndexOrThrow(TracksColumns.TRIMP);
                int hrTssIdx = cursor.getColumnIndexOrThrow(TracksColumns.HRTSS);

                while (cursor.moveToNext()) {
                    long startMs = cursor.getLong(timeIdx);
                    double trimp = cursor.isNull(trimpIdx) ? 0 : cursor.getDouble(trimpIdx);
                    double hrtss = cursor.isNull(hrTssIdx) ? 0 : cursor.getDouble(hrTssIdx);

                    double load = trimp > 0 ? trimp : hrtss;
                    if (load <= 0) continue;

                    LocalDate date = Instant.ofEpochMilli(startMs)
                            .atZone(ZoneId.systemDefault()).toLocalDate();
                    dailyLoad.merge(date, load, Double::sum);
                }
            }
        }

        if (dailyLoad.isEmpty()) return new ArrayList<>();

        // 2. Fill day-by-day from first activity to today
        LocalDate start = dailyLoad.firstKey();
        LocalDate today = LocalDate.now();
        List<DayLoad> result = new ArrayList<>();

        double ctl = 0, atl = 0;

        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            double load = dailyLoad.getOrDefault(d, 0.0);

            double tsb = ctl - atl; // today's form = yesterday's CTL - yesterday's ATL
            ctl = ctl + (load - ctl) / 42.0;
            atl = atl + (load - atl) / 7.0;

            DayLoad dl = new DayLoad(d, load);
            dl.ctl = ctl;
            dl.atl = atl;
            dl.tsb = tsb;
            result.add(dl);
        }

        return result;
    }

    public static String interpretTSB(double tsb) {
        if (tsb > 15) return "Very fresh — possible detraining.";
        if (tsb > 5)  return "Good race readiness.";
        if (tsb > -10) return "Balanced load.";
        if (tsb > -30) return "Productive training block.";
        return "High fatigue — monitor recovery!";
    }
}
