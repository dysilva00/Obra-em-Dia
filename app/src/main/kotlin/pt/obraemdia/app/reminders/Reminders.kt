package pt.obraemdia.app.reminders

import pt.obraemdia.app.data.repo.AgendaFacts
import pt.obraemdia.app.data.repo.AlertKind
import pt.obraemdia.app.data.repo.Insights
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import pt.obraemdia.domain.schedule.TimeSlot
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Preferências dos lembretes, guardadas nas definições locais. */
data class ReminderPrefs(
    val enabled: Boolean = false,
    val time: LocalTime = LocalTime.of(8, 0),
    val tomorrow: Boolean = true,
    val overdue: Boolean = true,
    val holdReview: Boolean = true,
    val conflicts: Boolean = true,
) {
    fun toSettings(): Map<String, String> = mapOf(
        KEY_ENABLED to enabled.toString(),
        KEY_TIME to time.toString(),
        KEY_TOMORROW to tomorrow.toString(),
        KEY_OVERDUE to overdue.toString(),
        KEY_HOLD to holdReview.toString(),
        KEY_CONFLICTS to conflicts.toString(),
    )

    companion object {
        const val KEY_ENABLED = "reminders_enabled"
        const val KEY_TIME = "reminders_time"
        const val KEY_TOMORROW = "reminders_tomorrow"
        const val KEY_OVERDUE = "reminders_overdue"
        const val KEY_HOLD = "reminders_hold"
        const val KEY_CONFLICTS = "reminders_conflicts"
        /** Assinatura do último resumo enviado: não repetir o mesmo aviso. */
        const val KEY_LAST_DIGEST = "reminders_last_digest"

        fun from(map: Map<String, String>) = ReminderPrefs(
            enabled = map[KEY_ENABLED] == "true",
            time = map[KEY_TIME]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(8, 0),
            tomorrow = map[KEY_TOMORROW] != "false",
            overdue = map[KEY_OVERDUE] != "false",
            holdReview = map[KEY_HOLD] != "false",
            conflicts = map[KEY_CONFLICTS] != "false",
        )
    }
}

/** Resumo diário: um título e linhas curtas. Vazio = não notificar. */
data class Digest(val title: String, val lines: List<String>) {
    val isEmpty get() = lines.isEmpty()
    /** Muda quando o conteúdo muda; serve para não repetir o mesmo resumo. */
    val signature: String get() = lines.joinToString("|").hashCode().toString()
}

object Reminders {

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Resume o que interessa saber de manhã. Usa os mesmos alertas do ecrã Início, para a
     * notificação e a app dizerem sempre o mesmo.
     */
    fun digest(snapshot: Snapshot, agenda: AgendaFacts, today: LocalDate, prefs: ReminderPrefs): Digest {
        val lines = mutableListOf<String>()

        if (prefs.tomorrow) {
            val tomorrow = TimeSlot.wholeDay(today.plusDays(1))
            agenda.bookings.map { it.first }
                .filter { it.slot.overlaps(tomorrow) }
                .sortedBy { it.slot.start }
                .forEach { b ->
                    val who = b.workerIds.sorted().joinToString(" e ") { snapshot.workerName(it) }
                    lines += "Amanhã ${b.slot.start.atZone(AGENDA_ZONE).format(hhmm)}: ${b.label} ($who)"
                }
        }

        val alerts = Insights.alerts(snapshot, agenda, today)
        alerts.forEach { a ->
            val wanted = when (a.kind) {
                AlertKind.OVERDUE_RECEIVABLE, AlertKind.OVERDUE_PAYABLE -> prefs.overdue
                AlertKind.HOLD_REVIEW -> prefs.holdReview
                AlertKind.CONFLICT -> prefs.conflicts
                // Dados em falta ficam só na app: não justificam uma notificação diária.
                AlertKind.MISSING_DATA -> false
            }
            if (wanted) lines += a.text
        }

        val title = when {
            lines.isEmpty() -> ""
            lines.size == 1 -> "Obra em Dia: 1 aviso"
            else -> "Obra em Dia: ${lines.size} avisos"
        }
        return Digest(title, lines)
    }

    /** O resumo só é enviado se tiver conteúdo e for diferente do último enviado. */
    fun shouldNotify(digest: Digest, settings: Map<String, String>): Boolean =
        !digest.isEmpty && settings[ReminderPrefs.KEY_LAST_DIGEST] != digest.signature}
