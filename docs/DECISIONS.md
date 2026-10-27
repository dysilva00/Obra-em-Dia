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

## Persistência e arquitetura da app

- **Room 2.8 + SQLite incluído na app** (`androidx.sqlite:sqlite-bundled`), igual em todos os aparelhos. Permite `VACUUM INTO` para o snapshot do backup. Esquema exportado em `app/schemas/` e versionado. Sem `fallbackToDestructiveMigration`.
- **Injeção de dependências manual** (`AppContainer` em `ObraEmDiaApp.kt`) em vez de Hilt: a app é pequena e assim evita-se geração de código.
- **Idempotência:** cada formulário gera um `operationId` ao abrir. Há índices únicos em `cash_movements(operationId)` e `time_entries(operationId, workerId)` com `INSERT OR IGNORE`, por isso um duplo toque ou a repetição após um erro não duplicam registos.
- **Transações:** operações relacionadas usam `useWriterConnection { immediateTransaction { … } }` e são sempre totais ou nulas.
- **Totais derivados:** os saldos, o estado de pagamento e o resultado nunca são guardados. São calculados pelas regras do `:domain` a partir dos registos (`data/repo/Summaries.kt`).
- **Valor desconhecido ≠ zero:** uma obra de preço fixo sem preço, ou sem taxa de IVA definida, fica "Por definir" e fica fora dos totais "por receber", com um aviso.
- **IVA:** a taxa é escolhida por obra, com "Por definir" como valor padrão. Nunca se assume 23 %.
- **Dados de demonstração:** gravados na base com `isDemo = 1` e removíveis em bloco, numa transação. Se houver registos reais ligados a uma obra de demonstração, a remoção falha sem apagar nada. As duas pessoas e as suas taxas são **provisórias** (2 500/1 500 e 2 200/1 500 cêntimos por hora) até serem confirmadas.
- O backup automático do Android está desligado (`allowBackup=false`); o backup completo é feito pela própria app.

## Correções e encerramento

- **Movimentos de caixa:** nunca se apagam. "Estornar" cria um movimento do mesmo tipo e valor, com `reversalOfId` e motivo, e o seu efeito na caixa é o contrário do original. Há um índice único em `reversalOfId`, por isso cada movimento só pode ser estornado uma vez, e um estorno não pode ser estornado.
- **Horas:** a correção edita data, duração, tipo, se é faturável e a nota; as taxas históricas nunca mudam. A anulação usa `deletedAt`. Ambas ficam em `audit_events`, a correção com o antes e o depois.
- **Documentos de compra:** a anulação usa `deletedAt` e estorna, na mesma transação, os pagamentos que ainda não tinham sido estornados. Deixam de contar nos custos.
- **Encerramento:** guarda uma revisão em `job_closures` com o balanço em JSON. A revisão é calculada a partir da base dentro da transação de fecho e não a partir do ecrã.
- **Obra encerrada:** horas, recebimentos, gastos, marcações e mudanças de estado são recusados (`JobClosedException`). Pagar a fornecedores continua a ser permitido, porque a obrigação mantém-se depois do fecho.
- **Reabrir:** exige motivo e é registado na revisão atual. Encerrar de novo cria a revisão seguinte e as anteriores ficam guardadas.
- O responsável indicado no encerramento é apenas uma indicação: sem contas de acesso, não prova quem fez a revisão.

## Acordo, extras e dados da obra

- **Acordo de preço:** o preço fixo sem IVA, a taxa de IVA e a modalidade estão na obra e representam o acordo atual. Cada alteração cria uma linha em `price_revisions` com os valores antigos e novos, a data de aprovação e o motivo. O acordo anterior nunca é apagado.
- **Extras e materiais cobrados:** ficam em `job_charges`, com valores sem IVA. Os extras não incluídos no preço somam ao valor de venda. Os materiais só somam quando a política da obra é "cobrados à parte". Os itens "já incluído no preço" ficam registados mas não somam. Anular exige motivo e deixa o registo no histórico.
- **Obra encerrada:** o acordo, os extras, os materiais e a política de materiais ficam bloqueados. O título, as notas, o cliente, a morada e a prioridade podem ser corrigidos sem impacto financeiro.
- **Telefone do cliente:** pertence ao cliente e é partilhado pelas suas obras. "Ligar" abre o marcador (`ACTION_DIAL`) e não liga sozinho; "Ver no mapa" abre a app de mapas com `geo:`.
- **Remover demonstração:** sai tudo o que estiver ligado a obras fictícias. Uma compra repartida também por uma obra real fica, e só a parcela fictícia sai, passando a "por classificar". Clientes e locais fictícios usados por obras reais deixam de ser marcados como demonstração e ficam.
