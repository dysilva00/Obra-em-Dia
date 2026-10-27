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

## Projeções e alertas

- **Previsão:** caixa prevista = saldo atual (saldo inicial + movimentos) + recebimentos em aberto − pagamentos em aberto, a 30, 60 e 90 dias. É calculada em `domain/cash/Forecast` e nunca é guardada.
- **Recebimentos:** vêm das prestações. O que já foi recebido cobre primeiro as prestações mais antigas, por isso os adiantamentos não reaparecem. O saldo não coberto por prestações usa como hipótese o último dia marcado da obra (só se for hoje ou depois); caso contrário fica "sem data". Nunca se prevê mais do que o saldo em dívida.
- **Pagamentos:** vêm das faturas de fornecedores por pagar, na data de vencimento. Sem vencimento, são considerados imediatos (pressuposto mostrado no ecrã).
- **Vencidos:** contam no dia de hoje e ficam marcados como VENCIDO.
- **Cenários:** confirmado ou com propostas (obras "Por confirmar"), sem probabilidades.
- **Incompleta:** a previsão é marcada como incompleta quando há obras sem preço ou IVA, ou valores sem data. Um valor desconhecido nunca é tratado como zero.
- **Adiamento simulado:** existe só no ecrã e não altera dados.
- **Não incluído:** custos futuros ainda não comprados, porque não há estimativas registadas. Está indicado nos pressupostos.
- **Alertas no Início:** prestações vencidas, faturas de fornecedor vencidas, sobreposições futuras na agenda, obras em espera com data de revisão atingida, obras ativas sem preço ou IVA e compras por classificar. Ainda não há notificações do sistema.

## Exportação

- **Só PDF, sem CSV:** decisão do utilizador a 27/10/2026. O "Relatório do mês" em PDF cobre o que o CSV ia dar (movimentos e horas).
- **Documentos:** o balanço da obra é interno (com custos, custo interno e resultado). O resumo para o cliente mostra só esta obra, sem custos, taxas de custo interno nem resultado. Nas obras à hora mostra as horas faturáveis ao preço cobrado. O relatório do mês tem os movimentos, as horas por pessoa e obra e a posição em aberto.
- **Rodapé:** todas as páginas dizem "Documento de gestão, sem valor fiscal. Não é fatura nem recibo."
- **Geração:** usa o `PdfDocument` nativo do Android, sem bibliotecas nem serviços externos.
- **Partilhar:** gera o PDF em `cache/exports/` (a única pasta exposta pelo FileProvider; os ficheiros com mais de 24 h são limpos) e abre o menu de partilha. O envio é sempre manual.
- **Guardar:** usa o seletor de ficheiros do Android (`CreateDocument`). O relatório é reconstruído quando o seletor devolve o destino, por isso funciona mesmo que a atividade seja recriada.

## Fotografias, notas e checklist

- **Anexos:** ficam em `filesDir/attachments/<uuid>.jpg`. A base guarda o caminho relativo, o tamanho e o SHA-256 (para validar o backup). Uma importação só está concluída depois de o ficheiro ser copiado: os URIs da galeria e da câmara são temporários.
- **Compressão:** lado maior ≤ 2048 px, JPEG 85 e orientação EXIF aplicada. Os PDFs são copiados tal como estão. Abaixo de 20 MB livres, a importação é recusada com uma mensagem compreensível.
- **Ordem da gravação:** primeiro a cópia (para um ficheiro temporário, depois renomeado) e só depois o registo na base. Se o registo falhar, o ficheiro copiado é apagado.
- **Câmara:** a foto é tirada pela app de câmara do sistema (`TakePicture`) para `cache/camera/` através do FileProvider, por isso não é preciso pedir permissão de câmara. O ficheiro temporário é apagado depois de copiado.
- **Galeria:** usa o Photo Picker do Android, sem permissão de armazenamento.
- **Obras encerradas:** fotos, notas e checklist não têm impacto financeiro e podem ser alteradas mesmo com a obra encerrada.
- **Remover:** remover uma foto, nota ou item usa eliminação lógica e fica no histórico. Remover a demonstração apaga também os ficheiros, só depois de a transação terminar bem.

## Design

- **Material 3 Expressive:** usa `MaterialExpressiveTheme` com `MotionScheme.expressive()`, `ShortNavigationBar` e `LoadingIndicator`. Exige `androidx.compose.material3:material3:1.5.0-beta02`, fixado no catálogo por cima do BOM (que traz a 1.4.0 estável). **Passar para a 1.5.0 estável quando sair.** Os componentes experimentais usam `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
- **Tipografia:** Plus Jakarta Sans, uma fonte variável incluída em `res/font` (licença OFL em `assets/licenses/`), por isso funciona sem internet. Os valores em euros usam algarismos tabulares (`tnum`).
- **Cor e superfícies:** verde-petróleo como cor principal e âmbar nos avisos. Os cartões são camadas tonais com contorno subtil, sem sombra, e cantos de 18–32 dp. Só existe tema claro, pensado para uso no exterior.
- **Início:** um cartão de destaque em degradê junta o saldo acumulado, o mês e os movimentos do mês. As três ações principais são mosaicos grandes.
- **Acessibilidade:** com a letra a mais de 115 %, as filas de quatro ações passam a grelha 2×2. Legendas e filas de botões usam `FlowRow` para passarem à linha seguinte em vez de partirem palavras. O mínimo de 48 dp nos alvos de toque mantém-se.

## Lembretes

- **Um resumo diário, nunca uma notificação por registo:** inclui as marcações de amanhã, as prestações e faturas vencidas, as obras em espera a rever e as sobreposições. Os dados em falta (obra sem preço, compras por classificar) ficam só no Início da app.
- **Agendamento:** WorkManager periódico de 24 h, com o primeiro disparo à hora escolhida (7:00, 8:00, 9:00 ou 19:00). É reagendado ao gravar as preferências e no arranque da app. Funciona com a app fechada e sem internet.
- **Sem repetições:** guarda-se a assinatura do último resumo enviado e o mesmo conteúdo não volta a ser notificado. Cada resumo substitui o anterior, por isso as notificações não se acumulam.
- **Permissão:** `POST_NOTIFICATIONS` é pedida só quando se liga o resumo. Se for recusada, nada mais deixa de funcionar e os avisos continuam no Início.
- **Por defeito está desligado.** As preferências ficam nas definições locais, que entram no backup.

## Faturas emitidas e código QR da AT

- **Leitor do QR** (`domain/fiscalqr/AtQr`): segue as Especificações Técnicas do Código QR da AT, versão 1.1 (Portaria n.º 195/2020), confirmadas no documento oficial a 27/10/2026. Os testes usam os exemplos desse documento.
- **Valores:** o valor sem impostos é O − N (inclui bases isentas e não sujeitas). As retenções vêm do campo P. Um documento com E:A (anulado) não pode ser registado, e só FT, FS, FR, NC e ND contam; as notas de crédito subtraem.
- **Leitura no aparelho:** ML Kit Barcode com o modelo incluído na app, sem internet. Nos PDFs renderizam-se a primeira e a última página a ~200 ppp com `PdfRenderer`, porque a AT permite o QR em qualquer uma delas. Se o PDF não tiver QR, fica anexado e os valores introduzem-se à mão.
- **Câmara:** CameraX + ML Kit em direto. A permissão de câmara é pedida só ao abrir o leitor; se for recusada, ficam o PDF e a introdução manual.
- **Totais por ano e por NIF do emitente:** o valor declarado é de cada contribuinte. O limite anual a vigiar é opcional e configurável; a app não assume nenhum limite fiscal.
- **Duplicados:** bloqueados pelo NIF + tipo + número ou pelo ATCUD. O PDF só é copiado depois de confirmar que a fatura não é duplicada.
- **Natureza do registo:** uma fatura emitida não é um recebimento; os recebimentos continuam nos movimentos. É um controlo interno e não comunica nada à AT.

## Número de pessoas

- **Configurável (1 ou mais):** decisão do utilizador a 27/10/2026, que substitui as "duas pessoas" fixas da spec. As pessoas adicionam-se e removem-se em Definições.
- **Remover = desativar** (`deletedAt`): a pessoa sai das escolhas, mas as horas, marcações e registos antigos mantêm o nome. Não se pode remover a última pessoa ativa, nem alguém com marcações a partir de hoje.
- **Interface:** com 1 pessoa, os seletores "Quem" desaparecem e tudo fica atribuído a essa pessoa. Com 2, a opção de juntar todos chama-se "Os dois"; com 3 ou mais, "Todos".
- A demonstração continua a criar 2 pessoas de exemplo. Para ficar com uma: remover os dados de demonstração e depois remover a pessoa a mais.

## Cópia de segurança e restauro

- **Formato:** um único `.zip` com `db/obra-em-dia.db`, a pasta `attachments/` e `manifest.json` (formato, versão do esquema, versão da app, data, contagens, tamanho e SHA-256 de cada ficheiro). Pensado para mudar de telemóvel e para servir de carga inicial num futuro servidor.
- **Snapshot consistente:** `VACUUM INTO` com a ligação de escrita do Room reservada, por isso nada é gravado a meio da cópia. O ZIP é criado e verificado na pasta privada e só depois copiado para o destino escolhido (SAF). Se a cópia falhar, o ficheiro de destino é apagado.
- **Validar antes de tocar:** o restauro copia e extrai para uma área separada, com limites de tamanho e de entradas e proteção contra caminhos fora da pasta (zip slip). Confere os checksums, recusa um esquema ou formato mais recente que a app, corre `integrity_check` e `foreign_key_check` e abre a base com o Room, o que aplica as migrações de versões antigas.
- **Aplicado no arranque:** confirmado o restauro, a app reinicia e a troca é feita antes de a base abrir. Assim nunca se fecha a base debaixo de ecrãs ou tarefas que a estejam a usar.
- **Recuperação:** um journal regista a fase da troca. Se o processo morrer a meio, no arranque seguinte os dados anteriores são repostos. Depois de um restauro bem-sucedido, os dados anteriores ficam em `noBackupFilesDir/backup/rollback` até ao restauro seguinte.
- **Data da última cópia:** fica nas definições (`last_backup_at`). Um restauro grava nela a data da cópia restaurada. O alerta aparece quando há obras reais e não há cópia ou esta tem mais de 30 dias. Também entra no resumo diário, mas só é notificado uma vez, até o texto mudar.
- **Sem cópia automática para a nuvem:** o `allowBackup` continua desligado. Guardar o ficheiro fora do telemóvel (Drive, e-mail, computador) é um passo do utilizador e a app lembra-o disso.
