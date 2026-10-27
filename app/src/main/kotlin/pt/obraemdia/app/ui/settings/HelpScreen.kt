package pt.obraemdia.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Start
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import pt.obraemdia.app.ui.components.IconTile

/** Um tema da ajuda: passos curtos, com os nomes dos botões tal como aparecem na app. */
private data class HelpTopic(val icon: ImageVector, val title: String, val steps: List<String>)

private val topics = listOf(
    HelpTopic(
        Icons.Filled.Start, "Começar a usar",
        listOf(
            "Na roda dentada (canto de cima, à direita) ficam as Definições.",
            "Em Definições, ponha os nomes e as taxas por hora de cada pessoa, o horário de trabalho e o saldo inicial (o dinheiro que a atividade tem hoje).",
            "Pode ter uma só pessoa ou várias: use \"Adicionar pessoa\" ou \"Remover\".",
            "As obras de exemplo servem só para experimentar. Quando quiser começar a sério, toque em \"Remover\" na faixa amarela do topo.",
        ),
    ),
    HelpTopic(
        Icons.Filled.Schedule, "Registar horas",
        listOf(
            "No Início, toque em \"Registar horas\" (ou em \"Horas\" dentro da obra).",
            "Escolha a obra, quem trabalhou e o dia. \"Os dois\" cria um registo para cada pessoa.",
            "Indique a duração (por exemplo 8 h) ou o início, o fim e a pausa: a app faz a conta.",
            "\"Repetir último\" preenche tudo igual ao último registo. Confirme e grave.",
            "Cronómetro: toque em \"Começar agora com cronómetro\". Fica a contar no Início, mesmo com a app fechada. No fim, toque em \"Parar e registar\".",
            "Se a mesma pessoa já tiver horas à mesma hora, a app avisa. Só grava se confirmar que está certo.",
        ),
    ),
    HelpTopic(
        Icons.Filled.ShoppingCart, "Gastos e faturas de compra",
        listOf(
            "No Início, toque em \"Adicionar gasto\" (ou em \"Gasto\" dentro da obra).",
            "Com uma fatura em papel, toque em \"Ler QR\" e aponte a câmara ao código quadrado. Com uma fatura em PDF, toque em \"Importar PDF\". O fornecedor, o número e os valores ficam preenchidos.",
            "Sem código QR, escreva o total à mão.",
            "Escolha a obra a que o gasto pertence, ou reparta por várias.",
            "Se ainda não pagou, escolha \"A crédito (por pagar)\". Fica em Contas, em \"Por pagar a fornecedores\", com o botão \"Pagar\".",
            "Guias de remessa e pró-formas não são faturas: a app não as aceita como gasto.",
        ),
    ),
    HelpTopic(
        Icons.Filled.Euro, "Receber pagamentos dos clientes",
        listOf(
            "No Início, toque em \"Registar pagamento\" (ou em \"Receber\" dentro da obra).",
            "Escolha a obra e escreva o valor recebido. A app mostra quanto falta receber.",
            "Se combinou pagar às prestações, registe-as na ficha da obra, em \"Nova prestação\". A app avisa quando uma passar do prazo.",
        ),
    ),
    HelpTopic(
        Icons.Filled.CalendarMonth, "Agenda",
        listOf(
            "Na Agenda, toque num dia e depois em \"Agendar neste dia\" (ou em \"Agendar\" dentro da obra).",
            "Pode marcar o dia inteiro, só a manhã, só a tarde ou um horário, e vários dias de uma vez.",
            "\"Pré-reserva\" serve para obras ainda por confirmar.",
            "Se alguém já estiver ocupado, a app avisa e sugere outro dia livre.",
            "Férias e outras ausências marcam-se em Definições, em \"Indisponibilidades\".",
        ),
    ),
    HelpTopic(
        Icons.Filled.Construction, "Obras",
        listOf(
            "Em Obras, toque em \"Nova obra\". Para o cliente, pode tocar em \"Escolher dos contactos\".",
            "Na ficha da obra estão as horas, os gastos, os pagamentos e quanto falta receber. Há também os botões \"Ligar\" ao cliente e \"Ver no mapa\".",
            "\"Mudar estado\": por confirmar, agendada, em curso, em espera, concluída…",
            "Trabalho a mais combinado com o cliente regista-se como extra. Mudar o preço combinado faz-se em \"Alterar acordo\", sempre com o motivo.",
            "No fim, toque em \"Encerrar obra\": a app mostra o balanço e o que falta resolver. Se for preciso mudar alguma coisa depois, use \"Reabrir obra\".",
        ),
    ),
    HelpTopic(
        Icons.Filled.PhotoCamera, "Fotos, notas e lista de compras",
        listOf(
            "Na ficha da obra, toque em \"Notas, fotos e checklist\".",
            "Tire fotos de antes, durante e depois, ou escolha-as da galeria.",
            "Há também uma lista de tarefas, uma lista de compras e notas livres.",
            "As fotos ficam só neste telemóvel. A cópia de segurança leva-as consigo.",
        ),
    ),
    HelpTopic(
        Icons.AutoMirrored.Filled.ShowChart, "Contas e projeções",
        listOf(
            "O \"Saldo acumulado\" do Início é o que a app conta (saldo inicial + recebido − pago). Não é o saldo do banco.",
            "Em Contas estão os movimentos do mês e as compras por pagar.",
            "\"Projeções\" mostra como fica o dinheiro nos próximos 30, 60 e 90 dias com o que está combinado.",
            "\"Exportar PDF\" faz o resumo do mês, o balanço de uma obra ou um resumo para mostrar ao cliente.",
        ),
    ),
    HelpTopic(
        Icons.AutoMirrored.Filled.ReceiptLong, "Faturas emitidas aos clientes",
        listOf(
            "Em Contas, toque em \"Faturas emitidas\".",
            "Use \"Ler QR\" (com a câmara) ou \"Importar PDF\": a app lê o total de cada fatura.",
            "Vê quanto já foi faturado no ano, e pode definir um limite para a app avisar quando se aproximar.",
            "Registar aqui não envia nada às Finanças: serve só para controlo.",
        ),
    ),
    HelpTopic(
        Icons.Filled.History, "Corrigir um engano",
        listOf(
            "Nada se apaga de vez: as correções ficam registadas.",
            "Horas: na ficha da obra, toque no registo e escolha corrigir ou anular.",
            "Pagamentos: em Contas, toque no movimento e use \"Estornar\".",
            "Compras: em Contas, toque na compra e use \"Anular\".",
            "Em Definições, \"Histórico de alterações\" mostra tudo o que foi mudado e quando.",
        ),
    ),
    HelpTopic(
        Icons.Filled.Backup, "Cópia de segurança (importante)",
        listOf(
            "Os dados só existem neste telemóvel. Se o telemóvel se perder ou avariar, só uma cópia guardada fora dele os recupera.",
            "Pelo menos uma vez por mês: Definições → \"Cópia de segurança\" → \"Guardar cópia de segurança\" → guarde no Google Drive.",
            "A app avisa no Início quando a última cópia tiver mais de 30 dias.",
            "Telemóvel novo: instale a app, vá a \"Cópia de segurança\" → \"Escolher ficheiro de cópia\" e escolha o ficheiro guardado.",
            "Não desinstale a app nem use \"Limpar armazenamento\" sem ter uma cópia recente.",
        ),
    ),
    HelpTopic(
        Icons.Filled.NotificationsActive, "Lembretes",
        listOf(
            "Em Definições, ligue o resumo diário e escolha a hora.",
            "Todas as manhãs chega uma notificação com o trabalho de amanhã, os pagamentos atrasados e os outros avisos.",
            "O mesmo aviso não chega duas vezes.",
        ),
    ),
)

@Composable
fun HelpScreen() {
    var open by rememberSaveable { mutableIntStateOf(-1) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Toque num tema para ver os passos.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        itemsIndexed(topics) { index, topic ->
            val expanded = open == index
            Surface(
                onClick = { open = if (expanded) -1 else index },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "Aberto" else "Fechado" },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                        IconTile(topic.icon)
                        Text(topic.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                    }
                    AnimatedVisibility(expanded) {
                        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            topic.steps.forEachIndexed { i, step ->
                                Row {
                                    Text("${i + 1}.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp))
                                    Text(step, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
