# Estado do desenvolvimento

Legenda: ✅ concluído e verificado · 🟡 parcial · ⬜ pendente

## Verificação

- `gradlew :domain:test`: 28 testes de regras (dinheiro, IVA, repartição, agenda/conflitos, cobrança, saldos, estados, encerramento).
- `gradlew connectedDebugAndroidTest`: 9 testes da base de dados no emulador (idempotência, taxas históricas, transações, chaves estrangeiras, remoção de demonstração).
- Verificado manualmente no emulador (API 37): navegação, letra a 130 %, registo de pagamento com duplo toque (gravado uma única vez) e persistência depois de fechar a app à força.

## Fase 0 — Esqueleto e provas técnicas

- ✅ Projeto Gradle: AGP 9.4.1, Kotlin 2.4.21, Gradle 9.6.0, compileSdk/targetSdk 37, minSdk 26.
- ✅ Base de dados Room v1 com SQLite incluído: pessoas, clientes, locais, obras, reservas, indisponibilidades, horas, movimentos de caixa, auditoria e definições.
- ✅ Dados de demonstração gravados e marcados, com a ação "Remover".
- ✅ Ecrãs com dados reais: Início (mês navegável), Agenda (conflitos e indisponibilidades), Obras (lista, pesquisa e ficha) e Contas.
- ✅ Formulários reais: Nova obra, Registar horas (incluindo "Os dois") e Registar pagamento.
- ⬜ Prova no aparelho real: leitura de QR AT
- ⬜ Prova no aparelho real: seletor de contactos
- ⬜ Exportação via SAF e ciclo de backup/restauro com anexo

## Ainda simulado ("ainda não disponível nesta versão")

Adicionar gasto, Agendar obra, Rever encerramento, Projeções e Exportar.

## Limitações conhecidas

- O resultado da obra ainda não inclui materiais e despesas, porque a tabela de compras chega com o QR (esquema v2). Na ficha da obra aparece como "provisório".
- Ainda não é possível editar nem estornar registos.
- Os nomes e taxas das pessoas são provisórios e ainda não há ecrã de definições.

## Fases 1–3

Backlog em `OBRA_EM_DIA_SPEC.md` §18.

## Ambiente

- Emulador: o instalador do driver AEHD falha neste PC. Usa-se a Plataforma de Hipervisor do Windows (WHPX).
- No Git Bash, os comandos `adb` com caminhos `/sdcard/...` precisam de `MSYS_NO_PATHCONV=1`.
- O emulador não substitui o aparelho real para câmara/QR, contactos e desempenho.
