package pt.obraemdia.app.data.repo

import kotlinx.coroutines.flow.Flow
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.db.AuditEventEntity
import pt.obraemdia.app.data.db.SettingEntity
import pt.obraemdia.app.data.db.TimeOffEntity
import pt.obraemdia.domain.money.HourlyRate
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import pt.obraemdia.domain.schedule.TimeSlot
import pt.obraemdia.domain.schedule.WorkHours
import java.time.LocalDate

/**
 * Configuração local da atividade: pessoas e taxas habituais, saldo inicial, horário, margem
 * de deslocação e indisponibilidades. Tudo fica no histórico de alterações.
 */
class BusinessSettings(private val db: AppDatabase, private val repo: ObraRepository, private val clock: () -> Long) {

    val timeOff: Flow<List<TimeOffEntity>> = db.scheduleDao().observeAllTimeOff()

    /** As taxas só pré-preenchem novos registos; as horas já registadas guardam as suas. */
    suspend fun updateWorker(id: String, name: String, saleRate: HourlyRate, costRate: HourlyRate) {
        require(name.isNotBlank()) { "O nome é obrigatório" }
        repo.transaction {
            val w = requireNotNull(db.workerDao().getAll().firstOrNull { it.id == id }) { "Pessoa não encontrada" }
            db.workerDao().upsert(w.copy(name = name.trim(), defaultSaleRateCents = saleRate.centsPerHour, defaultCostRateCents = costRate.centsPerHour, updatedAt = clock()))
            audit("worker", id, "update", "${w.name} ${w.defaultSaleRateCents}/${w.defaultCostRateCents} → ${name.trim()} ${saleRate.centsPerHour}/${costRate.centsPerHour}")
        }
    }

    /** Saldo inicial, margem e horário numa só transação. O construtor de [WorkHours] valida a ordem dos horários. */
    suspend fun saveBusiness(opening: Money, marginMinutes: Int, h: WorkHours) = repo.transaction {
        require(marginMinutes in 0..240) { "Margem entre 0 e 240 minutos" }
        put(Settings.OPENING_BALANCE_CENTS, opening.cents.toString())
        put(Settings.TRAVEL_MARGIN_MINUTES, marginMinutes.toString())
        put(Settings.MORNING_START, h.morningStart.toString())
        put(Settings.MORNING_END, h.morningEnd.toString())
        put(Settings.AFTERNOON_START, h.afternoonStart.toString())
        put(Settings.AFTERNOON_END, h.afternoonEnd.toString())
    }

    /** Preferências dos lembretes (só locais; o agendamento é feito por quem chama). */
    suspend fun saveReminders(prefs: pt.obraemdia.app.reminders.ReminderPrefs) = repo.transaction {
        prefs.toSettings().forEach { (k, v) -> put(k, v) }
    }

    /** Indisponibilidade de dias completos [from, to]. Devolve quantas marcações firmes ou pré-reservas colidem. */
    suspend fun addTimeOff(workerId: String, from: LocalDate, to: LocalDate, reason: String): Int {
        require(reason.isNotBlank()) { "Indique o motivo" }
        require(!to.isBefore(from)) { "A data final é anterior à inicial" }
        val slot = TimeSlot(TimeSlot.wholeDay(from, AGENDA_ZONE).start, TimeSlot.wholeDay(to, AGENDA_ZONE).end)
        return repo.transaction {
            val now = clock()
            val id = newId()
            db.scheduleDao().insertTimeOff(TimeOffEntity(id, workerId, slot.start.toEpochMilli(), slot.end.toEpochMilli(), reason.trim(), now, now))
            audit("time_off", id, "create", "$workerId $from–$to · $reason")
            // Não move nada: só informa das marcações afetadas.
            db.scheduleDao().getRange(slot.start.toEpochMilli(), slot.end.toEpochMilli()).count { it.workerId == workerId }
        }
    }

    suspend fun removeTimeOff(id: String) = repo.transaction {
        val t = requireNotNull(db.scheduleDao().getTimeOff(id)) { "Indisponibilidade não encontrada" }
        db.scheduleDao().updateTimeOff(t.copy(deletedAt = clock(), updatedAt = clock()))
        audit("time_off", id, "delete", t.reason)
    }

    private suspend fun put(key: String, value: String) {
        val old = db.settingsDao().get(key)
        db.settingsDao().put(SettingEntity(key, value))
        audit("setting", key, "update", "$old → $value")
    }

    private suspend fun audit(entity: String, entityId: String, action: String, detail: String) {
        db.auditDao().insert(AuditEventEntity(newId(), entity, entityId, action, detail, clock()))
    }
}
