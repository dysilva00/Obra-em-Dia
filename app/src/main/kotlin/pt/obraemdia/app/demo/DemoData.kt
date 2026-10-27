package pt.obraemdia.app.demo

import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.ClientBalance
import pt.obraemdia.domain.billing.JobResult
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.billing.SaleCalculator
import pt.obraemdia.domain.billing.SaleInputs
import pt.obraemdia.domain.billing.TimeEntryFacts
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.domain.money.HourlyRate
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.money.sum
import pt.obraemdia.domain.schedule.Booking
import pt.obraemdia.domain.schedule.BookingFirmness
import pt.obraemdia.domain.schedule.ConflictDetector
import pt.obraemdia.domain.schedule.DayPart
import pt.obraemdia.domain.schedule.WorkHours
import java.time.LocalDate
import java.time.YearMonth

/**
 * Dados fictícios para o protótipo navegável. Nomes, valores e moradas são inventados.
 * Os totais são calculados com as regras reais do módulo :domain.
 */
object DemoData {

    data class Worker(val id: String, val name: String)

    data class DemoJob(
        val id: String,
        val title: String,
        val client: String?,
        val address: String,
        val status: JobStatus,
        val priority: Priority,
        val mode: BillingMode,
        val sale: SaleInputs,
        val costs: Money,
        val received: List<Money>,
    ) {
        val saleValue get() = SaleCalculator.saleValue(sale)
        val balance get() = ClientBalance(saleValue, received)
        val result get() = JobResult.of(saleValue, costs, sale.timeEntries)
        val hoursMinutes get() = sale.timeEntries.sumOf { it.minutes }
    }

    data class DemoMovement(val date: LocalDate, val description: String, val amount: Money, val jobTitle: String?)

    val today: LocalDate = LocalDate.of(2026, 10, 28)
    val month: YearMonth = YearMonth.from(today)
    val hours = WorkHours()

    val workers = listOf(Worker("p1", "Pessoa 1"), Worker("p2", "Pessoa 2"))

    private fun entries(days: Int, sale: Long, cost: Long) = workers.flatMap { w ->
        List(days) { TimeEntryFacts(w.id, 8 * 60, true, HourlyRate.euros(sale), HourlyRate.euros(cost)) }
    }

    val jobs = listOf(
        DemoJob(
            id = "j1", title = "Casa de banho — Casa da Ana", client = "Ana (exemplo)",
            address = "Rua Exemplo 12, Lisboa", status = JobStatus.IN_PROGRESS, priority = Priority.HIGH,
            mode = BillingMode.FIXED_PRICE,
            sale = SaleInputs(
                BillingMode.FIXED_PRICE, MaterialsPolicy.INCLUDED,
                approvedFixedPrice = Money.euros(3000), timeEntries = entries(2, 0, 15),
            ),
            costs = Money.euros(700), received = listOf(Money.euros(1000)),
        ),
        DemoJob(
            id = "j2", title = "Pintura de muro", client = null,
            address = "Travessa Fictícia 3, Amadora", status = JobStatus.SCHEDULED, priority = Priority.NORMAL,
            mode = BillingMode.HOURLY,
            sale = SaleInputs(
                BillingMode.HOURLY, MaterialsPolicy.BILLED_SEPARATELY,
                materialsBilledToClient = listOf(Money.euros(120)), timeEntries = entries(1, 22, 15),
            ),
            costs = Money.euros(95), received = emptyList(),
        ),
        DemoJob(
            id = "j3", title = "Reparação de telhado", client = "Sr. Exemplo",
            address = "Largo Inventado 7, Sintra", status = JobStatus.ON_HOLD, priority = Priority.URGENT,
            mode = BillingMode.FIXED_PRICE,
            sale = SaleInputs(BillingMode.FIXED_PRICE, MaterialsPolicy.BILLED_SEPARATELY, approvedFixedPrice = Money.euros(1800)),
            costs = Money.ZERO, received = listOf(Money.euros(500)),
        ),
        DemoJob(
            id = "j4", title = "Assentar mosaico — cozinha", client = "Maria (exemplo)",
            address = "Avenida Teste 45, Odivelas", status = JobStatus.COMPLETED, priority = Priority.NORMAL,
            mode = BillingMode.FIXED_PRICE,
            sale = SaleInputs(BillingMode.FIXED_PRICE, MaterialsPolicy.INCLUDED, approvedFixedPrice = Money.euros(1200), timeEntries = entries(1, 0, 15)),
            costs = Money.euros(310), received = listOf(Money.euros(600)),
        ),
    )

    val bookings = listOf(
        Booking("b1", "j1", "s1", "Casa da Ana", setOf("p1", "p2"), hours.slotFor(today, DayPart.FULL_DAY)),
        Booking("b2", "j1", "s1", "Casa da Ana", setOf("p1", "p2"), hours.slotFor(today.plusDays(1), DayPart.MORNING)),
        Booking("b3", "j2", "s2", "Pintura de muro", setOf("p2"), hours.slotFor(today.plusDays(1), DayPart.MORNING), BookingFirmness.TENTATIVE),
        Booking("b4", "j2", "s2", "Pintura de muro", setOf("p2"), hours.slotFor(today.plusDays(2), DayPart.FULL_DAY)),
        Booking("b5", "j1", "s1", "Casa da Ana", setOf("p1"), hours.slotFor(today.plusDays(2), DayPart.AFTERNOON)),
    )

    /** Conflitos reais calculados pelo detetor do domínio sobre as reservas fictícias. */
    val conflicts by lazy {
        bookings.associate { b -> b.id to ConflictDetector().conflictsFor(b, bookings, emptyList()) }
            .filterValues { it.isNotEmpty() }
    }

    val movements = listOf(
        DemoMovement(month.atDay(3), "Adiantamento — Casa da Ana", Money.euros(1000), "Casa de banho — Casa da Ana"),
        DemoMovement(month.atDay(5), "Loja de materiais (exemplo)", Money.euros(-450), "Casa de banho — Casa da Ana"),
        DemoMovement(month.atDay(9), "Adiantamento — telhado", Money.euros(500), "Reparação de telhado"),
        DemoMovement(month.atDay(12), "Combustível", Money(-6540), null),
        DemoMovement(month.atDay(15), "Pagamento parcial — cozinha", Money.euros(600), "Assentar mosaico — cozinha"),
        DemoMovement(month.atDay(20), "Loja de materiais (exemplo)", Money.euros(-250), "Casa de banho — Casa da Ana"),
    )

    val openingBalance = Money.euros(2500)

    val monthReceived get() = movements.filter { it.amount.isPositive }.map { it.amount }.sum()
    val monthPaid get() = movements.filter { it.amount.isNegative }.map { -it.amount }.sum()
    val toReceive get() = jobs.map { it.balance.balance }.filter { it.isPositive }.sum()

    fun workerName(id: String) = workers.first { it.id == id }.name
}
