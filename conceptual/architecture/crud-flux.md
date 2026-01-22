
# Guia de Implementação de CRUDs Reativos no Projeto

Este guia detalha o padrão de implementação de CRUDs no backend localizado em `services/backend-api`, usando o exemplo do CRUD de "schoolyears" como referência. Para implementar qualquer novo CRUD, substitua "[nome da entidade]" pelo nome da sua entidade e consulte os arquivos do CRUD de schoolyears para exemplos práticos.

---

## Estrutura de Arquivos e Propósito

### 1. Controller
- **Caminho:** `src/main/java/ao/creativemode/kixi/controller/[NomeDaEntidade]Controller.java`
- **Propósito:** Define as rotas HTTP e expõe a API REST para a entidade. Recebe requisições, valida dados e delega ao service.
- **Integração:** Depende do service correspondente. Utiliza DTOs para entrada e saída.
- **Boas práticas:**
  - Anotado com `@RestController` e `@RequestMapping`.
  - Métodos reativos (`Mono`, `Flux`).
  - Validação com `@Valid`.
  - Retorno padronizado com `ResponseEntity`.
  - Consulte o arquivo do controller de schoolyears para exemplos de endpoints e convenções.

### 2. Service
- **Caminho:** `src/main/java/ao/creativemode/kixi/service/[NomeDaEntidade]Service.java`
- **Propósito:** Implementa a lógica de negócio do CRUD. Realiza validações adicionais e orquestra operações no repositório.
- **Integração:** Depende do repository. Utiliza DTOs e entidades.
- **Boas práticas:**
  - Anotado com `@Service`.
  - Métodos reativos (`Mono`, `Flux`).
  - Conversão entre entidade e DTO centralizada.
  - Lida com soft delete, restore e hard delete.
  - Consulte o arquivo do service de schoolyears para exemplos de métodos e lógica de negócio.

### 3. Repository
- **Caminho:** `src/main/java/ao/creativemode/kixi/repository/[NomeDaEntidade]Repository.java`
- **Propósito:** Interface para acesso ao banco de dados, usando Spring Data R2DBC.
- **Integração:** Utilizado pelo service. Opera sobre entidades.
- **Boas práticas:**
  - Extende `ReactiveCrudRepository`.
  - Métodos customizados para soft delete (`findAllByDeletedFalse`, etc).
  - Consulte o arquivo do repository de schoolyears para exemplos de métodos customizados.

### 4. Model (Entidade)
- **Caminho:** `src/main/java/ao/creativemode/kixi/model/[NomeDaEntidade].java`
- **Propósito:** Representa a tabela no banco de dados.
- **Integração:** Usada pelo repository e service.
- **Boas práticas:**
  - Anotações do Spring Data (`@Table`, `@Id`, `@Column`).
  - Métodos utilitários para soft delete e restore.
  - Consulte o arquivo da entidade schoolyears para exemplos de estrutura e métodos.

### 5. DTOs
- **Caminho:** `src/main/java/ao/creativemode/kixi/dto/[nomeDaEntidade]/`
- **Propósito:** Transportam dados entre camadas e expõem contratos da API.
- **Integração:** Usados no controller e service.
- **Boas práticas:**
  - Utilização de `record` para imutabilidade.
  - Validação com anotações (`@NotNull`, `@Positive`).
  - Consulte os DTOs de schoolyears para exemplos de estrutura e validação.

### 6. Migration (Banco de Dados)
- **Caminho:** `src/main/resources/db/migration/Vx__create_[nome_da_tabela]_table.sql`
- **Propósito:** Cria a tabela no banco de dados.
- **Integração:** Executada automaticamente na inicialização.
- **Boas práticas:**
  - Constrains de unicidade e integridade.
  - Consulte a migration de schoolyears para exemplos de constraints e estrutura.

### 7. Configurações
- **Caminho:** `src/main/resources/application.properties`
- **Propósito:** Configura conexão com o banco e propriedades do Spring.
- **Integração:** Usado pelo framework.
- **Boas práticas:**
  - Não versionar senhas reais. Use arquivos de exemplo.
  - Consulte o arquivo de configuração do projeto para exemplos de propriedades.

### 8. Exceptions e Handler Global
- **Caminho:** `src/main/java/ao/creativemode/kixi/common/exception/`
- **Propósito:** Centraliza tratamento de erros e padroniza respostas.
- **Integração:** Usado por todas as camadas.
- **Boas práticas:**
  - Uso de Problem Details (RFC 9457).
  - Handlers para validação e erros genéricos.
  - Consulte os arquivos de exceção e handler global para exemplos de tratamento de erros.

---


## Fluxo das Operações CRUD

### Create
- **Controller:** Recebe DTO de criação, valida e chama `service.create()`.
- **Service:** Converte DTO em entidade, salva via repository, retorna DTO de resposta.
- **Repository:** Persiste entidade.

### Read (Listar e Buscar por ID)
- **Controller:** Expõe endpoints para listar ativos, listar deletados, buscar por ID.
- **Service:** Busca entidades via repository, converte para DTO de resposta.
- **Repository:** Métodos customizados para ativos/deletados.

### Update
- **Controller:** Recebe DTO de atualização, valida e chama `service.update()`.
- **Service:** Busca entidade, atualiza campos, salva e retorna DTO de resposta.

### Delete (Soft Delete)
- **Controller:** Chama `service.softDelete()`.
- **Service:** Marca entidade como deletada, salva.
- **Repository:** Atualiza registro.

### Restore
- **Controller:** Chama `service.restore()`.
- **Service:** Restaura entidade deletada, salva.

### Hard Delete
- **Controller:** Chama `service.hardDelete()`.
- **Service:** Remove entidade do banco.

Consulte o fluxo completo do CRUD de schoolyears para exemplos detalhados de cada operação.

---

## Boas Práticas Gerais
- Separe DTOs, entidades, services, controllers e repositórios em pacotes distintos.
- Use métodos reativos (`Mono`, `Flux`) em todas as camadas.
- Nunca use `.block()` ou `.subscribe()` fora de testes.
- Valide dados com anotações e `@Valid`.
- Centralize conversão entre entidade e DTO.
- Implemente soft delete sempre que necessário.
- Centralize tratamento de erros com handler global.
- Documente endpoints e regras de negócio.

---


## Como Implementar um Novo CRUD
1. Crie a entidade em `model/` usando `[nome da entidade]`.
2. Defina DTOs em `dto/[nome da entidade]/`.
3. Implemente o repository.
4. Implemente o service.
5. Implemente o controller.
6. Crie migration para a tabela.
7. Adapte o handler global se necessário.
8. Siga as boas práticas acima.
9. Consulte os arquivos do CRUD de schoolyears para exemplos práticos e adaptação.

---

## Destaques Importantes

- **Relacionamentos Many-to-Many:**  
  - Se **não há dados extras** na tabela intermediária, **evite criar uma entidade** para ela; utilize apenas a anotação `@ManyToMany` nas entidades relacionadas.
  - Se **há dados extras** (colunas adicionais além das FKs), **sempre crie a entidade** da tabela intermediária e trate-a como uma “entidade normal” (com CRUD, DTOs, etc).

- **DTOs:**  
  Sempre crie DTOs para transportar dados entre camadas e **nunca exponha diretamente suas entidades** nos endpoints.

- **Service Layer:**  
  É recomendável utilizar uma camada de service para manter o controller limpo e separar a lógica de negócio.

---

Este guia é o padrão oficial para CRUDs reativos neste projeto. Adapte conforme necessário, mantendo a arquitetura e as convenções.
*Centralize conversões entre entidades e DTOs para facilitar manutenção.*
