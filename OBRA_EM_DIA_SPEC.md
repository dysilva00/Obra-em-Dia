# Obra em Dia — Especificação para Codex / Claude

> Documento autónomo para planear e desenvolver uma aplicação Android de gestão de pequenas obras e trabalhos de construção.
> Atualizado em 27/10/2026 — revisão 2: um único dispositivo, dados locais e sem custos de alojamento. Nome provisório: **Obra em Dia**.
> Inclui os requisitos pedidos pelo utilizador e as propostas de desenho discutidas. Não pressupõe que já exista código ou infraestrutura.

## 1. Instruções para o agente de desenvolvimento

Lê este documento antes de implementar. Trata-o como especificação funcional e ponto de partida técnico. Se existir um repositório, começa por inspecionar o código e as instruções locais; não substituas a arquitetura existente sem motivo.

O objetivo é uma app utilizável no telemóvel pelos pais do utilizador, não apenas um dashboard demonstrativo. Privilegia simplicidade no terreno, dados financeiros consistentes, gravação local fiável e backup recuperável.

Ao receberes autorização para desenvolver:

1. Identifica o que já existe e documenta um plano curto de implementação.
2. Se o projeto começar do zero, usa a arquitetura proposta abaixo como base, verificando versões e compatibilidade na documentação oficial.
3. Implementa por etapas com percursos completos, começando pelo protótipo navegável e pelo modelo de domínio.
4. Não faças depender o progresso de perguntas sobre detalhes que podem ser configuráveis ou ter valores provisórios claramente identificados.
5. Distingue sempre dados de demonstração, funcionalidades simuladas e funcionalidades reais.
6. Não declares QR, contactos, notificações, funcionamento offline ou backup/restauro como concluídos sem os validar no aparelho/ambiente adequado.
7. Entrega instruções para executar, testar e gerar uma versão Android de teste, indicando limitações reais.
8. Não publiques em lojas, não contrates serviços e não envies mensagens a clientes sem autorização específica.
9. Se implementares uma etapa parcial, mantém uma lista clara do que falta para cumprir a primeira versão funcional.

Este documento não exige a utilização simultânea de Codex e Claude: qualquer um deve conseguir trabalhar apenas com este ficheiro.

## 2. Contexto e objetivo

Os pais do utilizador são trabalhadores independentes na construção. Atualmente registam em papel o agendamento das obras, as horas, os materiais e as contas.

A app deve responder rapidamente a estas perguntas:

- Onde vamos trabalhar hoje e nos próximos dias?
- Que obras estão por confirmar, agendadas, em espera, em curso ou concluídas?
- Que prioridade tem cada trabalho?
- Quantas horas trabalhou cada pessoa e a que taxa?
- Quanto custou e quanto rendeu cada obra?
- Quanto já foi recebido e quanto falta receber?
- Quanto foi gasto no mês e que saldo existe?
- Que entradas, saídas e disponibilidade se preveem para as próximas obras?

### Decisões confirmadas pelo utilizador

| Tema | Decisão |
| --- | --- |
| Plataforma inicial | Android |
| Organização | Os dois trabalham e fazem as contas em conjunto |
| Dispositivos nesta fase | Um único telemóvel Android |
| Dados da atividade | Agenda, obras e contas dos dois, geridas no mesmo aparelho |
| Persistência | Base SQLite e anexos locais; funcionamento sem internet |
| Custos recorrentes | Sem servidor, alojamento, subscrições ou serviços pagos obrigatórios |
| Contas e sincronização | Sem login nem sincronização entre dispositivos nesta fase |
| Proteção contra perda | Backup completo exportável e restauro, incluindo anexos |
| Pessoas | Horas e disponibilidade individualizadas |
| Cobrança | Preço fixo previamente orçamentado **ou** cobrança à hora, escolhidos por obra |
| Língua | Português de Portugal |
| Moeda | Euro |
| Fuso da agenda | Europe/Lisbon |
| Cliente/contacto | Associação opcional; permitir obra sem contacto do telemóvel |

As restantes escolhas de arquitetura, design, taxas, lembretes e regras configuráveis são propostas, não preferências explicitamente confirmadas.

## 3. Princípios de experiência

- Interface pensada para utilização rápida em obra, com uma mão e luz exterior.
- Texto legível, bom contraste e suporte ao tamanho de letra do Android.
- Alvos táteis de pelo menos 48 dp, com rótulos claros.
- Nunca depender apenas de cor para distinguir estados ou prioridades.
- Poucos campos obrigatórios; detalhes podem ser completados depois.
- Obra atual, pessoa, data e taxas habituais preenchidas automaticamente quando apropriado.
- Três ações principais sempre fáceis de encontrar: **Registar horas**, **Adicionar gasto**, **Registar pagamento**.
- Mostrar estados vazios, erros de gravação, falta de espaço e progresso de backup/restauro de forma compreensível. Não mostrar indicadores de sincronização inexistente.
- Confirmar ações financeiras relevantes e disponibilizar correção auditável.
- Objetivo de usabilidade a validar: registar horas ou uma despesa simples em cerca de 30 segundos.

## 4. Estrutura da aplicação

Navegação inferior: **Início · Agenda · Obras · Contas**.

Clientes, pessoas, horários, taxas, definições e exportações podem ficar no menu ou em entradas contextuais.

### 4.1 Início / Dashboard

Apresentar o mês selecionado, com navegação para outros meses:

- Recebimentos efetivos do mês.
- Pagamentos efetivos do mês.
- Saldo mensal.
- Saldo acumulado a partir de um saldo inicial e dos movimentos registados.
- Valores a receber e a pagar, claramente identificados como posições em aberto na data de referência, não como movimentos do mês.
- Valores vencidos.
- Obras de hoje e próximas marcações.
- Obras em curso, concluídas e pendentes de cobrança.
- Horas por pessoa.
- Alertas úteis, sem transformar cada registo numa notificação.
- Acesso separado às projeções a 30, 60 e 90 dias.

Cada total deve abrir os registos que o compõem. Todos os cartões devem indicar período/data de referência e se mostram valores reais ou previstos.

### 4.2 Agenda

- Vista inicial em lista por dia; opções de dia, semana e mês.
- Filtro por pessoa e vista conjunta.
- Reservas de dia completo, manhã/tarde ou intervalo horário.
- Obras com múltiplas visitas e dias descontínuos.
- Datas provisórias visualmente diferentes de reservas confirmadas.
- Indisponibilidades, férias, descansos e margens de deslocação.
- Duração prevista, pessoas atribuídas e prioridade visíveis.
- Aviso de sobreposição com explicação e alternativas.
- Lista de obras ainda sem data.
- Proposta dos primeiros intervalos disponíveis, respeitando disponibilidade e pessoas necessárias.

### 4.3 Obras

- Lista pesquisável por título, cliente, morada e notas relevantes.
- Filtros por estado, prioridade, pessoa e situação de pagamento.
- Ordenação por data, prioridade e atualização.
- Arquivo de obras encerradas sem perder acesso ao histórico.

Ficha com áreas:

1. **Resumo:** cliente, local, estado, prioridade, modalidade, preço, datas e indicadores.
2. **Agenda:** visitas e pessoas atribuídas.
3. **Horas:** registos individuais e totais.
4. **Gastos:** materiais, outras despesas, documentos e repartições.
5. **Pagamentos:** adiantamentos, prestações, recebimentos, saldo e vencimentos.
6. **Notas e fotos:** checklist, lista de compras e imagens antes/depois.
7. **Balanço:** comparação previsto/real, resultado e revisão do encerramento.

### 4.4 Contas

- Movimentos reais, recebimentos e pagamentos.
- Valores por receber e por pagar.
- Despesas por categoria, obra e fornecedor.
- Despesas gerais sem obra específica.
- Saldo inicial, entradas de capital e retiradas pessoais identificadas à parte.
- Evolução mensal e projeção futura.
- Exportação mensal em CSV e resumo por obra em PDF.

## 5. Modelo funcional da obra

### Campos principais

- Identificador e título obrigatório.
- Descrição e tipo de trabalho.
- Cliente opcional e local da obra independente do cliente.
- Pessoas necessárias/atribuídas.
- Modalidade de cobrança: `fixed_price` ou `hourly`.
- Preço/orçamento aprovado ou taxas por pessoa, conforme modalidade.
- Política de materiais: incluídos, cobrados à parte ou fornecidos pelo cliente.
- Estimativa de horas-pessoa, custos e duração.
- Datas preferidas e reservas efetivas separadas.
- Prioridade, estado do trabalho e motivo de espera quando aplicável.
- Condições de pagamento, prestações e vencimentos.
- Extras aprovados com valor, data e evidência/nota da aprovação.
- Notas, fotos, anexos e histórico de alterações.

Um cliente pode ter várias obras e vários locais. O local não deve depender de um contacto existir no telemóvel.

### 5.1 Estados independentes

**Estado do trabalho:**

- Por confirmar.
- Agendada.
- Em curso.
- Em espera.
- Concluída.
- Cancelada.

Fluxo habitual: Por confirmar → Agendada → Em curso → Concluída.

Em espera exige motivo: material, cliente, meteorologia ou outro. Pode ter data de revisão. A retoma permite escolher a etapa apropriada. Marcar como agendada deve implicar uma reserva válida; uma obra confirmada sem data pode continuar na lista por agendar, com aceitação comercial registada separadamente.

**Situação de pagamento**, derivada dos movimentos e valor devido:

- Sem pagamentos.
- Parcial.
- Liquidado.
- A regularizar, por exemplo se existir pagamento em excesso.

Pagamento em atraso é um indicador adicional, que pode coexistir com pagamento parcial. A conclusão física não implica liquidação, nem a liquidação implica conclusão.

### 5.2 Encerramento

Encerrar é uma ação explícita, não apenas alterar o estado para Concluída.

Condições:

- Trabalho concluído.
- Preço final confirmado e extras revistos.
- Saldo do cliente regularizado, sem montantes por cobrar ou devolver.
- Horas e despesas revistas.
- Compromissos conhecidos com fornecedores identificados.
- Registos financeiros relevantes gravados e consistentes na base local antes do fecho definitivo. O fecho e a respetiva revisão devem ser transacionais.

Guardar uma versão do balanço, pessoa indicada como responsável pela revisão (opcional), data e notas. Sem login, essa indicação não constitui prova de autoria. Um fornecedor ainda por pagar não desaparece ao encerrar: o custo entra no resultado e a obrigação mantém-se em contas a pagar, com aviso na revisão.

Depois de encerrar, novas alterações com impacto financeiro exigem reabertura com motivo. Preservar versões anteriores de fecho. Cancelamentos libertam reservas futuras e obrigam a regularizar adiantamentos e compromissos existentes.

### 5.3 Prioridade

Baixa, Normal, Alta e Urgente. Urgente pede um motivo. A prioridade afeta ordenação e destaque, mas não desloca reservas automaticamente.

## 6. Agendamento e conflitos

### Capacidade

- Gerir disponibilidade por pessoa.
- Uma obra atribuída a ambos ocupa as duas agendas.
- Obras simultâneas com pessoas diferentes são permitidas.
- Duas pessoas a trabalhar oito horas representam 16 horas-pessoa, não oito.
- Um período de secagem pode prolongar a obra sem reservar pessoas durante todo esse intervalo.
- Horários, pausas e dias de trabalho são configuráveis.

### Sobreposições

Usar intervalos semiabertos `[início, fim)` para que reservas contíguas não conflituem sem margem adicional.

Existe interseção quando:

```text
inicio_A < fim_B AND fim_A > inicio_B
```

Aplicar a regra às mesmas pessoas, às indisponibilidades e à margem de deslocação configurada. Pré-reservas geram aviso mas distinguem-se de ocupação firme.

Exemplo de aviso: “A Pessoa 1 já está na Casa da Ana no dia 28, das 08:00 às 12:00.”

Ações disponíveis:

- Escolher outro horário.
- Atribuir outra pessoa disponível.
- Manter a sobreposição explicitamente, com motivo registado.

Não mover outras obras silenciosamente. Ao prolongar/reagendar, mostrar previamente os trabalhos afetados. Validar e gravar numa transação local para não deixar reservas parcialmente atualizadas.

A base local é a fonte de verdade nesta versão. A verificação e confirmação do agendamento funcionam inteiramente sem internet e não dependem de aprovação de servidor.

Guardar instantes com fuso consistente e datas civis das reservas de dia completo; testar mudança da hora em Europe/Lisbon.

## 7. Horas e modalidades de cobrança

### 7.1 Registo de horas

- Obra, pessoa e data.
- Início/fim e pausa, ou duração introduzida diretamente.
- Trabalho, deslocação ou outro tipo configurável.
- Faturável ou não faturável.
- Taxa de venda e custo interno por hora guardados no momento do registo.
- Nota opcional.
- Cronómetro opcional; no máximo um ativo por pessoa.
- Copiar registo/dia anterior.
- Registar “Os dois” cria entradas individuais, editáveis separadamente.

Validar duração positiva, pausas e sobreposição de horas da mesma pessoa. Definir arredondamento explicitamente; não arredondar automaticamente à hora ou à meia hora.

### 7.2 Preço fixo

```text
Valor de venda = preço fixo aprovado + extras aprovados + materiais cobrados à parte, se aplicável
```

As horas reais servem para controlar o esforço e calcular custos. **Não adicionar a cobrança das horas novamente ao preço fixo.**

Se os materiais estiverem incluídos, afetam custos mas não aumentam automaticamente a venda. Registar alterações ao orçamento e extras aprovados sem apagar o acordo anterior.

### 7.3 À hora

```text
Valor de venda = soma(horas faturáveis × taxa de venda histórica de cada registo)
               + materiais cobrados à parte
               + extras aprovados que não estejam já incluídos
```

Permitir taxas diferentes por pessoa, com valores habituais preenchidos automaticamente. Não alterar o histórico quando uma taxa habitual é atualizada.

O preço de venda de um material pode diferir do custo de compra, mas o preço ou regra de acréscimo tem de ser explícito. Material fornecido pelo cliente não cria uma compra fictícia nem cobrança automática.

### 7.4 Taxa de venda versus custo interno

- Taxa de venda: o que se cobra ao cliente.
- Custo interno: o custo de gestão atribuído ao trabalho.
- Para os próprios trabalhadores, o custo interno pode representar remuneração pretendida, sem corresponder a saída de caixa.
- Não deduzir de novo pagamentos de mão de obra já representados no custo interno.
- Não confundir retiradas pessoais com custo operacional.

## 8. Materiais, despesas e QR fiscal

### 8.1 Registo de despesa

- Data do documento/compra e data(s) de pagamento separadas.
- Fornecedor e NIF quando disponível.
- Tipo e número do documento; ATCUD quando disponível.
- Categoria: material, deslocação, aluguer, ferramenta, subcontrato ou outra.
- Base, impostos e total, quando disponíveis.
- Tratamento do IVA configurado, sem assumir recuperabilidade.
- Obra, repartição por várias obras ou despesa geral.
- Comprovativo/foto/PDF.
- Quem pagou e estado de liquidação ao fornecedor.
- Vencimento e pagamentos parciais, se aplicável.

Uma compra a crédito afeta custos antes de afetar caixa. Devoluções e notas de crédito reduzem custos e ligam ao documento original, sem pressupor que já houve devolução de dinheiro.

### 8.2 Leitura do QR português

Fluxo: **Adicionar gasto → Ler QR → Rever dados → Associar obra/repartição → Confirmar**.

O QR fiscal pode fornecer NIF do emitente/adquirente, tipo, número, data, ATCUD, bases/impostos e total. Na especificação consultada, o campo `O` representa o total do documento com impostos.

Limitações e regras:

- O QR não contém a lista detalhada de artigos, quantidades e preços unitários dos materiais.
- Não prometer reconhecimento automático de artigos apenas a partir do QR.
- O nome comercial do fornecedor pode ter de ser preenchido ou recuperado de um cadastro interno.
- Confirmar tipo de documento; guias, pró-formas e recibos não devem criar automaticamente uma segunda despesa.
- Notas de crédito exigem tratamento próprio de sinal e ligação ao original; não inferir tudo só pelo número positivo do total.
- Preservar o conteúdo original lido para diagnóstico e auditoria, com proteção de acesso.
- Campos ausentes, inválidos e QR de outros formatos devem produzir mensagens úteis e alternativa manual.
- Pedir câmara apenas no contexto da leitura/fotografia.
- Preferir descodificação local quando possível.
- A leitura é um registo interno: não comunica automaticamente ao e-Fatura, não autentica o documento e não comprova pagamento.
- OCR da imagem para extrair artigos é melhoria futura, sempre com confirmação.

Antes de implementar o parser, verificar a documentação oficial da AT vigente. Não codificar taxas de IVA ou formatos apenas com base em exemplos.

### 8.3 Repartição e duplicados

Guardar o documento original uma vez e atribuir parcelas às obras/despesas gerais.

Exemplo: uma compra de 123 € pode ser repartida em 80 € na obra A e 43 € na obra B.

- A soma das parcelas tem de corresponder ao total atribuído, com eventual parcela por classificar claramente visível.
- Repartir bases/impostos de forma coerente, com regra determinística para cêntimos residuais.
- Não somar documento e parcelas como despesas independentes.
- Se existirem detalhes dos artigos, não voltar a somá-los ao total já contabilizado.
- Identificar duplicados por NIF do emitente + identificação do documento, usando ATCUD válido quando existir; reforçar com data/total e hash do conteúdo.
- Avisar e abrir o documento existente; usar validação e restrições locais para impedir duplicados após dupla leitura, duplo toque ou repetição da operação.

## 9. Clientes, locais e contactos Android

- Criar cliente manualmente ou selecionar um contacto do aparelho.
- Importar apenas dados escolhidos e necessários.
- Nome basta para um cliente simples; telefone, email, NIF e morada são opcionais.
- A obra pode existir sem cliente associado.
- Preferir seletor nativo com acesso limitado e adaptar à versão Android/biblioteca disponível.
- Se não houver permissão ou compatibilidade, permitir introdução manual sem bloquear o fluxo.
- Não importar a agenda inteira por defeito.
- O identificador do contacto é local ao aparelho: o cliente da app tem identificador próprio e independente, preservado no backup e numa eventual migração futura.
- Apagar ou alterar um contacto no telemóvel não deve apagar o cliente nem alterar silenciosamente documentos históricos.
- Permitir ligar, abrir navegação para a morada e preparar partilha manual de resumo.

## 10. Recebimentos, pagamentos e contas

Registar adiantamentos, prestações e pagamento final, incluindo data, valor, método, nota e comprovativo opcional.

Métodos configuráveis, por exemplo dinheiro, transferência, MB WAY e outro. São registos de pagamentos efetuados; integração para processar pagamentos está fora do âmbito inicial.

Separar:

- Valor acordado/devido.
- Plano de prestações e respetivos vencimentos.
- Recebimentos efetivos e respetiva atribuição à obra/prestação.
- Compras e outros compromissos com fornecedores.
- Pagamentos efetivos a fornecedores.

Um adiantamento é entrada de caixa quando recebido e reduz o que falta receber; não aumenta uma segunda vez o preço da obra. Uma fatura de fornecedor não prova que houve pagamento.

Correções de movimentos financeiros devem ser auditáveis, por estorno/movimento corretivo ou modelo equivalente, em vez de eliminação silenciosa. Mostrar créditos e pagamentos em excesso como valores a regularizar.

## 11. Fórmulas e consistência financeira

### Tesouraria

```text
Saldo operacional do mês = recebimentos reais do mês - pagamentos operacionais reais do mês

Saldo acumulado = saldo inicial
                + todos os recebimentos reais
                - todos os pagamentos reais
                + entradas de capital
                - retiradas pessoais
                + ajustes explícitos auditáveis
```

Identificar entradas de capital e retiradas separadamente; não as apresentar como receitas e gastos de obras. O saldo representa os movimentos abrangidos pela app e não deve ser anunciado como saldo bancário reconciliado sem essa reconciliação.

Recebimentos/pagamentos usam valores de caixa, normalmente totais com impostos. Os períodos respeitam a data efetiva do movimento.

### Resultado da obra

```text
Resultado de gestão = valor final sem IVA repercutido
                    - materiais e outros custos líquidos do IVA recuperável
                    - custo interno de mão de obra

Margem (%) = resultado / valor final sem IVA repercutido × 100
```

Mostrar margem apenas quando o denominador for positivo. Custos conhecidos ainda não pagos entram no resultado. Se o IVA não for recuperável, integra o custo. Sem configuração suficiente, sinalizar a limitação e não apresentar um resultado fiscal como definitivo.

O resultado é anterior a impostos sobre rendimento e encargos não registados. Não substituir faturação oficial ou aconselhamento contabilístico.

### Saldo do cliente

```text
Saldo do cliente = total final devido com impostos, após descontos/créditos aplicáveis
                 - recebimentos líquidos de devoluções
```

Saldo positivo: por receber. Zero: liquidado. Negativo: crédito/excesso a regularizar. Não esconder valores negativos com `max(0, saldo)`.

### Exemplo sem IVA

| Elemento | Valor |
| --- | ---: |
| Preço fixo | 3 000 € |
| Material e outras despesas | 700 € |
| Trabalho | 40 horas-pessoa |
| Custo interno por hora | 15 € |
| Custo interno total | 600 € |
| Resultado | 1 700 € |
| Margem | 56,7% |
| Recebido até agora | 1 000 € |
| Falta receber | 2 000 € |
| Contribuição de caixa até agora, se os 700 € já foram pagos | 300 € |

### Precisão

Usar inteiros em cêntimos para montantes finais e `Decimal`/precisão decimal adequada para taxas e cálculos. Evitar floats binários em dinheiro. Guardar durações em segundos ou minutos inteiros e definir arredondamentos nas linhas/totais. Descrever a convenção de valores com/sem impostos no modelo de dados e nas funções de domínio.

## 12. Projeções e alertas

Horizontes: 30, 60 e 90 dias, com vista mensal.

```text
Caixa prevista = saldo atual
               + recebimentos futuros ainda em aberto
               - pagamentos futuros ainda em aberto
```

Usar datas previstas das prestações, compras, subcontratos e despesas recorrentes. A data final da obra só serve como hipótese explícita se não existir data de recebimento definida.

- Separar cenário confirmado de cenário que inclui propostas por aceitar.
- Não atribuir probabilidades automaticamente.
- Mostrar os pressupostos e permitir simular adiamento de um recebimento.
- Uma estimativa de custo sem dados não vale zero: indicar previsão incompleta.
- Associar compras reais às previsões para substituir a parte executada, não somar tudo novamente.
- Pagamentos já registados saem dos valores futuros em aberto.
- Não repetir adiantamentos já recebidos nas entradas previstas.
- Capacidade futura calculada por pessoa e reservas firmes; pré-reservas mostradas à parte.

Alertas propostos:

- Sobreposição de agenda.
- Prestação vencida.
- Gastos ou horas acima do previsto.
- Material por comprar.
- Obra sem preço, custo previsto ou data relevante para a projeção.
- Falhas de gravação, falta de espaço e backup externo desatualizado.
- Obra em espera cuja data de revisão chegou.

Notificações locais configuráveis no único aparelho; não requerem push nem servidor. Mensagens para clientes são preparadas para revisão/envio manual no âmbito inicial.

## 13. Fotos, notas, checklist e exportação

- Fotografias antes/depois e ao longo da obra, com legenda e data.
- Notas rápidas, anexos de orçamento e comprovativos.
- Checklist curta e lista do material a comprar.
- Compressão de imagens para utilização móvel, sem tornar documentos ilegíveis.
- Exportação CSV de movimentos e horas, com filtros.
- PDF do balanço da obra: preço, extras, horas, custos, recebimentos, saldo e resultado.
- Resumo partilhável para o cliente sem expor taxas internas ou dados de outras obras.
- Documentos de gestão exportados não devem fingir ser faturas ou recibos fiscais.

## 14. Direção visual e ecrãs do protótipo

Proposta visual: fundo claro, cartões simples, verde-petróleo nas ações principais e âmbar em avisos. Evitar tabelas densas no telemóvel. Manter os nomes de estados escritos por extenso.

Quatro ecrãs de referência:

1. **Início:** seletor do mês, saldo, recebido/pago, por receber, obra de hoje e botão Registar horas.
2. **Agenda:** semana, reservas com pessoas/horas, aviso de conflito e botão Agendar obra.
3. **Ficha da obra:** estado físico e financeiro, preço/recebido, horas, custos, resultado e ações rápidas.
4. **Confirmar gasto:** dados reconhecidos do QR, total, categoria, obra/repartição e confirmação.

Acrescentar os ecrãs necessários ao percurso completo: criar obra, escolher modalidade, registar horas, registar pagamento, rever encerramento e consultar balanço.

Usar dados fictícios identificados no protótipo. Não depender de imagens ou do PDF anterior para compreender estes requisitos.

## 15. Arquitetura técnica proposta — apenas local

A restrição confirmada é uma app para **um único Android**, sem custos recorrentes e sem dependência de cloud. A escolha de framework continua a ser uma proposta técnica ajustável após inspeção do repositório.

- **App:** React Native + Expo + TypeScript, Android como alvo inicial.
- **Base de dados:** SQLite local como única fonte de verdade, com relações, restrições, transações e migrações versionadas.
- **Anexos:** ficheiros persistentes no armazenamento privado da app; guardar caminhos relativos na base, não caminhos absolutos dependentes da instalação nem apenas URIs temporários.
- **Regras de negócio:** módulos TypeScript testáveis, independentes dos ecrãs e do armazenamento.
- **Dinheiro:** cêntimos inteiros e biblioteca decimal/aritmética apropriada quando necessário; não depender de Python nem de floats binários para regras monetárias.
- **Lembretes:** notificações locais, sem infraestrutura de push.
- **Acesso:** utilização direta no aparelho; sem registo, login, utilizadores remotos ou gestão de sessões.
- **Distribuição de teste:** gerar e instalar APK, com percurso de compilação local documentado. Não tornar um plano pago de build ou a publicação numa loja pré-requisito.
- **Rede:** os percursos centrais, QR fiscal, cálculos, ficheiros e backups locais funcionam sem internet. Ações externas opcionais, como abrir navegação ou partilhar para outra app, podem depender dessa app/rede.

Não implementar nesta fase API Python/FastAPI, PostgreSQL, Supabase, Firebase, servidor, alojamento, armazenamento cloud obrigatório, autenticação remota ou motor de sincronização. Não adicionar dependências pagas para QR, exportação ou backup. A app não deve enviar dados para serviços externos por defeito.

### Entidades sugeridas

| Entidade | Responsabilidade |
| --- | --- |
| BusinessSettings | Dados e configuração local da atividade |
| Worker | As duas pessoas, disponibilidade e taxas habituais; não são contas de login |
| Client | Dados do cliente, com contacto Android opcional |
| WorkSite | Local da obra, independente do cliente |
| Job | Trabalho, modalidade, estado, prioridade e condições |
| Quote / QuoteRevision / Extra | Orçamento e alterações aprovadas |
| ScheduleEntry / Assignment | Visita/reserva e pessoas atribuídas |
| Availability / TimeOff | Horário e indisponibilidades |
| TimeEntry | Tempo real e taxas históricas |
| PurchaseDocument / CostAllocation | Compra original e distribuição do custo |
| BillableMaterial | Material cobrado ao cliente, separado do custo de compra |
| PaymentObligation / Installment | Valores devidos e vencimentos por receber/pagar |
| CashMovement / PaymentAllocation | Movimento efetivo e atribuição aos saldos |
| CostEstimate / ForecastLink | Previsões e ligação à execução real |
| Attachment / Note / ChecklistItem | Evidências e informação da obra |
| JobClosureRevision | Balanço versionado do encerramento |
| AuditEvent | Histórico local de alterações relevantes, sem fingir identificar quem usou o aparelho |
| BackupMetadata | Data, versão, integridade e resultado da última exportação/restauro |

Evitar duplicar fontes de verdade: a situação financeira resulta dos movimentos e obrigações. A pessoa a quem uma hora ou despesa é atribuída pode ser escolhida, mas essa atribuição não equivale a autenticação nem prova de autoria.

### Preparação mínima para uma evolução futura

Usar identificadores estáveis, como UUID, datas de criação/alteração e separação entre interface, domínio e persistência. Isto facilita exportação e uma eventual evolução para vários aparelhos. Contas, backend e sincronização são uma fase futura independente, a desenhar e autorizar apenas quando forem necessárias; não implementar filas, tokens, conflitos distribuídos nem infraestrutura especulativa agora.

## 16. Persistência local, backup e restauro

### Gravação no aparelho

- Guardar imediatamente na SQLite, sem ligação à internet.
- Usar transações em operações relacionadas: pagamento/atribuição, repartição de custos, agendamento e fecho.
- Impedir duplicados por restrições de unicidade, controlo de duplo toque e recuperação segura de operações interrompidas.
- Ao importar fotos/documentos, copiá-los para uma pasta persistente da app; uma referência a um ficheiro temporário não conta como importação concluída.
- Prever erros de espaço, permissões e gravação sem perder os registos já válidos.
- Documentar migrações da base e não apagar dados para resolver alterações de esquema.

### Backup completo — requisito da primeira versão

Adicionar em Definições uma área **Backup e restauro**, com ações Exportar backup, Restaurar backup e indicação da última exportação bem-sucedida.

O backup deve ser um ficheiro portátil, por exemplo um pacote ZIP, que inclua:

1. Um snapshot consistente da base de dados ou formato de exportação completo equivalente.
2. Todos os anexos referenciados: fotografias, comprovativos e outros documentos.
3. Configuração da atividade, pessoas, taxas, revisões de fecho e histórico relevante.
4. Manifesto com versão do formato, versão do esquema/app, data, contagens e checksums dos ficheiros.

Não copiar apenas o ficheiro SQLite enquanto existem escritas/WAL ativos: usar mecanismo de snapshot suportado ou interromper/controlar escritas e obter uma cópia consistente. Coordenar também alterações dos anexos durante a captura.

Criar primeiro numa área temporária e validar antes de disponibilizar. Permitir ao utilizador escolher o destino com o seletor de ficheiros Android e copiar o pacote para fora do armazenamento privado da app. Permitir partilha manual para computador, suporte externo ou serviço que o utilizador já use, sem exigir integração, conta ou subscrição cloud.

Uma cópia apenas dentro da pasta da app não protege contra desinstalação. Uma cópia no mesmo telemóvel também não protege contra perda ou avaria do aparelho. Explicar isto brevemente na interface e disponibilizar lembrete local configurável para exportação externa. Uma exportação bem-sucedida indica que o destino aceitou o ficheiro, não prova que existe uma cópia independente fora do aparelho.

Backups contêm dados pessoais e financeiros. Não partilhar automaticamente nem expor conteúdos em logs. Se for acrescentada cifragem por palavra-passe, usar biblioteca/formato seguro, documentar a recuperação e não depender de uma chave exclusiva da instalação que impeça o restauro noutro aparelho.

### Restauro seguro

- Escolher explicitamente um ficheiro de backup.
- Validar manifesto, integridade, anexos e compatibilidade do esquema antes de tocar nos dados atuais.
- Rejeitar pacotes malformados, caminhos fora da pasta de destino e tamanhos/expansões abusivos.
- Mostrar data, conteúdo e aviso claro de substituição dos dados atuais; pedir confirmação.
- Na primeira versão, restaurar um conjunto completo. Não fundir backups nem importar o mesmo pacote como novas linhas.
- Fazer cópia de segurança do estado atual e preparar o restauro numa área separada.
- Validar a base restaurada, relações e anexos; aplicar migrações suportadas antes de ativar.
- Trocar para o conjunto restaurado apenas quando tudo estiver validado e manter recuperação em caso de falha/interrupção. Não presumir atomicidade conjunta entre SQLite e sistema de ficheiros.
- Recriar os lembretes locais a partir dos dados restaurados, evitando notificações duplicadas.
- Restaurar um backup suportado numa instalação limpa deve funcionar sem backend.

Exportações CSV/PDF são úteis para consulta, mas não substituem um backup completo recuperável.

## 17. Proteção dos dados e recuperação

- Usar armazenamento privado da app e as proteções do dispositivo Android.
- Minimizar contactos importados e pedir permissões apenas no contexto da ação.
- Não exigir login; um bloqueio local opcional pode ser avaliado mais tarde, sem infraestrutura remota.
- Manter histórico local de alterações financeiras e de agendamento; sem contas distintas, não atribuir automaticamente identidade ao operador.
- Não enviar telemetria com clientes, documentos, fotografias ou movimentos financeiros.
- Alertar de forma contextual que desinstalar/apagar dados pode remover base e anexos privados.
- Priorizar backup exportável e restauro testado desde o início; cópias automáticas internas, se existirem, são complementares.
- Antes do piloto, confirmar uma rotina simples de cópia externa e realizar pelo menos um restauro completo numa instalação de teste.
- Não prometer recuperação de dados que nunca foram exportados para fora do aparelho.
- Preservar exportação de consulta para reduzir dependência permanente da app.

## 18. Fases de implementação

### Fase 0 — Protótipo e prova técnica

- Protótipo navegável dos ecrãs e do percurso completo, com dados fictícios.
- Validar compreensão com os pais.
- Provar leitura QR, seleção de contacto e exportação de ficheiros no aparelho Android real.
- Confirmar arquitetura local, esquema de dados e convenções financeiras.
- Provar um ciclo de backup/restauro com base e anexo num ambiente de teste.

### Fase 1 — Base diária

- Configuração local da atividade e cadastro das duas pessoas, sem contas de acesso.
- Clientes e locais opcionais.
- Obras, prioridades e estados.
- Agenda, pessoas, indisponibilidades e alertas de conflito.
- Registo de horas e despesas manuais, fotos/notas básicas.
- Persistência SQLite, anexos privados e backup/restauro de base desde o início.

### Fase 2 — Contas e QR

- Preço fixo e cobrança à hora completos.
- Taxas de custo/venda históricas e política de materiais.
- Orçamentos, extras e comparação previsto/real.
- QR fiscal, duplicados e repartições.
- Adiantamentos, pagamentos parciais e fornecedores por pagar.
- Balanço, encerramento e reabertura auditável.

### Fase 3 — Painel completo e piloto

- Dashboard mensal e saldo inicial/acumulado.
- Projeções a 30/60/90 dias e indicadores de dados incompletos.
- Lembretes e alertas configuráveis.
- Exportações CSV/PDF.
- Robustez da gravação local, migrações, backups completos e recuperação.
- Piloto em paralelo com os registos atuais, antes de abandonar o papel.

As fases 1 a 3 compõem a primeira versão funcional pretendida. Não apresentar apenas a fase 1 como cumprimento de todos os requisitos.

### Melhorias posteriores

- Suporte a vários dispositivos, contas e sincronização, se solicitado no futuro, com arquitetura e custos reavaliados nessa altura.
- OCR para artigos de faturas.
- Modelos detalhados e envio de orçamentos.
- Ditado de notas.
- Catálogo de materiais e stock simples.
- Trabalhos recorrentes.
- Integração com faturação externa.
- Portal de cliente e automatizações adicionais, se vierem a ser necessárias.

## 19. Critérios de aceitação e testes

### Percurso principal

Criar obra sem contacto → escolher preço fixo → agendar ambos por dois dias → provocar e resolver conflito → registar 8 h por pessoa → ler e repartir uma compra → registar adiantamento → concluir → receber restante → rever/encerrar → exportar balanço.

Repetir com obra à hora e taxas diferentes por pessoa.

### Casos obrigatórios

- [ ] Preço fixo não cresce automaticamente com o registo de horas.
- [ ] À hora soma apenas horas faturáveis às taxas históricas corretas.
- [ ] Alterar uma taxa habitual não muda registos anteriores.
- [ ] Materiais incluídos não são cobrados novamente.
- [ ] Obras com pessoas diferentes podem decorrer em paralelo.
- [ ] Uma reserva para ambos conflita com indisponibilidade de qualquer um.
- [ ] Reservas contíguas respeitam margens configuradas e mudança da hora.
- [ ] Conflitos são detetados e resolvidos no aparelho sem internet.
- [ ] Ler ou guardar duas vezes a mesma compra não duplica despesas.
- [ ] Documento de 123 € repartido em 80 € e 43 € soma 123 €, nunca 246 €.
- [ ] Uma guia/pró-forma não cria despesa automática e uma nota de crédito reduz o custo corretamente.
- [ ] Compra a crédito afeta custos, mas não caixa antes de ser paga.
- [ ] Adiantamento reduz o saldo sem aumentar o preço e não reaparece na projeção.
- [ ] Devoluções e pagamentos em excesso ficam visíveis e conciliáveis.
- [ ] Obra concluída por pagar não pode ser encerrada como liquidada.
- [ ] Compromissos com fornecedores persistem depois do fecho.
- [ ] Falhas de gravação não deixam um fecho parcialmente aplicado.
- [ ] Reabrir e fechar cria nova revisão sem apagar a anterior.
- [ ] Projeções não somam integralmente estimativa e execução da mesma despesa.
- [ ] Dados em falta são sinalizados, não tratados como zero conhecido.
- [ ] Duplo toque, reabertura da app e repetição após erro não duplicam horas nem pagamentos.
- [ ] Negar câmara/contactos/notificações não bloqueia os percursos manuais.
- [ ] Não é exigido login, ligação à internet ou serviço cloud para usar a app.
- [ ] Backup restaurado recupera todos os registos, taxas históricas, configurações e anexos.
- [ ] Backup incompleto/corrompido ou de versão incompatível é rejeitado sem alterar os dados atuais.
- [ ] Restauro completo substitui os dados apenas após confirmação; repetir o restauro não duplica registos.
- [ ] Falta de espaço e interrupção durante backup/restauro não destroem a base atual.
- [ ] Migração de versão preserva registos e anexos e suporta recuperação após falha.
- [ ] Totais do dashboard e exportações reconciliam com os movimentos.

Escrever testes de domínio para cálculos, transições, repartições, prevenção de duplicados e conflitos. Complementar com testes de integração SQLite/ficheiros, migrações, backup/restauro e percursos Android. Não usar apenas snapshots visuais como prova de correção financeira.

Validar UI no aparelho, com fonte aumentada, dados longos, listas extensas, rede indisponível e estados de erro. Um preview no browser não valida câmara, contactos ou comportamento Android.

## 20. Informação ainda por confirmar

Não voltar a perguntar se é Android, se trabalham em conjunto, se cobram preço fixo/à hora, se querem custos de alojamento ou se usam vários dispositivos: já está confirmado que a primeira versão é local, sem custos recorrentes e para um único Android.

Ainda pode ser necessário confirmar:

- Modelo e versão Android do aparelho inicial.
- Nomes das pessoas e taxas habituais de venda/custo.
- Horário, pausas e dias de trabalho.
- Tratamento de materiais mais habitual.
- Como combinam prestações e adiantamentos.
- Existência de compras a crédito e subcontratação.
- Saldo inicial e movimentos abrangidos pela app.
- Situação de IVA e sistema de faturação atual.
- Destino externo e rotina de backups; distribuição da versão Android de teste sem serviços pagos obrigatórios.

Até existir resposta, usar configuração explícita ou dados de demonstração identificados. Não inventar valores reais, regime fiscal ou credenciais.

## 21. Pesquisa de referência

Pesquisa funcional realizada em 27/10/2026, com documentação oficial. Não foram feitos testes práticos das plataformas. As referências são inspiração; não implicam copiar interfaces nem integrar estes serviços.

| Referência | Ideia aproveitada |
| --- | --- |
| Jobber | Agenda, visitas/pessoas e ligação de despesas à obra |
| Housecall Pro | Custos por obra e comparação previsto/real |
| PlanRadar | Organização de tarefas, fotos, documentos e relatórios |

Fontes para consulta e verificação antes de implementar:

- Jobber: https://www.getjobber.com/features/
- Housecall Pro: https://www.housecallpro.com/features/job-costing/
- PlanRadar: https://help.planradar.com/hc/pt-br/articles/12523388641309-Projetos
- AT, especificação QR consultada (versão 1.1): https://info.portaldasfinancas.gov.pt/pt/apoio_ao_contribuinte/Negocios/Faturacao/Regras_mecanismos_comunicacao/Comunicacao_de_series_ATCUD/Codigo_barras_bidimensional/Documents/Especificacoes_Tecnicas_Codigo_QR.pdf
- Android, seleção de contactos: https://developer.android.com/about/versions/17/features/contact-picker
- Expo SQLite: https://docs.expo.dev/versions/latest/sdk/sqlite/

Não assumir que versões de bibliotecas, permissões e especificações técnicas continuam iguais: verificar a documentação oficial durante a implementação.

## 22. Entregáveis esperados do desenvolvimento

- Código da app Android autónoma, com todos os registos e cálculos locais.
- Modelo de dados e migrações.
- Ambiente de desenvolvimento reproduzível e instruções de compilação local, sem dependência de backend ou serviço pago.
- Dados de demonstração claramente separados dos reais.
- Testes das regras críticas e respetivos resultados.
- Instruções de execução e geração/instalação da versão Android de teste.
- Lista de funcionalidades concluídas, parciais e pendentes.
- Guia curto para utilização pelos pais.
- Exportação e restauro de backup completo e procedimento de recuperação validado antes do piloto.

### Prompt curto para iniciar uma sessão de desenvolvimento

> Lê o ficheiro `OBRA_EM_DIA_SPEC.md` e inspeciona o repositório antes de alterar código. Quero desenvolver esta app para um único telemóvel Android, sem custos recorrentes, sem servidor, sem login e sem sincronização nesta fase. Mantém SQLite e anexos locais, funcionamento sem internet e backup/restauro completo exportável. Continua a gerir as horas e disponibilidade das duas pessoas no mesmo aparelho. Segue as decisões confirmadas e as regras de negócio do documento. Começa por um plano curto e pela Fase 0: protótipo navegável do percurso completo e provas técnicas de QR/contactos e backup/restauro, distinguindo claramente simulações de funcionalidades reais. Usa as fases seguintes como backlog obrigatório da primeira versão funcional. Resolve escolhas técnicas reversíveis com bom senso e documenta-as; pergunta apenas pelo que bloquear realmente o trabalho. Não publiques nem contrates serviços sem autorização.
