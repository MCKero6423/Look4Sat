/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.domain.utility

import kotlin.math.abs

/**
 * Dependency-free replacement for jvm/Android `java.lang.String.format`, required because
 * JVM formatting APIs do not exist on Kotlin/Native (iOS).
 *
 * Supported conversions: `%d` `%x` `%X` `%f` `%s` `%c` `%%`, plus the `0` flag, a width and
 * `.precision` (for `%f`). Anything else throws, so an unsupported pattern never silently
 * produces a wrong string.
 *
 * `%f` rounding matches java.lang.String.format (half-up on the exact double value) whenever
 * the scaled value fits in a Long (< 2^53), which covers every frequency/coordinate string
 * the app builds. Negative zero is preserved like the JVM does ("-0.000").
 */
fun formatString(pattern: String, vararg args: Any?): String {
    val out = StringBuilder(pattern.length + 16)
    var argIndex = 0
    var i = 0
    while (i < pattern.length) {
        val ch = pattern[i]
        if (ch != '%') {
            out.append(ch); i++; continue
        }
        i++
        if (i >= pattern.length) throw IllegalArgumentException("dangling '%' in pattern: $pattern")
        if (pattern[i] == '%') {
            out.append('%'); i++; continue
        }
        var zeroPadded = false
        if (pattern[i] == '0') {
            zeroPadded = true; i++
        }
        var width = 0
        while (i < pattern.length && pattern[i].isDigit()) {
            width = width * 10 + (pattern[i] - '0'); i++
        }
        // java.lang.String.format throws MissingFormatWidthException for this; an illegal
        // pattern must not quietly format one way on Android and another way on iOS.
        if (zeroPadded && width == 0) {
            throw IllegalArgumentException("'0' flag without a width in pattern: $pattern")
        }
        var precision = -1 // java.lang.String.format defaults %f to 6 decimals
        if (i < pattern.length && pattern[i] == '.') {
            i++
            precision = 0 // the digits accumulate from zero; -1 means "not specified"
            while (i < pattern.length && pattern[i].isDigit()) {
                precision = precision * 10 + (pattern[i] - '0'); i++
            }
        }
        if (i >= pattern.length) throw IllegalArgumentException("truncated conversion in pattern: $pattern")
        val conversion = pattern[i]
        i++
        val arg = if (argIndex < args.size) args[argIndex++] else null
        val rendered = when (conversion) {
            'd' -> longArg(arg, conversion, pattern).toString()
            'x' -> longArg(arg, conversion, pattern).toString(16)
            'X' -> longArg(arg, conversion, pattern).toString(16).uppercase()
            'f' -> formatFixed(doubleArg(arg, pattern), if (precision < 0) 6 else precision, pattern)
            's' -> arg?.toString() ?: "null"
            'c' -> when (arg) {
                is Char -> arg.toString()
                is Int -> arg.toChar().toString()
                else -> throw IllegalArgumentException("unsupported %c argument: $arg in pattern: $pattern")
            }
            else -> throw IllegalArgumentException("unsupported conversion %$conversion in pattern: $pattern")
        }
        if (width <= rendered.length) {
            out.append(rendered)
        } else if (zeroPadded && !rendered.startsWith("-") && !rendered.startsWith("+")) {
            repeat(width - rendered.length) { out.append('0') }
            out.append(rendered)
        } else if (zeroPadded) {
            out.append(rendered[0])
            repeat(width - rendered.length) { out.append('0') }
            out.append(rendered.substring(1))
        } else {
            repeat(width - rendered.length) { out.append(' ') }
            out.append(rendered)
        }
    }
    // Extra arguments are ignored, exactly like java.lang.String.format: call sites already
    // pass what they pass and a port should not turn a latent extra argument into a crash.
    return out.toString()
}

/** `"%.3f".format(1.2345)` -> `"1.235"` */
fun String.format(vararg args: Any?): String = formatString(this, *args)

private val POWERS_OF_TEN = longArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000)
private const val MAX_LONG_DIGITS = 18 // the most decimal digits that still fit in a Long

private fun longArg(arg: Any?, conversion: Char, pattern: String): Long = when (arg) {
    is Int -> arg.toLong()
    is Long -> arg
    is Short -> arg.toLong()
    is Byte -> arg.toLong()
    else -> throw IllegalArgumentException("unsupported %$conversion argument: $arg in pattern: $pattern")
}

private fun doubleArg(arg: Any?, pattern: String): Double = when (arg) {
    is Double -> arg
    is Float -> arg.toDouble()
    is Int -> arg.toDouble()
    is Long -> arg.toDouble()
    else -> throw IllegalArgumentException("unsupported %f argument: $arg in pattern: $pattern")
}

private fun formatFixed(value: Double, precision: Int, pattern: String): String {
    if (precision !in 0..8) throw IllegalArgumentException("precision $precision too large in pattern: $pattern")
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0.0) "Infinity" else "-Infinity"
    val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
    val rounded = roundHalfUp(abs(value), precision, value, pattern)
    val power = POWERS_OF_TEN[precision]
    val integerPart = rounded / power
    val fractionPart = rounded % power
    val result = StringBuilder()
    if (negative) result.append('-')
    result.append(integerPart)
    if (precision > 0) {
        result.append('.')
        result.append(fractionPart.toString().padStart(precision, '0'))
    }
    return result.toString()
}

/**
 * Rounds to [precision] decimals the way java.lang.String.format does: it rounds the shortest
 * decimal representation of the double half-up, not its binary value. `"%.3f"` of 0.5005 is
 * therefore `"0.501"`, even though the double holds 0.50049999999999994493.
 *
 * Scaling in binary first - floor(magnitude * 10^precision + 0.5) - loses exactly that and printed
 * "0.500", so the digits come from the decimal representation and are rounded by integer
 * arithmetic instead. Returns the value scaled by 10^precision.
 */
private fun roundHalfUp(magnitude: Double, precision: Int, value: Double, pattern: String): Long {
    val text = magnitude.toString() // shortest representation that still round-trips
    val exponentIndex = text.indexOfFirst { it == 'E' || it == 'e' }
    val mantissa = if (exponentIndex < 0) text else text.substring(0, exponentIndex)
    val exponent = if (exponentIndex < 0) 0 else text.substring(exponentIndex + 1).toInt()
    val pointIndex = mantissa.indexOf('.')
    val integerDigits = if (pointIndex < 0) mantissa else mantissa.substring(0, pointIndex)
    val fractionDigits = if (pointIndex < 0) "" else mantissa.substring(pointIndex + 1)
    val digits = integerDigits + fractionDigits
    // magnitude == digits * 10^scale, so digits * 10^(scale + precision) is the scaled value.
    val shift = exponent - fractionDigits.length + precision
    val unscaled = digits.toLong()
    if (shift >= 0) {
        if (digits.length + shift > MAX_LONG_DIGITS) throw ValueTooLarge(value, precision, pattern)
        var scaled = unscaled
        repeat(shift) { scaled *= 10 }
        return scaled
    }
    // Below half of the last printed digit everything rounds to zero, and 10^divisorDigits would
    // no longer fit in a Long, so stop before building it.
    val divisorDigits = -shift
    if (divisorDigits > MAX_LONG_DIGITS) return 0L
    var divisor = 1L
    repeat(divisorDigits) { divisor *= 10 }
    val quotient = unscaled / divisor
    val remainder = unscaled % divisor
    return if (2 * remainder >= divisor) quotient + 1 else quotient
}

// Values this large are never produced by the app; avoid silently wrong digits.
private class ValueTooLarge(value: Double, precision: Int, pattern: String) :
    IllegalArgumentException("value $value too large for %.$precision" + "f in pattern: $pattern")
