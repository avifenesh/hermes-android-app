package dev.avifenesh.hermes

import android.icu.text.CompactDecimalFormat
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.icu.util.ULocale
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Dates, times, durations and numbers the app formats itself, in the app language's conventions.
 * Date skeletons ("dMMM", "EEEHm") go through the locale's best pattern; times follow the phone's 12/24-hour setting.
 */
object Fmt {
    private val patterns = ConcurrentHashMap<Pair<Locale, String>, String>()

    fun locale(): Locale = L10n.locale()

    private fun h24(): Boolean = android.text.format.DateFormat.is24HourFormat(L10n.context())

    /** The locale's pattern for a skeleton, e.g. "dMMM" -> "d MMM" (en-GB), "MMM d" (en-US), "M月d日" (ja). */
    fun pattern(skeleton: String, l: Locale = locale()): String =
        patterns.getOrPut(l to skeleton) { android.text.format.DateFormat.getBestDateTimePattern(l, skeleton) }

    fun date(ms: Long, skeleton: String): String = locale().let { l -> SimpleDateFormat(pattern(skeleton, l), l).format(Date(ms)) }

    private fun withTime(skeleton: String) = skeleton + if (h24()) "Hm" else "hm"

    /** Time of day ("14:05" or "2:05 PM"). */
    fun time(ms: Long): String = date(ms, withTime(""))
    /** A wall-clock time given as hour and minute (alarm times stored as "07:30"). Formatted at a fixed UTC instant,
     * not today's date in the phone's zone: on a daylight-saving gap day 02:30 would otherwise show as 03:30. */
    fun time(hour: Int, minute: Int): String = locale().let { l ->
        SimpleDateFormat(pattern(withTime(""), l), l).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(Date((hour * 60L + minute) * 60_000L))
    }
    fun weekday(ms: Long): String = date(ms, "EEE")
    fun dayMonth(ms: Long): String = date(ms, "dMMM")
    fun weekdayDayMonth(ms: Long): String = date(ms, "EEEdMMM")
    fun weekdayTime(ms: Long): String = date(ms, withTime("EEE"))
    fun dayMonthTime(ms: Long): String = date(ms, withTime("dMMM"))

    private fun narrow(vararg m: Measure): String =
        MeasureFormat.getInstance(ULocale.forLocale(locale()), MeasureFormat.FormatWidth.NARROW).formatMeasures(*m)

    /** Narrow durations: "5m", "2h", "1h 5m" in English, "5 Min.", "2 Std." in German. */
    fun minutes(n: Long): String = narrow(Measure(n, MeasureUnit.MINUTE))
    fun hours(n: Long): String = narrow(Measure(n, MeasureUnit.HOUR))
    fun seconds(n: Long): String = narrow(Measure(n, MeasureUnit.SECOND))
    fun hoursMinutes(h: Long, m: Long): String = narrow(Measure(h, MeasureUnit.HOUR), Measure(m, MeasureUnit.MINUTE))
    fun days(n: Long): String = narrow(Measure(n, MeasureUnit.DAY))

    /** Stopwatch reading under ten minutes ("4:07"). */
    fun stopwatch(sec: Long): String = String.format(locale(), "%d:%02d", sec / 60, sec % 60)

    /** US dollars (the only currency the server reports), with a third decimal for sub-cent amounts. */
    fun money(x: Double): String = NumberFormat.getCurrencyInstance(locale()).apply {
        currency = java.util.Currency.getInstance("USD")
        val d = if (x > 0 && x < 0.01) 3 else 2
        minimumFractionDigits = d; maximumFractionDigits = d
    }.format(x)

    fun int(n: Long): String = NumberFormat.getIntegerInstance(locale()).format(n)
    fun int(n: Int): String = int(n.toLong())

    fun decimal(x: Double, digits: Int): String = NumberFormat.getNumberInstance(locale()).apply {
        minimumFractionDigits = digits; maximumFractionDigits = digits
    }.format(x)

    /** A 0..1 fraction as a percentage ("85%", "85 %", "%85"). */
    fun percent(fraction: Double, digits: Int = 0): String = NumberFormat.getPercentInstance(locale()).apply {
        minimumFractionDigits = digits; maximumFractionDigits = digits
    }.format(fraction)

    /** Short counts: "950", "12K", "1.2M" in English. */
    fun compact(n: Long): String = CompactDecimalFormat.getInstance(locale(), CompactDecimalFormat.CompactStyle.SHORT).apply {
        maximumFractionDigits = 1
    }.format(n)

    /** File sizes with the platform's localized units. */
    fun size(bytes: Long): String = android.text.format.Formatter.formatShortFileSize(L10n.context(), bytes)
}
