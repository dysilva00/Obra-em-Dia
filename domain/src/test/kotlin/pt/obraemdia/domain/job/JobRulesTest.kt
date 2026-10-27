package pt.obraemdia.domain.job

import org.junit.jupiter.api.Test
import pt.obraemdia.domain.money.Money
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JobRulesTest {

    @Test
    fun `agendar exige reserva valida`() {
        val change = StatusChange(JobStatus.SCHEDULED)
        assertIs<TransitionResult.Rejected>(JobTransitions.validate(JobStatus.TO_CONFIRM, change, hasValidBooking = false))
        assertIs<TransitionResult.Allowed>(JobTransitions.validate(JobStatus.TO_CONFIRM, change, hasValidBooking = true))
    }

    @Test
    fun `em espera exige motivo`() {
        assertIs<TransitionResult.Rejected>(
            JobTransitions.validate(JobStatus.IN_PROGRESS, StatusChange(JobStatus.ON_HOLD), true),
        )
        assertIs<TransitionResult.Rejected>(
            JobTransitions.validate(JobStatus.IN_PROGRESS, StatusChange(JobStatus.ON_HOLD, HoldReason.OTHER), true),
        )
        assertIs<TransitionResult.Allowed>(
            JobTransitions.validate(JobStatus.IN_PROGRESS, StatusChange(JobStatus.ON_HOLD, HoldReason.WEATHER), true),
        )
    }

    private val readyToClose = ClosureFacts(
        status = JobStatus.COMPLETED,
        finalPriceConfirmed = true,
        extrasReviewed = true,
        clientBalance = Money.ZERO,
        hoursAndCostsReviewed = true,
        unpaidSupplierAmount = Money.ZERO,
    )

    @Test
    fun `obra concluida por pagar nao pode ser encerrada`() {
        assertFalse(ClosureCheck.canClose(readyToClose.copy(clientBalance = Money.euros(2000))))
        assertFalse(ClosureCheck.canClose(readyToClose.copy(clientBalance = Money.euros(-50))))
        assertTrue(ClosureCheck.canClose(readyToClose))
    }

    @Test
    fun `fornecedor por pagar avisa mas nao impede o fecho`() {
        val facts = readyToClose.copy(unpaidSupplierAmount = Money.euros(120))

        assertTrue(ClosureCheck.canClose(facts))
        assertEquals(1, ClosureCheck.issues(facts).filterIsInstance<ClosureIssue.Warning>().size)
    }
}
