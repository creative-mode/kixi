# ADR 0012: Choice of Primary Key Type for Kixi Core Entities

## Status
Pendent

---

## Context

Kixi is a school management platform built with a Java Spring Boot backend and a PostgreSQL database. The main entities include `SchoolYear`, `Term`, `Student`, and `Class`, all interconnected through foreign key relationships. The choice of data type for the primary key (PK) is critical, as it affects query performance, referential integrity, system readability, and future possibilities for integration or expansion to distributed architectures. Several options were considered, including sequential integers (`BIGINT`), globally unique identifiers (`UUID`), lexicographically sortable identifiers (`ULID`), and arbitrary strings.

---

## Decision

After technical analysis, it was decided to adopt `BIGINT auto-increment` as the primary key type for all core Kixi entities. This choice provides highly efficient indexing, fast joins, and native compatibility with Java (`Long`), keeping the implementation simple and maintainable. Sequential IDs are short, readable, and predictable, which facilitates debugging and testing. While solutions like UUIDs and ULIDs offer global uniqueness and, in the case of ULID, sortability, the disadvantages of these alternatives outweigh their benefits in the current context of Kixi, making `BIGINT` the most appropriate option.

---

## Why not UUID

Although UUIDs provide global uniqueness, they present several disadvantages for Kixi. Their larger size results in larger indexes and reduced performance in joins and queries. UUID v4 is not sortable, potentially causing index fragmentation in PostgreSQL, while UUID v1 includes timestamp and hardware information, making debugging more complex. Furthermore, their long textual representation reduces readability in logs and manual testing. For a centralized system like Kixi, the global uniqueness offered by UUID is unnecessary.

---

## Why not ULID

ULID offers global uniqueness and lexicographic ordering, representing a modern alternative to UUID. However, its generation requires external libraries in the application, increasing complexity. Although more compact than UUID (26 characters), it is still significantly larger than a `BIGINT` (8 bytes), affecting index size and storage. Its readability is lower, making debugging and manual data handling more cumbersome. Since Kixi is currently a centralized system, the need for globally unique and sortable IDs is not critical, making ULID unnecessary at this stage.

---

## Why not Strings

Using strings as primary keys increases index size and reduces performance for joins and queries. Strings also require additional validation and can introduce inconsistencies if not carefully managed. While they provide readability and flexibility, for Kixi, the efficiency and simplicity of `BIGINT` outweigh these advantages.

---

## PK Type Comparison

| Type    | Example                                | Sortable | Size                | Notes                                                   |
| ------- | -------------------------------------- | -------- | ------------------- | ------------------------------------------------------- |
| BIGINT  | 1, 2, 3…                               | Yes      | 8 bytes             | Sequential, fast, readable, native in Java/SQL          |
| UUID v4 | `550e8400-e29b-41d4-a716-446655440000` | No       | 16 bytes / 36 chars | Random, globally unique, not sortable                   |
| UUID v1 | `6ba7b810-9dad-11d1-80b4-00c04fd430c8` | Yes      | 16 bytes / 36 chars | Sortable, includes timestamp/MAC, less readable         |
| ULID    | `01ARZ3NDEKTSV4RRFFQ69G5FAV`           | Yes      | 16 bytes / 26 chars | Sortable, globally unique, requires application library |

---

## Consequences

All Kixi primary keys will use `BIGINT`, mapped to Java `Long`, ensuring maximum performance, simplicity, and readability. Foreign keys will follow the same pattern, maintaining referential integrity and efficient joins. This decision keeps the system robust and easy to maintain, while allowing future migration to ULID or UUID if distributed architecture or system integration becomes necessary.

