# Mutual Pass & Elevation Curve Feature

> **For Hermes:** Implementation plan for dual-station mutual pass query and draggable elevation curve.

**Goal:** Port satlover.de's "对台过境查询" (dual-station mutual pass query) and draggable dual elevation curve into Look4Sat.

**Architecture:**
- New feature module `feature/mutual/` following existing feature plugin pattern
- Mutual pass logic: compute passes for two stations, find overlapping time windows
- Elevation curve: Canvas-based composable with drag gesture

**Tech Stack:** Kotlin, Jetpack Compose, Canvas, predict4java (existing)

---

## Module Structure

### New files to create:

```
feature/mutual/
├── build.gradle.kts
└── src/main/java/com/rtbishop/look4sat/feature/mutual/
    ├── MutualPass.kt              # data class for a mutual pass
    ├── MutualViewModel.kt         # ViewModel for dual-station logic
    ├── MutualScreen.kt            # Main screen composable
    ├── MutualInputSection.kt      # Location input section
    ├── MutualResultList.kt        # Mutual pass results list
    └── ElevationCurveChart.kt     # Draggable dual elevation curve
```

### Existing files to modify:

- `settings.gradle.kts` — add `:feature:mutual` include
- `app/build.gradle.kts` — add `implementation(project(":feature:mutual"))`
- `app/.../MainActivity.kt` or navigation — add mutual pass route
- `core/presentation/.../strings.xml` — add string resources (Chinese + English)

---

## Task 1: Create feature/mutual module

**Files:**
- Create: `feature/mutual/build.gradle.kts`
- Modify: `settings.gradle.kts` — add `include(":feature:mutual")`
- Modify: `app/build.gradle.kts` — add `implementation(project(":feature:mutual"))`

## Task 2: Add MutualPass data model

**Files:**
- Create: `feature/mutual/src/main/java/.../MutualPass.kt`

**Data class:**
```kotlin
data class MutualPass(
    val catNum: Int,
    val name: String,
    val startTime: Long,      // common AOS time
    val endTime: Long,        // common LOS time
    val maxElevationA: Double,
    val maxElevationB: Double,
    val elevationSamples: List<Pair<Long, Pair<Double, Double>>>  // time -> (elevA, elevB)
)
```

## Task 3: MutualViewModel

**Files:**
- Create: `feature/mutual/src/main/java/.../MutualViewModel.kt`

**Logic:**
- Input: station A position, station B position, time range, selected satellites
- For each satellite: compute passes for A and B, find overlapping windows
- For each overlap: sample elevation every N seconds, store in MutualPass
- Return sorted list of mutual passes

## Task 4: ElevationCurveChart composable

**Files:**
- Create: `feature/mutual/src/main/java/.../ElevationCurveChart.kt`

**Features:**
- Canvas draw: background grid, elevation labels, two curves (station A color, station B color)
- Drag gesture: draggable vertical line + time label
- Time axis: show time labels at regular intervals

## Task 5: MutualScreen UI

**Files:**
- Create: `feature/mutual/src/main/java/.../MutualInputSection.kt`
- Create: `feature/mutual/src/main/java/.../MutualResultList.kt`
- Create: `feature/mutual/src/main/java/.../MutualScreen.kt`

**Layout:**
- Top: two station location inputs (lat/lon + min elevation)
- Middle: time range selector + satellite selector
- Bottom: results list with elevation curve for each mutual pass

## Task 6: Navigation + Strings

**Files:**
- Modify: `app/.../MainActivity.kt` or navigation setup
- Modify: `core/presentation/.../values/strings.xml` + `values-zh/strings.xml`

---

## Verification

1. Build: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL
2. Navigate to mutual pass screen from main navigation
3. Input two locations, select satellites, query → see mutual passes
4. Tap a mutual pass → see elevation curve with drag
5. Test with different time zones and UTC