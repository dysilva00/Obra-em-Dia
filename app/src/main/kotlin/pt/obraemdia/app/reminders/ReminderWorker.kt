package pt.obraemdia.app.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import pt.obraemdia.app.MainActivity
import pt.obraemdia.app.R
import pt.obraemdia.app.container
import pt.obraemdia.app.data.db.SettingEntity
import pt.obraemdia.app.data.repo.AgendaFacts
import pt.obraemdia.app.data.repo.Settings
import pt.obraemdia.app.data.repo.toBookings
import pt.obraemdia.app.data.repo.toUnavailabilities
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import pt.obraemdia.domain.schedule.ConflictDetector
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Verificação diária feita pelo WorkManager, mesmo com a app fechada e sem internet.
 * Envia no máximo um resumo por dia, e só se o conteúdo mudou desde o último.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val snapshot = c.repository.snapshot.first()
        val prefs = ReminderPrefs.from(snapshot.settings)
        val force = inputData.getBoolean(KEY_FORCE, false)
        if (!prefs.enabled && !force) return Result.success()

        val today = LocalDate.now(AGENDA_ZONE)
        val from = today.minusDays(1).atStartOfDay(AGENDA_ZONE).toInstant().toEpochMilli()
        val to = today.plusDays(8).atStartOfDay(AGENDA_ZONE).toInstant().toEpochMilli()
        val (rows, timeOff) = c.repository.schedule(from, to).first()
        val bookings = rows.toBookings()
        val unavailable = timeOff.toUnavailabilities()
        val margin = Duration.ofMinutes(snapshot.settings[Settings.TRAVEL_MARGIN_MINUTES]?.toLongOrNull() ?: 0)
        val detector = ConflictDetector(margin)
        val agenda = AgendaFacts(bookings.map { it to detector.conflictsFor(it, bookings, unavailable) })

        val digest = Reminders.digest(snapshot, agenda, today, prefs)
        if (force && digest.isEmpty) {
            show(applicationContext, Digest("Obra em Dia", listOf("Sem avisos para hoje.")))
            return Result.success()
        }
        if (!force && !Reminders.shouldNotify(digest, snapshot.settings)) return Result.success()
        if (show(applicationContext, digest)) {
            c.database.settingsDao().put(SettingEntity(ReminderPrefs.KEY_LAST_DIGEST, digest.signature))
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "lembrete-diario"
        private const val KEY_FORCE = "force"
        const val CHANNEL = "lembretes"
        private const val NOTIFICATION_ID = 1001

        fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        /** (Re)agenda o lembrete diário à hora escolhida, ou cancela-o se estiver desligado. */
        fun schedule(context: Context, prefs: ReminderPrefs, now: ZonedDateTime = ZonedDateTime.now(AGENDA_ZONE)) {
            val wm = WorkManager.getInstance(context)
            if (!prefs.enabled) {
                wm.cancelUniqueWork(UNIQUE)
                return
            }
            var next = now.toLocalDate().atTime(prefs.time).atZone(AGENDA_ZONE)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Corre já uma vez (botão "Experimentar agora"). */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<ReminderWorker>().setInputData(workDataOf(KEY_FORCE to true)).build(),
            )
        }

        /** Mostra o resumo como uma única notificação (substitui a anterior, nunca acumula). */
        fun show(context: Context, digest: Digest): Boolean {
            if (!canNotify(context)) return false
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Lembretes diários", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Resumo da manhã: marcações de amanhã, valores vencidos e obras a rever"
                },
            )
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val style = NotificationCompat.InboxStyle().also { s -> digest.lines.take(6).forEach(s::addLine) }
            if (digest.lines.size > 6) style.setSummaryText("+ ${digest.lines.size - 6} na app")
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(digest.title)
                .setContentText(digest.lines.first())
                .setStyle(style)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            @Suppress("MissingPermission") // verificada em canNotify
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            return true
        }
    }
}
