/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package de.dennisguse.opentracks.fragments;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.charts.HorizontalBarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import de.dennisguse.opentracks.R;
import de.dennisguse.opentracks.TrackRecordedActivity;
import de.dennisguse.opentracks.analytics.ActivityAnalytics;
import de.dennisguse.opentracks.analytics.HeartRateZoneCalculator;
import de.dennisguse.opentracks.analytics.TrainingLoadCalculator;
import de.dennisguse.opentracks.data.ContentProviderUtils;
import de.dennisguse.opentracks.data.models.DistanceFormatter;
import de.dennisguse.opentracks.data.models.SpeedFormatter;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.data.tables.TracksColumns;
import de.dennisguse.opentracks.databinding.StatisticsRecordedBinding;
import de.dennisguse.opentracks.settings.PreferencesUtils;
import de.dennisguse.opentracks.settings.UnitSystem;
import de.dennisguse.opentracks.stats.SensorStatistics;
import de.dennisguse.opentracks.stats.TrackStatistics;
import de.dennisguse.opentracks.util.StringUtils;

/**
 * A fragment to display track statistics to the user for a recorded {@link Track}.
 *
 * @author Sandor Dornbush
 * @author Rodrigo Damazio
 */
public class StatisticsRecordedFragment extends Fragment {

    private static final String TAG = StatisticsRecordedFragment.class.getSimpleName();

    private static final String TRACK_ID_KEY = "trackId";

    public static StatisticsRecordedFragment newInstance(Track.Id trackId) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(TRACK_ID_KEY, trackId);

        StatisticsRecordedFragment fragment = new StatisticsRecordedFragment();
        fragment.setArguments(bundle);
        return fragment;
    }

    private SensorStatistics sensorStatistics;

    private Track.Id trackId;
    @Nullable // Lazily loaded.
    private Track track;

    private ContentProviderUtils contentProviderUtils;

    private StatisticsRecordedBinding viewBinding;

    private UnitSystem unitSystem = UnitSystem.defaultUnitSystem();
    private boolean preferenceReportSpeed;

    private final SharedPreferences.OnSharedPreferenceChangeListener sharedPreferenceChangeListener = (sharedPreferences, key) -> {
        boolean updateUInecessary = false;

        if (PreferencesUtils.isKey(R.string.stats_units_key, key)) {
            updateUInecessary = true;
            unitSystem = PreferencesUtils.getUnitSystem();
        }

        if (PreferencesUtils.isKey(R.string.stats_rate_key, key) && track != null) {
            updateUInecessary = true;
            preferenceReportSpeed = PreferencesUtils.isReportSpeed(track);
        }

        if (key != null && updateUInecessary && isResumed()) {
            getActivity().runOnUiThread(() -> {
                if (isResumed()) {
                    updateUI();
                }
            });
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        trackId = getArguments().getParcelable(TRACK_ID_KEY);
        contentProviderUtils = new ContentProviderUtils(getContext());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        viewBinding = StatisticsRecordedBinding.inflate(inflater, container, false);

        return viewBinding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();

        PreferencesUtils.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);

        loadStatistics();
    }

    @Override
    public void onPause() {
        super.onPause();

        PreferencesUtils.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewBinding = null;
    }

    public void loadStatistics() {
        if (isResumed()) {
            getActivity().runOnUiThread(() -> {
                if (isResumed()) {
                    Track track = contentProviderUtils.getTrack(trackId);
                    if (track == null) {
                        Log.e(TAG, "track cannot be null");
                        getActivity().finish();
                        return;
                    }

                    sensorStatistics = contentProviderUtils.getSensorStats(trackId);

                    boolean prefsChanged = this.track == null || (!this.track.getActivityTypeLocalized().equals(track.getActivityTypeLocalized()));
                    this.track = track;
                    if (prefsChanged) {
                        sharedPreferenceChangeListener.onSharedPreferenceChanged(null, getString(R.string.stats_rate_key));
                    }

                    loadTrackDescription(track);
                    updateUI();
                    updateSensorUI();

                    ((TrackRecordedActivity) getActivity()).startPostponedEnterTransitionWith(viewBinding.statsActivityTypeIcon);
                }
            });
        }
    }

    private void loadTrackDescription(@NonNull Track track) {
        viewBinding.statsNameValue.setText(track.getName());
        viewBinding.statsDescriptionValue.setText(track.getDescription());
        viewBinding.statsStartDatetimeValue.setText(StringUtils.formatDateTimeWithOffsetIfDifferent(track.getStartTime()));
    }

    private void updateUI() {
        TrackStatistics trackStatistics = track.getTrackStatistics();
        // Set total distance
        {
            Pair<String, String> parts = DistanceFormatter.Builder()
                    .setUnit(unitSystem)
                    .build(getContext()).getDistanceParts(trackStatistics.getTotalDistance());

            viewBinding.statsDistanceValue.setText(parts.first);
            viewBinding.statsDistanceUnit.setText(parts.second);
        }

        // Set activity type
        viewBinding.statsActivityTypeIcon.setImageDrawable(ContextCompat.getDrawable(getContext(), track.getActivityType().getIconDrawableId()));

        // Set time and start datetime
        {
            viewBinding.statsMovingTimeValue.setText(StringUtils.formatElapsedTime(trackStatistics.getMovingTime()));
            viewBinding.statsTotalTimeValue.setText(StringUtils.formatElapsedTime(trackStatistics.getTotalTime()));
        }

        SpeedFormatter formatter = SpeedFormatter.Builder().setUnit(unitSystem).setReportSpeedOrPace(preferenceReportSpeed).build(getContext());
        // Set average speed/pace
        {
            viewBinding.statsAverageSpeedLabel.setText(preferenceReportSpeed ? R.string.stats_average_speed : R.string.stats_average_pace);

            Pair<String, String> parts = formatter.getSpeedParts(trackStatistics.getAverageSpeed());
            viewBinding.statsAverageSpeedValue.setText(parts.first);
            viewBinding.statsAverageSpeedUnit.setText(parts.second);
        }

        // Set max speed/pace
        {
            viewBinding.statsMaxSpeedLabel.setText(preferenceReportSpeed ? R.string.stats_max_speed : R.string.stats_fastest_pace);

            Pair<String, String> parts = formatter.getSpeedParts(trackStatistics.getMaxSpeed());
            viewBinding.statsMaxSpeedValue.setText(parts.first);
            viewBinding.statsMaxSpeedUnit.setText(parts.second);
        }

        // Set moving speed/pace
        {
            viewBinding.statsMovingSpeedLabel.setText(preferenceReportSpeed ? R.string.stats_average_moving_speed : R.string.stats_average_moving_pace);

            Pair<String, String> parts = formatter.getSpeedParts(trackStatistics.getAverageMovingSpeed());
            viewBinding.statsMovingSpeedValue.setText(parts.first);
            viewBinding.statsMovingSpeedUnit.setText(parts.second);
        }

        // Set altitude gain and loss
        {
            Float altitudeGain_m = trackStatistics.getTotalAltitudeGain();
            Float altitudeLoss_m = trackStatistics.getTotalAltitudeLoss();

            Pair<String, String> parts;

            parts = StringUtils.getAltitudeParts(getContext(), altitudeGain_m, unitSystem);
            viewBinding.statsAltitudeGainValue.setText(parts.first);
            viewBinding.statsAltitudeGainUnit.setText(parts.second);

            parts = StringUtils.getAltitudeParts(getContext(), altitudeLoss_m, unitSystem);
            viewBinding.statsAltitudeLossValue.setText(parts.first);
            viewBinding.statsAltitudeLossUnit.setText(parts.second);

            boolean show = altitudeGain_m != null && altitudeLoss_m != null;
            viewBinding.statsAltitudeGroup.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void updateSensorUI() {
        if (sensorStatistics == null) {
            return;
        }

        if (sensorStatistics.hasHeartRate()) {
            String maxBPM = String.valueOf(Math.round(sensorStatistics.maxHeartRate().getBPM()));
            String avgBPM = String.valueOf(Math.round(sensorStatistics.avgHeartRate().getBPM()));

            viewBinding.statsHeartRateGroup.setVisibility(View.VISIBLE);
            viewBinding.statsMaxHeartRateValue.setText(maxBPM);
            viewBinding.statsAvgHeartRateValue.setText(avgBPM);
        }
        if (sensorStatistics.hasCadence()) {
            String maxRPM = String.valueOf(Math.round(sensorStatistics.maxCadence().getRPM()));
            String avgRPM = String.valueOf(Math.round(sensorStatistics.avgCadence().getRPM()));

            viewBinding.statsCadenceGroup.setVisibility(View.VISIBLE);
            viewBinding.statsMaxCadenceValue.setText(maxRPM);
            viewBinding.statsAvgCadenceValue.setText(avgRPM);
        }
        if (sensorStatistics.hasPower()) {
            String maxW = String.valueOf(Math.round(sensorStatistics.maxPower().getW()));
            String avgW = String.valueOf(Math.round(sensorStatistics.avgPower().getW()));

            viewBinding.statsPowerGroup.setVisibility(View.VISIBLE);
            viewBinding.statsMaxPowerValue.setText(maxW);
            viewBinding.statsAvgPowerValue.setText(avgW);
        }

        loadAnalytics();
    }

    private void loadAnalytics() {
        Executors.newSingleThreadExecutor().execute(() -> {
            // Ensure TRIMP/hrTSS are computed and stored
            TrainingLoadCalculator.computeAndSaveForTrack(requireContext(), trackId);

            // Read stored TRIMP/hrTSS
            double trimp = 0, hrTss = 0;
            try (Cursor cursor = requireContext().getContentResolver().query(
                    TracksColumns.CONTENT_URI,
                    new String[]{TracksColumns.TRIMP, TracksColumns.HRTSS},
                    TracksColumns._ID + "=?",
                    new String[]{String.valueOf(trackId.id())}, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int trimpIdx = cursor.getColumnIndex(TracksColumns.TRIMP);
                    int hrTssIdx = cursor.getColumnIndex(TracksColumns.HRTSS);
                    if (trimpIdx >= 0 && !cursor.isNull(trimpIdx)) trimp = cursor.getDouble(trimpIdx);
                    if (hrTssIdx >= 0 && !cursor.isNull(hrTssIdx)) hrTss = cursor.getDouble(hrTssIdx);
                }
            }

            ActivityAnalytics analytics = ActivityAnalytics.compute(requireContext(), trackId);

            final double finalTrimp = trimp;
            final double finalHrTss = hrTss;

            if (isResumed()) {
                requireActivity().runOnUiThread(() -> {
                    if (isResumed() && viewBinding != null) {
                        updateAnalyticsUI(analytics, finalTrimp, finalHrTss);
                    }
                });
            }
        });
    }

    private void updateAnalyticsUI(ActivityAnalytics analytics, double trimp, double hrTss) {
        viewBinding.analyticsTrimpValue.setText(String.format("%.1f", trimp));
        viewBinding.analyticsHrtssValue.setText(String.format("%.1f", hrTss));
        viewBinding.analyticsSufferValue.setText(String.format("%.0f", analytics.sufferScore));

        if (analytics.vo2maxEstimate > 0) {
            viewBinding.analyticsVo2maxValue.setText(String.format("%.1f ml/kg/min", analytics.vo2maxEstimate));
        } else {
            viewBinding.analyticsVo2maxValue.setText("—");
        }

        double dec = analytics.aerobicDecouplingPercent;
        String decText = String.format("Aerobic Decoupling: %.1f%% (%s)",
                dec, dec < 5.0 ? "aerobically efficient" : "above aerobic threshold");
        viewBinding.analyticsDecouplingValue.setText(decText);

        if (!analytics.hasHeartRateData) {
            viewBinding.analyticsNoHrData.setVisibility(View.VISIBLE);
            viewBinding.analyticsZoneChart.setVisibility(View.GONE);
            return;
        }

        viewBinding.analyticsNoHrData.setVisibility(View.GONE);
        viewBinding.analyticsZoneChart.setVisibility(View.VISIBLE);
        buildZoneChart(analytics);
    }

    private void buildZoneChart(ActivityAnalytics analytics) {
        int maxHR = PreferencesUtils.getFitnessEffectiveMaxHeartRate();
        int rhr = PreferencesUtils.getFitnessRestingHeartRate();
        int lthr = PreferencesUtils.getFitnessEffectiveLTHR();
        String zoneModel = PreferencesUtils.getFitnessZoneModel();
        HeartRateZoneCalculator calc = new HeartRateZoneCalculator(zoneModel, maxHR, rhr, lthr);

        int[] zoneColors = {
                Color.GRAY, Color.BLUE, Color.GREEN,
                Color.parseColor("#FF9800"), Color.RED,
                Color.parseColor("#B71C1C"), Color.parseColor("#4A148C")
        };

        List<BarEntry> entries = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        long[] timeInZone = analytics.timeInZoneSeconds;
        for (int i = 0; i < timeInZone.length; i++) {
            float minutes = timeInZone[i] / 60f;
            entries.add(new BarEntry(i, minutes));
            colors.add(i < zoneColors.length ? zoneColors[i] : Color.GRAY);
            labels.add(calc.getZoneName(i));
        }

        BarDataSet dataSet = new BarDataSet(entries, "");
        dataSet.setColors(colors);
        dataSet.setDrawValues(true);
        dataSet.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                if (value < 0.5f) return "";
                int min = (int) value;
                int sec = (int) ((value - min) * 60);
                return min + ":" + String.format("%02d", sec);
            }
        });

        HorizontalBarChart chart = viewBinding.analyticsZoneChart;
        chart.setData(new BarData(dataSet));
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setDrawValueAboveBar(true);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                int idx = (int) value;
                if (idx >= 0 && idx < labels.size()) return labels.get(idx);
                return "";
            }
        });
        chart.getAxisRight().setEnabled(false);
        chart.invalidate();
    }
}
