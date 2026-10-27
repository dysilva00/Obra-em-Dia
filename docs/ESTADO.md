# Estado do desenvolvimento

Legenda: ✅ concluído e verificado · 🟡 parcial · ⬜ pendente

## Verificação

- `gradlew :domain:test`: 46 testes de regras (previsão de caixa, dinheiro, IVA, repartição, custos e IVA recuperável, tipos de documento, NIF, agenda/conflitos, sugestão de dias livres, cobrança, saldos, estados, encerramento).
- `gradlew connectedDebugAndroidTest`: 81 testes no emulador (idempotência, taxas históricas, transações, chaves estrangeiras, remoção de demonstração, agendamento com conflitos, estados, cancelamento, gastos/repartições/duplicados/crédito/notas de crédito, estornos, correção e anulação de horas, anulação de compras, encerramento/bloqueio/reabertura/revisões, acordo/extras/materiais/edição, definições/indisponibilidades, previsão e alertas, PDFs válidos e resumo do cliente sem dados internos, fotos/notas/checklist, lembretes, migrações v1→…→v9, horas com início/fim, sobreposições e cronómetro, faturas emitidas, leitura de QR em PDF, backup/restauro incluindo pacotes corrompidos, zip slip, versão mais recente e restauro interrompido).
  **Atenção:** este comando desinstala a app no fim e apaga os dados do emulador.
- Verificado manualmente no emulador (API 37): navegação, letra a 130 %, registo de pagamento com duplo toque (gravado uma única vez) e persistência depois de fechar a app à força.

## Fase 0 — Esqueleto e provas técnicas

- ✅ Projeto Gradle: AGP 9.4.1, Kotlin 2.4.21, Gradle 9.6.0, compileSdk/targetSdk 37, minSdk 26.
- ✅ Base de dados Room v1 com SQLite incluído: pessoas, clientes, locais, obras, reservas, indisponibilidades, horas, movimentos de caixa, auditoria e definições.
- ✅ Dados de demonstração gravados e marcados, com a ação "Remover".
- ✅ Ecrãs com dados reais: Início (mês navegável), Agenda (conflitos e indisponibilidades), Obras (lista, pesquisa e ficha) e Contas.
- ✅ Formulários reais: Nova obra, Registar horas (incluindo "Os dois") e Registar pagamento.
- ✅ Agendar obra: vários dias (descontínuos), dia completo/manhã/tarde/horas, pré-reserva, conflitos detetados enquanto se preenche e de novo na gravação, com as 3 saídas (outro dia sugerido, outra pessoa livre, manter com motivo).
- ✅ Vista de calendário mensal na Agenda (com filtro por pessoa e "Agendar neste dia") e na ficha da obra (dias marcados dessa obra).
- ✅ Mudar estado da obra com as regras do domínio; cancelar liberta as reservas futuras.
- ✅ Esquema v6 com migrações automáticas testadas (v1→…→v6).
- ✅ Adicionar gasto: tipos de documento (guia/pró-forma/recibo recusados com explicação), fornecedor/NIF validado/nº, categoria, IVA incluído e se é recuperável (por definir por omissão), uma obra / despesa geral / repartição com valor por classificar visível, pago agora ou a crédito com vencimento, notas de crédito.
- ✅ Duplicados de compras bloqueados (mesma operação, NIF+tipo+número, ATCUD).
- ✅ Contas: por pagar a fornecedores (com vencidas) e ação Pagar (parcial ou total), por classificar.
- ✅ Custos reais no resultado da obra (IVA recuperável fora do custo; por classificar entra e é sinalizado).
- ✅ Correções auditáveis: estornar recebimentos/pagamentos (com motivo), corrigir ou anular horas, anular documentos de compra (estorna os pagamentos).
- ✅ Revisão de encerramento com balanço, verificação (bloqueios e avisos), responsável opcional e notas; encerramento transacional e versionado; obra encerrada bloqueia alterações financeiras; reabrir com motivo cria nova revisão.
- ✅ Editar obra (título, notas, cliente existente ou novo com telefone, morada, prioridade, política de materiais); Ligar e Ver no mapa.
- ✅ Acordo e extras: alterar preço/IVA/modalidade com motivo e histórico; extras aprovados e materiais cobrados ao cliente (com evidência, "já incluído"), anuláveis com motivo.
- ✅ Definições (roda dentada no topo): nome e taxas habituais de cada pessoa, saldo inicial, margem de deslocação, horário de trabalho (usado nas marcações), indisponibilidades com aviso de marcações em conflito.
- ✅ Número de pessoas configurável (adicionar/remover em Definições); com 1 pessoa os seletores "Quem" desaparecem.
- ✅ Plano de pagamentos (prestações) por obra, com estado de cada prestação.
- ✅ Projeções 30/60/90 dias (Contas → Projeções e Início): cenário confirmado vs. com propostas, por mês, ponto mais baixo, vencidos, previsão incompleta, adiamento simulado e pressupostos.
- ✅ Alertas no Início: prestações e faturas vencidas, sobreposições, obras em espera a rever, dados em falta.
- ✅ Exportação em PDF: balanço da obra (interno), resumo para o cliente (sem custos nem taxas internas) e relatório do mês; partilhar ou guardar no telemóvel. CSV dispensado pelo utilizador.
- ✅ Notas, fotos e checklist por obra: fotos antes/durante/depois (câmara ou galeria, copiadas e comprimidas para a pasta privada), legendas, tarefas e lista de compras, notas editáveis. Verificado no emulador com a câmara virtual.
- ✅ Lembretes: resumo diário configurável (hora e tipos), com a app fechada, sem repetir o mesmo aviso; permissão pedida só ao ligar. Verificado no emulador (notificação real).
- ✅ Faturas emitidas: ler QR (câmara), importar PDF (lê o QR da AT dentro do PDF) ou à mão; totais do ano por NIF (sem IVA, impostos, total, retenções), limite opcional, duplicados bloqueados, PDF anexado. Importação de PDF verificada no emulador; leitura pela câmara por verificar no aparelho real.
- ✅ Adicionar gasto: Ler QR e Importar PDF preenchem NIF, tipo, número, ATCUD, data, total e base/IVA exatos; PDF guardado como comprovativo; duplicados também por ATCUD e conteúdo do QR.
- ✅ Histórico de alterações (Definições): todas as criações, correções, estornos, anulações, encerramentos e definições, com filtro por tipo.
- ✅ Ícone próprio da app (adaptativo, com versão monocromática para ícones temáticos).
- ✅ Design: Material 3 Expressive (beta) e Plus Jakarta Sans.
- ⬜ Prova no aparelho real: leitura de QR AT
- ✅ Registar horas por início, fim e pausa (ou só duração); "Repetir último" preenche obra, pessoas, horário e tipo do último registo.
- ✅ Aviso quando as horas da mesma pessoa se sobrepõem ou o dia passa das 16 h; só grava depois de confirmar.
- ✅ Cronómetro (um por pessoa, também para "Os dois"): começa em Registar horas, fica em destaque no Início e "Parar e registar" abre o formulário preenchido. Sobrevive a fechar a app.
- ✅ Cliente a partir dos contactos (Nova obra e Editar obra): copia só o nome e o telefone, sem pedir permissão. No emulador com Android 17 abre o novo seletor privado de contactos.
- ✅ Aviso no ecrã da cópia de segurança de que desinstalar ou limpar os dados apaga tudo.
- ✅ Prova no aparelho real (Nothing Phone (1), Android 15): seletor de contactos e cronómetro (continua a contar com a app fechada).
- ✅ Cópia de segurança (Definições → Cópia de segurança): exporta um .zip (base + fotos/anexos + manifesto com SHA-256) para onde se escolher; restauro validado antes de mexer nos dados e aplicado no arranque, com recuperação se for interrompido. Alerta no Início (e no resumo diário) quando não há cópia ou tem mais de 30 dias, só com dados reais. Ciclo exportar → apagar → restaurar verificado no emulador.
- ⬜ Prova no aparelho real: restaurar a cópia num segundo telemóvel

## Ainda simulado ("ainda não disponível nesta versão")

Nada.

## Limitações conhecidas

- Os nomes, taxas, horário e saldo inicial vêm com valores provisórios: confirmar e acertar em Definições.

## Fases 1–3

Backlog em `OBRA_EM_DIA_SPEC.md` §18.

## Ambiente

- Emulador: o instalador do driver AEHD falha neste PC. Usa-se a Plataforma de Hipervisor do Windows (WHPX).
- No Git Bash, os comandos `adb` com caminhos `/sdcard/...` precisam de `MSYS_NO_PATHCONV=1`.
- O emulador não substitui o aparelho real para câmara/QR, contactos e desempenho.
