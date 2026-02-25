package de.dennisguse.opentracks.settings;

import android.os.Bundle;

import androidx.preference.PreferenceFragmentCompat;

import de.dennisguse.opentracks.R;

public class FitnessSettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.settings_fitness);
    }

    @Override
    public void onResume() {
        super.onResume();
        ((SettingsActivity) getActivity()).getSupportActionBar().setTitle(R.string.settings_fitness_title);
    }
}
