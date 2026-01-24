# Guia de Implementação: Camada de Chatbot e AI (RAG)  
**Kixi Platform**  
**Versão:** 1.2  
**Data:** Janeiro 2026  
**Status:** Proposto para Revisão e Aceitação via ADR  

A camada de inteligência artificial e chatbot da Enuncia Platform opera exclusivamente sobre os dados estruturados já persistidos na base relacional, nunca sobre outputs crus do OCR nem sobre informações não validadas. O seu propósito é oferecer suporte inteligente de estudo, explicação de conteúdos, geração de exercícios semelhantes e recomendações complementares, sempre mantendo a base de dados como fonte única de verdade.

A camada é implementada como microserviço independente (ai-service), em Python com FastAPI e LangChain, garantindo desacoplamento total do pipeline de ingestão (OCR + backend) e permitindo evolução independente.

### Princípios Fundamentais
A camada AI é read-only em relação aos dados do domínio. Não persiste, não modifica e não cria entidades. Utiliza Retrieval-Augmented Generation (RAG) para recuperar contexto relevante da base vetorial e enriquecer as respostas do modelo de linguagem.  

Todo o processamento respeita as regras de autorização (RBAC) aplicadas no backend principal. O chatbot suporta referências explícitas a enunciados na forma `@enunciado.[disciplina].[anoletivo].[trimestre].[tipodeprova]`, carrega o contexto completo do enunciado quando referenciado e mantém-no ativo durante a conversa enquanto o utilizador mencionar questões ou partes dele.

### Decisão Tecnológica
O microserviço ai-service é construído em Python 3.11+ com FastAPI para a API reativa e LangChain para orquestração de chains e retrieval.  

O modelo de embedding escolhido é multilíngue e leve (paraphrase-multilingual-mpnet-base-v2 ou intfloat/multilingual-e5-large). A base vetorial inicial é PgVector (extensão PostgreSQL na base principal), com possibilidade de migração para Pinecone ou Milvus em escala maior.  

O LLM é configurável via variáveis de ambiente (Grok-2 via xAI API como recomendação inicial, com suporte nativo a Gemini, GPT-4o-mini ou modelos locais via Ollama/vLLM). A decisão final sobre o provedor principal será formalizada em ADR complementar.

### Fluxo de Indexação
Após a persistência ou atualização de um Statement (exame/enunciado), o backend publica um evento assíncrono (Kafka topic `enuncia.events.statement` ou Spring ApplicationEvent).  

O ai-service consome o evento, recupera o Statement completo incluindo Questions e QuestionOptions (via API segura do backend ou leitura direta com row-level security).  

Cada questão é transformada num documento coeso que inclui: enunciado, tipo de questão, pontuação máxima, opções (se aplicável) e metadados do enunciado (disciplina, ano letivo, trimestre, tipo de prova).  

O documento é embeddado e indexado na base vetorial com metadados filtráveis (statement_id, disciplina, ano letivo, etc.). A indexação é eventual consistente e idempotente.

### Fluxo de Consulta e Funcionalidades Principais
O utilizador envia a mensagem via frontend → backend-api (`POST /ai/chat`). O backend valida autenticação JWT e autorização RBAC antes de encaminhar a query ao ai-service.  

O ai-service processa a query da seguinte forma:

1. Parser identifica referências no formato `@enunciado.[disciplina].[anoletivo].[trimestre].[tipodeprova]` e aplica filtro obrigatório no retrieval para o enunciado correspondente.  
2. Se a query mencionar uma questão específica (ex: “explica a questão 3”), o contexto completo do enunciado é carregado e mantido ativo para toda a conversa subsequente sobre esse enunciado.  
3. Retrieval semântico recupera top-k documentos relevantes (k configurável, threshold mínimo ~0.75).  
4. Construção do prompt inclui: contexto recuperado, instruções do sistema, histórico recente da conversa e eventuais filtros do utilizador.  
5. Chamada ao LLM (streaming quando suportado).  

Funcionalidades específicas implementadas:

- Explicação detalhada de questões, com passos claros e raciocínio lógico.  
- Geração de exercícios novos no mesmo calibre, tema e dificuldade do enunciado referenciado (chain dedicada com prompt específico).  
- Geração automática de chaves/respostas corretas quando as opções não tiverem `isCorrect` definido na base (prompt explícito para evitar hallucinação).  
- Adaptação ao estilo de correção de um professor específico, quando indicado (ex: “responda como o Prof. João gosta: formal e com encorajamento”). Armazenamento de preferências por utilizador/professor via configuração.  
- Recomendação dinâmica de materiais complementares (livros didáticos portugueses/angolanos e vídeos YouTube), obtidos via busca externa integrada (LangChain tools para web search e YouTube). As recomendações incluem título, autor/editora e link direto.

### Estrutura de Documento na Base Vetorial
Cada entrada representa uma questão e inclui:

- id: UUID da questão  
- statement_id: referência ao enunciado  
- statement_reference: string no formato `@enunciado...`  
- content: texto completo enriquecido com metadados  
- metadata: filtros (school_year, subject, question_type, etc.)  
- embedding: vetor gerado

### Endpoints Principais
- POST /chat/query  
  Corpo: { "query": string, "userId": uuid, "contextFilters": object opcional }  
  Resposta: streaming JSON ou objeto com answer, sources, generatedExercises, complementaryMaterials (array com título + link)

- POST /admin/reindex (protegido)  
  Força reindexação de um statement específico

### Comunicação e Segurança
O backend atua como proxy autenticado. JWT é validado e propagado. Rate limiting por utilizador (Redis) suporta modelo freemium futuro. Eventos assíncronos garantem desacoplamento.

### Escalabilidade e Observabilidade
Escala horizontal via réplicas K8s. Cache Redis para embeddings e respostas frequentes. Métricas Prometheus monitoram latência, recall@K, tempo de geração e consumo de tokens. Tracing distribuído (Jaeger) correlaciona fluxos completos.

### Configuração Inicial Recomendada
- Embedding: paraphrase-multilingual-mpnet-base-v2  
- Vector DB: PgVector  
- LLM: Grok-2 (xAI API) como default configurável  
- Prompt base do sistema:  
  “Você é um tutor especialista da Enuncia Platform. Responda sempre em português de Portugal, de forma clara, educativa e estruturada. Use apenas o contexto fornecido. Cite as questões relevantes. Gere exercícios semelhantes quando pedido. Recomende materiais complementares úteis (livros e vídeos) com links. Se não houver chave na base, gere respostas corretas com rigor. Adapte ao estilo do professor quando indicado.”

### Próximos Passos
- Prototipar fluxo completo: persistir enunciado → indexar → referenciar via @ → explicar + gerar exercício + recomendar material  
- Validar precisão da geração de chaves e exercícios em conjunto com professores  
- Realizar testes A/B para k, threshold e modelo embedding  
- Formalizar escolha do LLM via ADR  
- Definir SLOs: 95% das respostas em menos de 4 segundos, recall@5 superior a 0.85

Aprovação pendente:  
[ ] Tech Lead  
[ ] Arquitetura  

Última atualização: Janeiro 2026