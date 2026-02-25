# OpenTracks — Fitness Analytics Fork

> A privacy-first Android sport tracker extended with a full fitness analytics engine:
> heart rate zones, training load (TRIMP / hrTSS), and a Fitness & Freshness dashboard
> modelled after the CTL/ATL/TSB performance management chart used by platforms like Strava.

[![Android](https://img.shields.io/badge/platform-Android-3ddc84?logo=android&logoColor=white)](https://developer.android.com)
[![Java](https://img.shields.io/badge/language-Java-f89820?logo=openjdk&logoColor=white)](https://openjdk.org)
[![Min SDK](https://img.shields.io/badge/minSdk-26-blue)](https://developer.android.com/tools/releases/platforms#8.0)
[![License](https://img.shields.io/badge/license-Apache%202.0-lightgrey)](LICENSE)
[![Fork of](https://img.shields.io/badge/fork%20of-OpenTracks-orange)](https://codeberg.org/OpenTracksApp/OpenTracks)

---

## Overview

This project forks [OpenTracks](https://codeberg.org/OpenTracksApp/OpenTracks) — a well-established
open-source GPS and BLE sport tracker — and layers a complete fitness analytics system on top.
The goal was to answer the question: *how do you go from raw heart rate data to meaningful training
insights?*

The result is a self-contained Android app that, entirely on-device and with no cloud dependency,
computes training load, tracks fitness over time, and surfaces per-activity analytics alongside
every recorded workout.

---

## Features Added

### Heart Rate Zones
Three configurable zone models, all derived from the user's personal fitness profile:

| Model | Basis | Zones |
|---|---|---|
| % of Max HR | Age-predicted or user-set MaxHR | 5 zones |
| Karvonen (HRR) | Heart Rate Reserve (MaxHR − RHR) | 5 zones |
| LTHR-Based Friel | Lactate Threshold HR | 7 zones (Z1–Z5c) |

Zone boundaries are computed dynamically and visualised as a colour-coded horizontal bar chart
inside every activity's statistics screen.

### Training Load — TRIMP & hrTSS
Two complementary training-load metrics are calculated per activity and stored in the local
SQLite database:

- **TRIMP (Banister)** — integrates HR intensity over time using an exponential weighting curve,
  with separate constants for male (k = 1.92) and female (k = 1.67) athletes.
- **hrTSS** — normalises effort relative to the user's Lactate Threshold HR, analogous to
  Coggan's TSS for cycling power.

### Fitness & Freshness Dashboard (CTL / ATL / TSB)
An interactive `LineChart` (MPAndroidChart) plots the full training history as three
exponentially-weighted moving averages:

| Metric | Window | Meaning |
|---|---|---|
| **CTL** — Chronic Training Load | 42 days | Long-term fitness / aerobic base |
| **ATL** — Acute Training Load | 7 days | Short-term fatigue |
| **TSB** — Training Stress Balance | CTL − ATL | Form / readiness |

A plain-language interpretation of today's TSB guides recovery decisions
(e.g. *"Productive training block"*, *"Good race readiness"*).

### Per-Activity Analytics
Displayed directly in the statistics screen for every recorded workout:

- **Time-in-zones** — seconds and percentage spent in each HR zone
- **Aerobic Decoupling** — compares cardiac efficiency (pace ÷ HR) between the first and second
  halves of an activity; < 5% indicates aerobic efficiency
- **Suffer Score** — zone-weighted load index (weights 1 / 2 / 3 / 4 / 8 per zone)
- **VO₂max Estimate** — derived from steady-state running segments using the
  ACSM metabolic equation and the Swain %VO₂ / %HRmax relationship

### Fitness Profile Settings
A dedicated settings screen allows configuration of:
- Resting Heart Rate (RHR)
- Maximum Heart Rate (manual override, or automatic via the **Tanaka formula**: 208 − 0.7 × age)
- Lactate Threshold Heart Rate (LTHR)
- Date of birth, biological sex (affects TRIMP k-constant)
- Preferred HR zone model

---

## Architecture

```
app/
├── analytics/
│   ├── HeartRateZoneCalculator.java   # Zone boundary computation (3 models)
│   ├── ActivityAnalytics.java         # Per-activity metrics (zones, decoupling, VO2max)
│   └── TrainingLoadCalculator.java    # TRIMP, hrTSS, CTL/ATL/TSB history
│
├── ui/dashboard/
│   └── DashboardActivity.java         # Fitness & Freshness chart screen
│
├── settings/
│   ├── FitnessSettingsFragment.java   # Fitness profile preferences UI
│   └── PreferencesUtils.java          # (extended) typed preference accessors
│
└── data/
    ├── tables/TracksColumns.java      # (extended) trimp + hrtss columns
    └── CustomSQLiteOpenHelper.java    # (extended) DB migration v38 → v39
```

### Data Flow

```
BLE Heart Rate Sensor
        │
        ▼
  TrackPoint (HR + GPS + timestamp)
        │
        ├──► TrainingLoadCalculator ──► SQLite (trimp, hrtss per track)
        │                                        │
        │                                        ▼
        │                              buildFitnessHistory()
        │                                        │
        │                                        ▼
        │                               CTL / ATL / TSB
        │                                        │
        │                                        ▼
        │                              DashboardActivity (LineChart)
        │
        └──► ActivityAnalytics ──► time-in-zones, decoupling,
                                   suffer score, VO₂max
                                        │
                                        ▼
                           StatisticsRecordedFragment (BarChart)
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| UI | Android Views, Material Design 3, ViewBinding |
| Charts | [MPAndroidChart v3.1.0](https://github.com/PhilJay/MPAndroidChart) |
| Persistence | SQLite (ContentProvider pattern) |
| Sensors | Bluetooth LE (existing OpenTracks infrastructure) |
| Async | `java.util.concurrent.Executors` |
| Build | Gradle 8, Android Gradle Plugin |

---

## Key Algorithms

### TRIMP (Banister, 1991)
For each time interval *dt* (minutes) at heart rate *HR*:

```
HRR  = (maxHR − rhr)
ratio = clamp((HR − rhr) / HRR, 0, 1)
TRIMP += dt × ratio × e^(k × ratio)
```
where *k* = 1.92 (male) or 1.67 (female).

### CTL / ATL Update (daily)
```
CTL_today = CTL_yesterday + (load − CTL_yesterday) / 42
ATL_today = ATL_yesterday + (load − ATL_yesterday) / 7
TSB_today = CTL_yesterday − ATL_yesterday
```

### VO₂max Estimate (Swain + ACSM)
```
%VO2   = (%HRmax − 0.37) / 0.64
VO2    = speed_mpm × 0.2 + 3.5        (ACSM running equation)
VO2max = VO2 / %VO2
```
Averaged across all qualifying steady-state points in the activity
(speed > 0.5 m/s, HR > 37% of MaxHR).

### Aerobic Decoupling
```
eff1 = avgSpeed_1st_half / avgHR_1st_half
eff2 = avgSpeed_2nd_half / avgHR_2nd_half
decoupling (%) = (eff1 − eff2) / eff1 × 100
```

---

## Getting Started

### Prerequisites
- Android Studio Meerkat or newer
- Android SDK 26+ device or emulator
- A BLE heart rate monitor (optional but recommended for full analytics)

### Build
```bash
git clone https://github.com/OverfittingUnderachiever/OpenTracks.git
cd OpenTracks
git checkout feature/fitness-analytics
./gradlew assembleDebug
```

### First-time Setup
1. Open the app and navigate to **Settings → Fitness Profile**
2. Enter your resting heart rate, date of birth, and biological sex
3. Optionally set a manual MaxHR or LTHR (the app will estimate them if left at 0)
4. Choose a heart rate zone model
5. Record or import an activity — analytics appear automatically in the stats screen
6. Tap the chart icon in the bottom bar to open the Fitness & Freshness dashboard

---

## Database Migration

The fork adds two columns to the `tracks` table via a non-destructive SQLite migration
(`DATABASE_VERSION` 38 → 39):

```sql
ALTER TABLE tracks ADD COLUMN trimp FLOAT;
ALTER TABLE tracks ADD COLUMN hrtss FLOAT;
```

Existing data is preserved. Downgrade to v38 recreates the table without these columns.

---

## Upstream

This fork is based on [OpenTracks v4.22.0](https://codeberg.org/OpenTracksApp/OpenTracks).
All original privacy guarantees are maintained — no internet access, no analytics, no
third-party services. The fitness analytics engine runs entirely on-device.

Original README and upstream documentation: [UPSTREAM.md](UPSTREAM_README.md)

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).
Original work © Google Inc. / OpenTracks contributors.
Fitness analytics additions © 2026 OverfittingUnderachiever.
