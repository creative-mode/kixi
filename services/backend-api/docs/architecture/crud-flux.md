# Guia de Implementação de CRUD Reativo com Spring WebFlux

Este guia apresenta um modelo para criar módulos CRUD reativos usando Spring WebFlux, seguindo a arquitetura e boas práticas do backend existente. Use este documento como referência para novos módulos, adaptando para cada entidade conforme necessário.

---

## Estrutura de Arquivos Recomendada

```
src/main/java/ao/creativemode/
  ├── controller/
  │     ProdutoMockController.java
  ├── service/
  │     ProdutoMockService.java
  ├── repository/
  │     ProdutoMockRepository.java
  ├── model/
  │     ProdutoMock.java
  ├── dto/
  │     produtomock/
  │         ProdutoMockCreateDTO.java
  │         ProdutoMockUpdateDTO.java
  │         ProdutoMockResponseDTO.java
  ├── mapper/
  │     ProdutoMockMapper.java
  └── exception/
        GlobalExceptionHandler.java
```

---

## 1. Entidade de Domínio

```java
// ProdutoMock.java
package ao.creativemode.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("produtos_mock")
public class ProdutoMock {
    @Id
    private Long id;
    private String nome;
    private Double preco;
}
```
*Utilize anotações do Spring Data para persistência e Lombok para reduzir boilerplate.*

---

## 2. DTOs

```java
// ProdutoMockCreateDTO.java
package ao.creativemode.dto.produtomock;

import jakarta.validation.constraints.NotNull;

public record ProdutoMockCreateDTO(
    @NotNull String nome,
    @NotNull Double preco
) {}

// ProdutoMockUpdateDTO.java
package ao.creativemode.dto.produtomock;

import jakarta.validation.constraints.NotNull;

public record ProdutoMockUpdateDTO(
    @NotNull String nome,
    @NotNull Double preco
) {}

// ProdutoMockResponseDTO.java
package ao.creativemode.dto.produtomock;

public record ProdutoMockResponseDTO(
    Long id,
    String nome,
    Double preco
) {}
```
*Separe DTOs para criação, atualização e resposta. Use `record` para imutabilidade e validação.*

---

## 3. Repository

```java
// ProdutoMockRepository.java
package ao.creativemode.repository;

import ao.creativemode.model.ProdutoMock;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface ProdutoMockRepository extends ReactiveCrudRepository<ProdutoMock, Long> {
    // Métodos customizados podem ser adicionados aqui
}
```
*Utilize `ReactiveCrudRepository` para operações reativas nativas.*

---

## 4. Mapper

```java
// ProdutoMockMapper.java
package ao.creativemode.mapper;

import ao.creativemode.model.ProdutoMock;
import ao.creativemode.dto.produtomock.*;

public class ProdutoMockMapper {
    public static ProdutoMock toEntity(ProdutoMockCreateDTO dto) {
        return new ProdutoMock(null, dto.nome(), dto.preco());
    }
    public static ProdutoMock toEntity(Long id, ProdutoMockUpdateDTO dto) {
        return new ProdutoMock(id, dto.nome(), dto.preco());
    }
    public static ProdutoMockResponseDTO toResponseDTO(ProdutoMock entity) {
        return new ProdutoMockResponseDTO(entity.getId(), entity.getNome(), entity.getPreco());
    }
}
```
*Centralize conversões entre entidades e DTOs para facilitar manutenção.*

---

## 5. Service

```java
// ProdutoMockService.java
package ao.creativemode.service;

import ao.creativemode.repository.ProdutoMockRepository;
import ao.creativemode.dto.produtomock.*;
import ao.creativemode.mapper.ProdutoMockMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ProdutoMockService {
    private final ProdutoMockRepository repository;

    public ProdutoMockService(ProdutoMockRepository repository) {
        this.repository = repository;
    }

    public Flux<ProdutoMockResponseDTO> getAll() {
        return repository.findAll()
            .map(ProdutoMockMapper::toResponseDTO);
    }

    public Mono<ProdutoMockResponseDTO> getById(Long id) {
        return repository.findById(id)
            .map(ProdutoMockMapper::toResponseDTO);
    }

    public Mono<ProdutoMockResponseDTO> create(ProdutoMockCreateDTO dto) {
        ProdutoMock entity = ProdutoMockMapper.toEntity(dto);
        return repository.save(entity)
            .map(ProdutoMockMapper::toResponseDTO);
    }

    public Mono<ProdutoMockResponseDTO> update(Long id, ProdutoMockUpdateDTO dto) {
        return repository.findById(id)
            .flatMap(existing -> {
                ProdutoMock updated = ProdutoMockMapper.toEntity(id, dto);
                return repository.save(updated);
            })
            .map(ProdutoMockMapper::toResponseDTO);
    }

    public Mono<Void> delete(Long id) {
        return repository.deleteById(id);
    }
}
```
*Garanta reatividade pura: sempre retorne Mono/Flux, sem blocos ou subscribe.*

---

## 6. Controller

```java
// ProdutoMockController.java
package ao.creativemode.controller;

import ao.creativemode.dto.produtomock.*;
import ao.creativemode.service.ProdutoMockService;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/produtos-mock")
public class ProdutoMockController {
    private final ProdutoMockService service;

    public ProdutoMockController(ProdutoMockService service) {
        this.service = service;
    }

    @GetMapping
    public Flux<ProdutoMockResponseDTO> getAll() {
        return service.getAll();
    }

    @GetMapping("/{id}")
    public Mono<ProdutoMockResponseDTO> getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @PostMapping
    public Mono<ProdutoMockResponseDTO> create(@Valid @RequestBody ProdutoMockCreateDTO dto) {
        return service.create(dto);
    }

    @PutMapping("/{id}")
    public Mono<ProdutoMockResponseDTO> update(@PathVariable Long id, @Valid @RequestBody ProdutoMockUpdateDTO dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public Mono<Void> delete(@PathVariable Long id) {
        return service.delete(id);
    }
}
```
*Utilize validação com `@Valid` e mantenha endpoints reativos.*

---

## 7. Handler Global de Exceções

```java
// GlobalExceptionHandler.java
package ao.creativemode.exception;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleResponseStatusException(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(ex.getReason());
    }
    // Adicione outros handlers conforme necessário
}
```
*Centralize tratamento de erros para respostas padronizadas e claras.*

---

## Boas Práticas e Observações

- **Separação de responsabilidades:** mantenha DTOs, entidades, mappers, serviços e controllers em pacotes distintos.
- **Reatividade pura:** nunca use `.block()` ou `.subscribe()` nos fluxos, exceto em testes.
- **Validação:** utilize anotações de validação nos DTOs e `@Valid` nos controllers.
- **Naming conventions:** siga nomes consistentes para arquivos, classes e endpoints.
- **Tratamento de erros:** implemente um handler global para exceções.
- **Mapper centralizado:** facilita manutenção e testes.
- **Documentação:** sempre documente endpoints e regras de negócio.

---

## Como Reaplicar o Padrão para Novas Entidades

1. **Crie a entidade de domínio** em `model/`.
2. **Defina os DTOs** em `dto/<entidade>/` para criação, atualização e resposta.
3. **Implemente o repository** estendendo `ReactiveCrudRepository`.
4. **Crie o mapper** para conversão entre entidade e DTOs.
5. **Implemente o service** com métodos reativos usando Mono/Flux.
6. **Implemente o controller** com endpoints REST reativos.
7. **Adicione o handler global de exceções** se necessário.
8. **Siga as boas práticas** descritas acima.

---

## Referências
- [Spring WebFlux Documentation](https://docs.spring.io/spring-framework/docs/current/reference/html/web-reactive.html)
- [Project Reactor](https://projectreactor.io/)
- [Spring Data R2DBC](https://spring.io/projects/spring-data-r2dbc)

---

Este guia serve como modelo oficial para novos módulos CRUD reativos no backend. Adapte conforme necessário para cada contexto, mantendo a arquitetura e boas práticas.
