/* 
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.utility

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos

/**
 * Computes Doppler-corrected reciprocal frequencies for linear transponders.
 *
 * For a linear (passband) transponder, uplink and downlink frequencies are
 * related by a fixed passband offset. When the satellite moves, both are
 * Doppler-shifted. Given one, we compute the other:
 *
 *   downlink → uplink: mapDownlinkToUplink (passband) → getUplinkFreq (Doppler)
 *   uplink → downlink: mapUplinkToDownlink (passband) → getDownlinkFreq (Doppler)
 *
 * Addresses GitHub issue #91 (Custom frequency Doppler correction).
 */
object DopplerFrequencyCalculator {

    /**
     * Given a downlink frequency, compute the Doppler-corrected uplink frequency.
     * Returns null if the transponder is not a linear passband type.
     */
    fun computeUplinkFromDownlink(
        downlinkHz: Long,
        transponder: SatRadio,
        orbitalPos: OrbitalPos
    ): Long? {
        if (!isLinearTransponder(transponder)) return null
        val baseUplink = TransponderMapper.mapDownlinkToUplink(downlinkHz, transponder) ?: return null
        return orbitalPos.getUplinkFreq(baseUplink)
    }

    /**
     * Given a downlink frequency, compute the Doppler-corrected uplink frequency
     * with an offset applied to the downlink (in Hz).
     * Returns null if the transponder is not a linear passband type.
     */
    fun computeUplinkFromDownlinkWithOffset(
        downlinkHz: Long,
        transponder: SatRadio,
        orbitalPos: OrbitalPos,
        offsetHz: Long
    ): Long? {
        if (!isLinearTransponder(transponder)) return null
        val baseUplink = TransponderMapper.mapDownlinkToUplink(downlinkHz + offsetHz, transponder) ?: return null
        return orbitalPos.getUplinkFreq(baseUplink)
    }

    /**
     * Given an uplink frequency, compute the Doppler-corrected downlink frequency.
     * Returns null if the transponder is not a linear passband type.
     */
    fun computeDownlinkFromUplink(
        uplinkHz: Long,
        transponder: SatRadio,
        orbitalPos: OrbitalPos
    ): Long? {
        if (!isLinearTransponder(transponder)) return null
        val baseDownlink = TransponderMapper.mapUplinkToDownlink(uplinkHz, transponder) ?: return null
        return orbitalPos.getDownlinkFreq(baseDownlink)
    }

    /**
     * Given an uplink frequency, compute the Doppler-corrected downlink frequency
     * with an offset applied to the downlink (in Hz).
     * Returns null if the transponder is not a linear passband type.
     */
    fun computeDownlinkFromUplinkWithOffset(
        uplinkHz: Long,
        transponder: SatRadio,
        orbitalPos: OrbitalPos,
        offsetHz: Long
    ): Long? {
        if (!isLinearTransponder(transponder)) return null
        val baseDownlink = TransponderMapper.mapUplinkToDownlink(uplinkHz, transponder) ?: return null
        return orbitalPos.getDownlinkFreq(baseDownlink + offsetHz)
    }

    /** True if this transponder supports linear passband mapping. */
    fun isLinearTransponder(transponder: SatRadio): Boolean {
        val upLow = transponder.uplinkLow
        val upHigh = transponder.uplinkHigh
        val downLow = transponder.downlinkLow
        val downHigh = transponder.downlinkHigh
        return upLow != null && upHigh != null && downLow != null && downHigh != null
                && upLow != upHigh && downLow != downHigh
    }
}
