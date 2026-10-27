# Estado do desenvolvimento

Legenda: ✅ concluído e verificado · 🟡 parcial · ⬜ pendente

## Verificação

- `gradlew :domain:test`: 38 testes de regras (dinheiro, IVA, repartição, custos e IVA recuperável, tipos de documento, NIF, agenda/conflitos, sugestão de dias livres, cobrança, saldos, estados, encerramento).
- `gradlew connectedDebugAndroidTest`: 25 testes no emulador (idempotência, taxas históricas, transações, chaves estrangeiras, remoção de demonstração, agendamento com conflitos, estados, cancelamento, gastos/repartições/duplicados/crédito/notas de crédito, migrações v1→v2→v3).
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
- ✅ Esquema v3 com migrações automáticas testadas (v1→v2→v3).
- ✅ Adicionar gasto: tipos de documento (guia/pró-forma/recibo recusados com explicação), fornecedor/NIF validado/nº, categoria, IVA incluído e se é recuperável (por definir por omissão), uma obra / despesa geral / repartição com valor por classificar visível, pago agora ou a crédito com vencimento, notas de crédito.
- ✅ Duplicados de compras bloqueados (mesma operação, NIF+tipo+número, ATCUD).
- ✅ Contas: por pagar a fornecedores (com vencidas) e ação Pagar (parcial ou total), por classificar.
- ✅ Custos reais no resultado da obra (IVA recuperável fora do custo; por classificar entra e é sinalizado).
- ⬜ Prova no aparelho real: leitura de QR AT
- ⬜ Prova no aparelho real: seletor de contactos
- ⬜ Exportação via SAF e ciclo de backup/restauro com anexo

## Ainda simulado ("ainda não disponível nesta versão")

Leitura de QR, Rever encerramento, Projeções e Exportar.

## Limitações conhecidas

- Ainda não é possível editar nem estornar registos (horas, pagamentos, gastos).
- Os nomes e taxas das pessoas são provisórios e ainda não há ecrã de definições.

## Fases 1–3

Backlog em `OBRA_EM_DIA_SPEC.md` §18.

## Ambiente

- Emulador: o instalador do driver AEHD falha neste PC. Usa-se a Plataforma de Hipervisor do Windows (WHPX).
- No Git Bash, os comandos `adb` com caminhos `/sdcard/...` precisam de `MSYS_NO_PATHCONV=1`.
- O emulador não substitui o aparelho real para câmara/QR, contactos e desempenho.
