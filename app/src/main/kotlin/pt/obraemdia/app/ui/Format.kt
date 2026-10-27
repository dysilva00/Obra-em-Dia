package pt.obraemdia.app.ui

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val PT: Locale = Locale.forLanguageTag("pt-PT")

private val dayFormat = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", PT)
private val shortDayFormat = DateTimeFormatter.ofPattern("d MMM", PT)

fun LocalDate.longLabel(): String = format(dayFormat).replaceFirstChar { it.titlecase(PT) }
fun LocalDate.shortLabel(): String = format(shortDayFormat)
fun YearMonth.label(): String =
    "${month.getDisplayName(TextStyle.FULL_STANDALONE, PT).replaceFirstChar { it.titlecase(PT) }} $year"

/** 965 → "16 h 05 min"; 480 → "8 h". */
fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h %02d min".format(m)
    }
}
