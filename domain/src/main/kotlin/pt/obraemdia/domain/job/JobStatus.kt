package pt.obraemdia.domain.job

import java.time.LocalDate

enum class JobStatus { TO_CONFIRM, SCHEDULED, IN_PROGRESS, ON_HOLD, COMPLETED, CANCELLED }

enum class HoldReason { MATERIAL, CLIENT, WEATHER, OTHER }

enum class Priority { LOW, NORMAL, HIGH, URGENT }

/** Pedido de mudança de estado, com os dados que algumas transições exigem. */
data class StatusChange(
    val target: JobStatus,
    val holdReason: HoldReason? = null,
    val holdNote: String? = null,
    val reviewDate: LocalDate? = null,
)

sealed interface TransitionResult {
    data object Allowed : TransitionResult
    data class Rejected(val message: String) : TransitionResult
}

object JobTransitions {

    /**
     * Valida uma mudança do estado do trabalho.
     * O estado de pagamento é independente: concluir não implica liquidar, nem o contrário.
     * Encerrar é uma ação à parte (ver [ClosureCheck]), não um estado.
     */
    fun validate(current: JobStatus, change: StatusChange, hasValidBooking: Boolean): TransitionResult {
        val target = change.target
        if (current == target) return TransitionResult.Rejected("A obra já está neste estado")
        if (current == JobStatus.CANCELLED) {
            return TransitionResult.Rejected("Obra cancelada: crie uma nova ou reabra com motivo")
        }
        return when (target) {
            JobStatus.SCHEDULED ->
                if (hasValidBooking) TransitionResult.Allowed
                else TransitionResult.Rejected("Para marcar como agendada, crie primeiro uma reserva")
            JobStatus.ON_HOLD -> when {
                change.holdReason == null -> TransitionResult.Rejected("Indique o motivo da espera")
                change.holdReason == HoldReason.OTHER && change.holdNote.isNullOrBlank() ->
                    TransitionResult.Rejected("Descreva o motivo da espera")
                else -> TransitionResult.Allowed
            }
            JobStatus.TO_CONFIRM ->
                if (current == JobStatus.ON_HOLD || current == JobStatus.SCHEDULED) TransitionResult.Allowed
                else TransitionResult.Rejected("Só é possível voltar a \"Por confirmar\" a partir de agendada ou em espera")
            JobStatus.IN_PROGRESS, JobStatus.COMPLETED, JobStatus.CANCELLED -> TransitionResult.Allowed
        }
    }
}

/** Nomes por extenso para a interface (pt-PT); nunca distinguir estados só pela cor. */
val JobStatus.label: String
    get() = when (this) {
        JobStatus.TO_CONFIRM -> "Por confirmar"
        JobStatus.SCHEDULED -> "Agendada"
        JobStatus.IN_PROGRESS -> "Em curso"
        JobStatus.ON_HOLD -> "Em espera"
        JobStatus.COMPLETED -> "Concluída"
        JobStatus.CANCELLED -> "Cancelada"
    }

val Priority.label: String
    get() = when (this) {
        Priority.LOW -> "Baixa"
        Priority.NORMAL -> "Normal"
        Priority.HIGH -> "Alta"
        Priority.URGENT -> "Urgente"
    }
