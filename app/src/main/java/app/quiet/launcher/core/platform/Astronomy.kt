package app.quiet.launcher.core.platform

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Pure astronomy helpers (no Android). Accuracy: moon about 0.2 day, sun times about 1 minute. */
data class SunTimes(
    val sunrise: ZonedDateTime?,
    val sunset: ZonedDateTime?,
    val civilDusk: ZonedDateTime?,
    val civilDawn: ZonedDateTime?,
)

object Astronomy {
    private const val J2000 = 2451545.0

    private fun rad(d: Double) = Math.toRadians(d)
    private fun norm360(d: Double): Double { val r = d % 360.0; return if (r < 0) r + 360.0 else r }

    private fun julianDay(instant: Instant): Double =
        instant.epochSecond / 86400.0 + instant.nano / 86400e9 + 2440587.5

    /** 0 new, 0.5 full, back to 1 at the next new moon. Uses the moon-sun elongation (Meeus ch. 47, main terms). */
    fun moonPhase(instant: Instant): Double {
        val t = (julianDay(instant) - J2000) / 36525.0
        val lm = norm360(218.3164477 + 481267.88123421 * t)
        val d = rad(norm360(297.8501921 + 445267.1114034 * t))
        val m = rad(norm360(357.5291092 + 35999.0502909 * t))
        val mp = rad(norm360(134.9633964 + 477198.8675055 * t))
        val f = rad(norm360(93.2720950 + 483202.0175233 * t))
        val moonLon = lm + 6.288774 * sin(mp) + 1.274027 * sin(2 * d - mp) + 0.658314 * sin(2 * d) +
            0.213618 * sin(2 * mp) - 0.185116 * sin(m) - 0.114332 * sin(2 * f) +
            0.058793 * sin(2 * d - 2 * mp) + 0.057066 * sin(2 * d - m - mp) + 0.053322 * sin(2 * d + mp) +
            0.045758 * sin(2 * d - m)
        val l0 = norm360(280.46646 + 36000.76983 * t)
        val sunLon = l0 + 1.914602 * sin(m) + 0.019993 * sin(2 * m)
        return norm360(moonLon - sunLon) / 360.0
    }

    fun illuminatedFraction(phase: Double): Double = (1 - cos(2 * Math.PI * phase)) / 2

    fun phaseName(phase: Double): String {
        val p = ((phase % 1.0) + 1.0) % 1.0
        return when (((p * 8.0) + 0.5).toInt() % 8) {
            0 -> "New moon"
            1 -> "Waxing crescent"
            2 -> "First quarter"
            3 -> "Waxing gibbous"
            4 -> "Full moon"
            5 -> "Waning gibbous"
            6 -> "Last quarter"
            else -> "Waning crescent"
        }
    }

    /** Local date (in from's zone) of the next full moon after [from]; hourly scan for the 0.5 crossing. */
    fun nextFullMoon(from: ZonedDateTime): LocalDate {
        var t = from.toInstant()
        var prev = moonPhase(t)
        var guard = 0
        while (prev >= 0.5 && guard++ < 24 * 31) { t = t.plus(Duration.ofHours(1)); prev = moonPhase(t) }
        guard = 0
        while (guard++ < 24 * 31) {
            val next = t.plus(Duration.ofHours(1))
            val p = moonPhase(next)
            if (p >= 0.5 && prev < 0.5) return next.atZone(from.zone).toLocalDate()
            prev = p; t = next
        }
        return from.toLocalDate().plusDays(29)
    }

    /** Returns declination (degrees) and equation of time (minutes) at a Julian day. */
    private fun solarParams(jd: Double): Pair<Double, Double> {
        val t = (jd - J2000) / 36525.0
        val l0 = norm360(280.46646 + t * (36000.76983 + t * 0.0003032))
        val m = norm360(357.52911 + t * (35999.05029 - 0.0001537 * t))
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(rad(m)) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(rad(2 * m)) * (0.019993 - 0.000101 * t) + sin(rad(3 * m)) * 0.000289
        val trueLon = l0 + c
        val omega = 125.04 - 1934.136 * t
        val lambda = trueLon - 0.00569 - 0.00478 * sin(rad(omega))
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cos(rad(omega))
        val decl = Math.toDegrees(asin(sin(rad(eps)) * sin(rad(lambda))))
        val y = tan(rad(eps / 2)).let { it * it }
        val eq = 4.0 * Math.toDegrees(
            y * sin(2 * rad(l0)) - 2 * e * sin(rad(m)) + 4 * e * y * sin(rad(m)) * cos(2 * rad(l0)) -
                0.5 * y * y * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(m))
        )
        return decl to eq
    }

    /** Minutes after 00:00 UTC of [jd0] at which the sun crosses [zenith]; null if it never does. */
    private fun eventMinutes(jd0: Double, lat: Double, lon: Double, zenith: Double, rising: Boolean): Double? {
        var minutes = 720.0 - 4.0 * lon
        repeat(2) {
            val (decl, eq) = solarParams(jd0 + minutes / 1440.0)
            val cosH = cos(rad(zenith)) / (cos(rad(lat)) * cos(rad(decl))) - tan(rad(lat)) * tan(rad(decl))
            if (cosH > 1.0 || cosH < -1.0) return null
            val ha = Math.toDegrees(acos(cosH))
            minutes = 720.0 - 4.0 * (lon + if (rising) ha else -ha) - eq
        }
        return minutes
    }

    fun sunTimes(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): SunTimes {
        val midnight = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        val jd0 = julianDay(midnight)
        fun at(zenith: Double, rising: Boolean): ZonedDateTime? =
            eventMinutes(jd0, lat, lon, zenith, rising)?.let {
                midnight.plusMillis((it * 60_000.0).toLong()).atZone(zone)
            }
        return SunTimes(
            sunrise = at(90.833, true),
            sunset = at(90.833, false),
            civilDusk = at(96.0, false),
            civilDawn = at(96.0, true),
        )
    }

    fun sunAltitudeDeg(instant: Instant, lat: Double, lon: Double): Double {
        val jd = julianDay(instant)
        val (decl, eq) = solarParams(jd)
        val utcMin = (((jd + 0.5) % 1.0) + 1.0) % 1.0 * 1440.0
        val tst = utcMin + eq + 4.0 * lon
        val ha = rad(tst / 4.0 - 180.0)
        val s = sin(rad(lat)) * sin(rad(decl)) + cos(rad(lat)) * cos(rad(decl)) * cos(ha)
        return Math.toDegrees(asin(s.coerceIn(-1.0, 1.0)))
    }

    /** 0 at sunrise, 1 at sunset, outside [0,1] when the sun is down. Returns 0 when there is no sunrise or sunset. */
    fun sunArcFraction(now: ZonedDateTime, sunTimes: SunTimes): Float {
        val rise = sunTimes.sunrise ?: return 0f
        val set = sunTimes.sunset ?: return 0f
        val total = Duration.between(rise, set).toMillis().toDouble()
        if (total <= 0.0) return 0f
        return (Duration.between(rise, now).toMillis() / total).toFloat()
    }

    private val ones = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = arrayOf("", "", "twenty", "thirty", "forty", "fifty")

    private fun words(n: Int): String =
        if (n < 20) ones[n] else tens[n / 10] + if (n % 10 != 0) "-" + ones[n % 10] else ""

    /** 24h British words: 16:42 "Sixteen forty-two", 09:05 "Nine oh five", 16:00 "Sixteen hundred". */
    fun timeInWords(hour: Int, minute: Int): String {
        val h = words(hour.coerceIn(0, 23))
        val m = minute.coerceIn(0, 59)
        val tail = when {
            m == 0 -> "hundred"
            m < 10 -> "oh " + ones[m]
            else -> words(m)
        }
        return (h + " " + tail).replaceFirstChar { it.uppercase() }
    }
}

fun moonPhase(instant: Instant) = Astronomy.moonPhase(instant)
fun illuminatedFraction(phase: Double) = Astronomy.illuminatedFraction(phase)
fun phaseName(phase: Double) = Astronomy.phaseName(phase)
fun nextFullMoon(from: ZonedDateTime) = Astronomy.nextFullMoon(from)
fun sunTimes(date: LocalDate, lat: Double, lon: Double, zone: ZoneId) = Astronomy.sunTimes(date, lat, lon, zone)
fun sunAltitudeDeg(instant: Instant, lat: Double, lon: Double) = Astronomy.sunAltitudeDeg(instant, lat, lon)
fun sunArcFraction(now: ZonedDateTime, sunTimes: SunTimes) = Astronomy.sunArcFraction(now, sunTimes)
fun timeInWords(hour: Int, minute: Int) = Astronomy.timeInWords(hour, minute)
