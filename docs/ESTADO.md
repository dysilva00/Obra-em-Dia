# Estado do desenvolvimento

Legenda: ✅ concluído e verificado · 🟡 escrito, por verificar/parcial · ⬜ pendente

## Fase 0 — Esqueleto e provas técnicas

- ✅ Módulo `:domain`: dinheiro, repartição, agenda/conflitos, cobrança, saldo do cliente, estados e encerramento. 26 testes a passar (`gradlew :domain:test`).
- ✅ Projeto Gradle: AGP 9.4.1, Kotlin 2.4.21, Gradle 9.6.0, compileSdk/targetSdk 37, minSdk 26.
- ✅ Protótipo navegável: Início, Agenda (com conflito real detetado pelo domínio), Obras (lista + ficha) e Contas, com dados de demonstração e faixa de aviso. Verificado no emulador (API 37), também com letra a 130%.
- 🟡 As ações (registar horas, gasto, pagamento, nova obra, etc.) apenas mostram "ainda não disponível neste protótipo".
- ⬜ Ecrãs do percurso completo: criar obra, registar horas, registar pagamento, confirmar gasto/QR, rever encerramento
- ⬜ Esquema Room v1, Hilt e DemoSeeder
- ⬜ Prova no aparelho: leitura de QR AT
- ⬜ Prova no aparelho: seletor de contactos
- ⬜ Prova no aparelho: exportação via SAF
- ⬜ Prova no aparelho: ciclo de backup/restauro com anexo

## Fases 1–3

⬜ Por iniciar. O backlog está em `OBRA_EM_DIA_SPEC.md` §18 e no plano de implementação.

## Limitações do ambiente

- Emulador: o instalador do driver AEHD falha neste PC. Usa-se a Plataforma de Hipervisor do Windows (WHPX).
- O emulador não substitui o aparelho real para câmara/QR, contactos e desempenho.
