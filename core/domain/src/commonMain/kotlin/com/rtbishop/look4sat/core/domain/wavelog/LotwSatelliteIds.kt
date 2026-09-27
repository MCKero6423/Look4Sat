/* LotwSatelliteIds.kt - NORAD catalogue number to LoTW satellite name.
 *
 * Why the catalogue number and not the name: the same satellite carries different names in
 * different TLE sources, so a name-keyed table misses whenever the user switches source.
 * Measured across Celestrak amateur and AMSAT nasabare, 33 of the 49 satellites present in
 * both are named differently - NORAD 43017 is "RADFXSAT (FOX-1B)" in one and "AO-91" in the
 * other, 43700 is "ES'HAIL 2" against "QO-100". The catalogue number is identical in every
 * source, so it is the only stable key.
 *
 * LoTW rejects a QSO whose SAT_NAME is not spelled exactly as in its accepted list
 * (https://lotw.arrl.org/lotw-help/satellite-qsos: "if you enter the satellite name as AO7
 * instead of AO-7 the data will be rejected"), which is why this maps to the exact spelling
 * held in LotwSatellites rather than to whatever the TLE happens to say.
 *
 * Every number here was read out of live TLE data, never typed from memory. Entries cover the
 * satellites that both appear in the app's own sources (Sources.satelliteDataUrls) and are in
 * the LoTW list; the rest of that list is satellites no source still carries, so no user can
 * track them and no mapping is needed for them.
 */
package com.rtbishop.look4sat.core.domain.wavelog

object LotwSatelliteIds {

    /**
     * NORAD catalogue number to the LoTW spelling. The trailing comment is one name the
     * satellite goes by in the sources, kept so a reader can recognise the entry.
     *
     * Three numbers had to be decided rather than derived, because one name matched several
     * catalogued objects. Each was settled by which object the amateur-specific sources carry:
     * - ARISS is 25544, the station itself. Celestrak's full catalogue also lists ISS (UNITY),
     *   (ZVEZDA), (DESTINY) and (NAUKA), which are modules rather than stations you work.
     * - IO-117 is 53109: four sources name that number GREENCUBE (IO-117) and only R4UAB calls
     *   it ROBUSTA 1F, which is a different satellite.
     * - TO-108 is 44881, present in all three amateur sources; 44879 is TIANQIN 1 and appears
     *   only in the general catalogue.
     */
    private val idToName: Map<Int, String> = mapOf(
        7530 to "AO-7",       // AO-07
        14129 to "AO-10",     // PHASE 3B (AO-10)
        20439 to "AO-16",     // OSCAR 16 (PACSAT)
        20442 to "LO-19",     // LO-19
        22825 to "AO-27",     // AO-27
        23439 to "RS-15",     // RADIO ROSTO (RS-15)
        24278 to "FO-29",     // FO-29
        25544 to "ARISS",     // ISS (ZARYA)
        26609 to "AO-40",     // PHASE 3D (AO-40)
        26931 to "NO-44",     // NO-44
        27607 to "SO-50",     // SAUDISAT 1C (SO-50)
        28650 to "VO-52",     // HAMSAT (VO-52)
        39444 to "AO-73",     // AO-73
        40025 to "EO-79",     // FUNCUBE-3 (EO-79)/QB50P1
        40074 to "UKUBE1",    // UKUBE-1
        40908 to "CAS-3H",    // LILACSAT-2
        40931 to "IO-86",     // IO-86
        40967 to "AO-85",     // FOX-1A (AO-85)
        41847 to "CAS-2T",    // CAS-2T
        43017 to "AO-91",     // AO-91
        43678 to "PO-101",    // DIWATA-2B
        43700 to "QO-100",    // ES'HAIL 2
        43803 to "JO-97",     // JO-97
        44530 to "TAURUS",    // TAURUS-1
        44881 to "TO-108",    // CAS-6 (TO-108)
        44909 to "RS-44",     // DOSAAF-85 (RS-44)
        50466 to "HO-113",    // CAMSAT XW-3 (CAS-9)
        53109 to "IO-117",    // GREENCUBE (IO-117)
        61781 to "AO-123",    // AO-123
        // The TEVEL-2 constellation. Every source writes these TEVEL2-N while LoTW has TEV2-N,
        // and no amount of separator-stripping bridges that - TEVEL21 is not TEV21 - so without
        // these nine rows their QSOs upload under a name LoTW refuses. Note the numbering is not
        // sequential: 63217 is TEVEL2-1 and 63213 is TEVEL2-4.
        63213 to "TEV2-4",
        63214 to "TEV2-5",
        63215 to "TEV2-6",
        63217 to "TEV2-1",
        63218 to "TEV2-3",
        63219 to "TEV2-2",
        63237 to "TEV2-9",
        63238 to "TEV2-7",
        63239 to "TEV2-8"
    )

    /** The LoTW spelling for [catnum], or null when this satellite is not in the LoTW list. */
    fun nameFor(catnum: Int): String? = idToName[catnum]

    /** True when [catnum] names a satellite LoTW accepts, so a QSO on it can be confirmed. */
    fun isKnown(catnum: Int): Boolean = catnum in idToName

    /** Entry count, so a test can catch the table being emptied by a bad edit. */
    val size: Int get() = idToName.size
}
