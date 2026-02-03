Here’s a full English version of your CRUD implementation guide, adapted for the entities you listed:

---

# Reactive CRUD Implementation Guide for the Project

This guide details the standard pattern for implementing CRUD operations in the backend located at `services/backend-api`, using the `schoolYears` CRUD as a reference. To implement any new CRUD, replace `[EntityName]` with the name of your entity and refer to the `schoolYears` CRUD files for practical examples.

---

## File Structure and Purpose

### 1. Controller

* **Path:** `src/main/java/ao/creativemode/kixi/controller/[EntityName]Controller.java`
* **Purpose:** Defines HTTP routes and exposes the REST API for the entity. Receives requests, validates data, and delegates to the service layer.
* **Integration:** Depends on the corresponding service. Uses DTOs for input and output.
* **Best Practices:**

  * Annotate with `@RestController` and `@RequestMapping`.
  * Use reactive types (`Mono`, `Flux`).
  * Validate inputs with `@Valid`.
  * Return standardized responses using `ResponseEntity`.
  * Refer to `schoolYearsController` for endpoint examples and naming conventions.

### 2. Service

* **Path:** `src/main/java/ao/creativemode/kixi/service/[EntityName]Service.java`
* **Purpose:** Implements business logic for the CRUD operations. Performs additional validations and orchestrates repository operations.
* **Integration:** Depends on the repository. Uses DTOs and entities.
* **Best Practices:**

  * Annotate with `@Service`.
  * Methods should be reactive (`Mono`, `Flux`).
  * Centralize entity-to-DTO conversion.
  * Handle soft delete, restore, and hard delete.
  * See `schoolYearsService` for method examples and business logic.

### 3. Repository

* **Path:** `src/main/java/ao/creativemode/kixi/repository/[EntityName]Repository.java`
* **Purpose:** Interface for database access using Spring Data R2DBC.
* **Integration:** Used by the service layer. Operates on entities.
* **Best Practices:**

  * Extend `ReactiveCrudRepository`.
  * Define custom methods for soft delete (`findAllByDeletedFalse`, etc.).
  * Check `schoolYearsRepository` for custom query examples.

### 4. Model (Entity)

* **Path:** `src/main/java/ao/creativemode/kixi/model/[EntityName].java`
* **Purpose:** Represents the database table.
* **Integration:** Used by repository and service layers.
* **Best Practices:**

  * Annotate with Spring Data annotations (`@Table`, `@Id`, `@Column`).
  * Include utility methods for soft delete and restore.
  * See `schoolYears` entity for structure examples.

### 5. DTOs

* **Path:** `src/main/java/ao/creativemode/kixi/dto/[entityName]/`
* **Purpose:** Transfer data between layers and define API contracts.
* **Integration:** Used in controller and service layers.
* **Best Practices:**

  * Use `record` for immutability.
  * Validate fields with annotations (`@NotNull`, `@Positive`).
  * See `schoolYears` DTOs for examples of structure and validation.

### 6. Database Migration

* **Path:** `src/main/resources/db/migration/Vx__create_[table_name]_table.sql`
* **Purpose:** Creates the database table.
* **Integration:** Automatically executed on application startup.
* **Best Practices:**

  * Include constraints for uniqueness and integrity.
  * Refer to the `schoolYears` migration for table structure and constraints.

### 7. Configurations

* **Path:** `src/main/resources/application.properties`
* **Purpose:** Configures database connections and Spring properties.
* **Integration:** Used by the framework.
* **Best Practices:**

  * Do not commit real passwords. Use example configuration files.
  * See project config for property examples.

### 8. Exceptions and Global Handler

* **Path:** `src/main/java/ao/creativemode/kixi/common/exception/`
* **Purpose:** Centralizes error handling and standardizes API responses.
* **Integration:** Used across all layers.
* **Best Practices:**

  * Use Problem Details (RFC 9457) format.
  * Provide handlers for validation and generic errors.
  * Check existing exception handlers for usage examples.

---

## CRUD Operation Flow

### Create

* **Controller:** Receives creation DTO, validates, calls `service.create()`.
* **Service:** Converts DTO to entity, saves via repository, returns response DTO.
* **Repository:** Persists the entity.

### Read (List and Get by ID)

* **Controller:** Provides endpoints to list active items, deleted items, and fetch by ID.
* **Service:** Fetches entities via repository, converts to response DTOs.
* **Repository:** Provides custom queries for active/deleted items.

### Update

* **Controller:** Receives update DTO, validates, calls `service.update()`.
* **Service:** Fetches entity, updates fields, saves, and returns response DTO.

### Soft Delete

* **Controller:** Calls `service.softDelete()`.
* **Service:** Marks entity as deleted, saves.
* **Repository:** Updates record.

### Restore

* **Controller:** Calls `service.restore()`.
* **Service:** Restores deleted entity, saves.

### Hard Delete

* **Controller:** Calls `service.hardDelete()`.
* **Service:** Removes entity from database.

Refer to `schoolYears` CRUD for detailed flow examples.

---

## General Best Practices

* Keep DTOs, entities, services, controllers, and repositories in separate packages.
* Use reactive types (`Mono`, `Flux`) in all layers.
* Avoid `.block()` or `.subscribe()` outside tests.
* Validate inputs with annotations and `@Valid`.
* Centralize entity-to-DTO conversion.
* Always implement soft delete where applicable.
* Centralize error handling with a global handler.
* Document endpoints and business rules.

---

## Implementing a New CRUD

1. Create the entity in `model/` using `[EntityName]`.
2. Define DTOs in `dto/[EntityName]/`.
3. Implement the repository.
4. Implement the service.
5. Implement the controller.
6. Create migration for the table.
7. Update the global handler if needed.
8. Follow the best practices above.
9. Refer to `schoolYears` CRUD for practical examples.

---

## Important Notes

* **Many-to-Many Relationships:**

  * If **no extra data** exists in the join table, **do not create a separate entity**; use `@ManyToMany` annotations in related entities.
  * If **extra columns exist** (beyond FK references), **always create an entity** for the join table and handle it like a normal entity (with CRUD, DTOs, etc.).

* **DTOs:**
  Always create DTOs for data transfer between layers and **never expose entities directly** in API endpoints.

* **Service Layer:**
  Recommended to use a service layer to keep controllers clean and separate business logic.

---

This guide represents the official standard for reactive CRUDs in this project. Adapt as necessary while maintaining architecture and conventions.
*Centralize entity-to-DTO conversions to simplify maintenance.*

---

**Entities in the Project:**

[ERM](https://github.com/creative-mode/kixi/blob/prod/conceptual/architecture/db/erm.md)