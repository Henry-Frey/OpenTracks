package de.dennisguse.opentracks.ui.dashboard;

import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import de.dennisguse.opentracks.R;
import de.dennisguse.opentracks.analytics.TrainingLoadCalculator;
import de.dennisguse.opentracks.databinding.ActivityDashboardBinding;
import de.dennisguse.opentracks.settings.PreferencesUtils;

public class DashboardActivity extends AppCompatActivity {

    private ActivityDashboardBinding viewBinding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PreferencesUtils.applyTheme(this);
        super.onCreate(savedInstanceState);

        viewBinding = ActivityDashboardBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());

        setSupportActionBar(viewBinding.dashboardToolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        loadData();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadData() {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<TrainingLoadCalculator.DayLoad> history =
                    TrainingLoadCalculator.buildFitnessHistory(this);

            runOnUiThread(() -> {
                if (history.isEmpty()) {
                    viewBinding.dashboardNoData.setVisibility(View.VISIBLE);
                    viewBinding.dashboardChart.setVisibility(View.GONE);
                    viewBinding.dashboardCurrentValues.setText(R.string.dashboard_no_data);
                    return;
                }

                viewBinding.dashboardNoData.setVisibility(View.GONE);
                viewBinding.dashboardChart.setVisibility(View.VISIBLE);

                // Today's values
                TrainingLoadCalculator.DayLoad today = history.get(history.size() - 1);
                viewBinding.dashboardCurrentValues.setText(
                        String.format("Fitness %.1f  |  Fatigue %.1f  |  Form %.1f",
                                today.ctl, today.atl, today.tsb));
                viewBinding.dashboardInterpretation.setText(
                        TrainingLoadCalculator.interpretTSB(today.tsb));

                buildChart(history);
            });
        });
    }

    private void buildChart(List<TrainingLoadCalculator.DayLoad> history) {
        List<Entry> ctlEntries = new ArrayList<>();
        List<Entry> atlEntries = new ArrayList<>();
        List<Entry> tsbEntries = new ArrayList<>();

        final LocalDate firstDate = history.get(0).date;
        final List<String> xLabels = new ArrayList<>();

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM d");

        for (int i = 0; i < history.size(); i++) {
            TrainingLoadCalculator.DayLoad d = history.get(i);
            ctlEntries.add(new Entry(i, (float) d.ctl));
            atlEntries.add(new Entry(i, (float) d.atl));
            tsbEntries.add(new Entry(i, (float) d.tsb));
            xLabels.add(d.date.format(fmt));
        }

        LineDataSet ctlSet = new LineDataSet(ctlEntries, getString(R.string.dashboard_fitness_label));
        ctlSet.setColor(Color.parseColor("#2196F3"));
        ctlSet.setCircleRadius(0f);
        ctlSet.setDrawCircles(false);
        ctlSet.setLineWidth(2f);
        ctlSet.setDrawValues(false);

        LineDataSet atlSet = new LineDataSet(atlEntries, getString(R.string.dashboard_fatigue_label));
        atlSet.setColor(Color.parseColor("#E91E63"));
        atlSet.setCircleRadius(0f);
        atlSet.setDrawCircles(false);
        atlSet.setLineWidth(2f);
        atlSet.setDrawValues(false);

        LineDataSet tsbSet = new LineDataSet(tsbEntries, getString(R.string.dashboard_form_label));
        tsbSet.setColor(Color.parseColor("#FFEB3B"));
        tsbSet.setCircleRadius(0f);
        tsbSet.setDrawCircles(false);
        tsbSet.setLineWidth(2f);
        tsbSet.setDrawValues(false);

        LineChart chart = viewBinding.dashboardChart;
        chart.setData(new LineData(ctlSet, atlSet, tsbSet));
        chart.getDescription().setEnabled(false);
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(true);
        chart.setPinchZoom(true);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                int idx = (int) value;
                if (idx >= 0 && idx < xLabels.size()) return xLabels.get(idx);
                return "";
            }
        });
        xAxis.setLabelRotationAngle(-45f);
        xAxis.setLabelCount(Math.min(6, history.size()));

        chart.getAxisRight().setEnabled(false);
        chart.getLegend().setEnabled(false); // we have our own legend
        chart.invalidate();
    }
}
