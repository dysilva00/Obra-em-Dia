# Decisões técnicas

Registo das escolhas reversíveis tomadas durante o desenvolvimento. A especificação funcional está em `OBRA_EM_DIA_SPEC.md`.

## Plataforma

- **Kotlin + Jetpack Compose (Android nativo)** em vez da proposta React Native + Expo.
  Motivo: app só Android, local e offline. Room dá migrações testáveis, `VACUUM INTO` dá um snapshot consistente para o backup, ZIP e SAF são nativos, o QR é lido offline com CameraX + ML Kit e o APK compila localmente com Gradle sem serviços pagos.
- Módulo `:domain` em Kotlin/JVM puro, sem dependências Android, com todas as regras de negócio e testes.
  Pode ser reutilizado tal como está por um futuro servidor Kotlin (Ktor), caso venham a existir sincronização e várias contas.

## Dinheiro

- Montantes como `Money` (cêntimos `Long`). Nunca `Double`.
- Taxas como `HourlyRate` (cêntimos por hora). O valor de cada registo de horas é `minutos × taxa / 60`, arredondado ao cêntimo (HALF_UP) **por linha**; os totais são somas de linhas já arredondadas.
- Repartições pelo método do maior resto: os cêntimos residuais vão para o maior resto e, em empate, para a primeira parcela. A soma é sempre exata.
- Numa repartição de documento, a base é repartida na proporção dos totais atribuídos e o IVA de cada parcela é `total − base`.
- Convenção de IVA:
  - valor de venda e resultado da obra → **sem** IVA repercutido;
  - saldo do cliente, recebimentos e pagamentos → **com** IVA (valores de caixa).
- Saldo do cliente negativo = crédito a regularizar; nunca é escondido com `max(0, …)`.

## Tempo

- Fuso da agenda: `Europe/Lisbon`. Instantes em UTC, reservas de dia completo como data civil.
- Intervalos semiabertos `[início, fim)`.
- Horário provisório: 08:00–12:00 e 13:00–17:00 (configurável; por confirmar).
- A margem de deslocação aplica-se apenas entre locais diferentes.
- Durações em minutos inteiros, sem arredondamento automático à hora ou à meia hora.

## Preparação para sincronização futura (sem a implementar)

- PK em UUID gerado no aparelho, `createdAt`/`updatedAt` em UTC, eliminação lógica (`deletedAt`) nas entidades de negócio.
- Movimentos financeiros só de acréscimo, com correções por estorno.
- A UI acede aos dados apenas através de interfaces de repositório.
