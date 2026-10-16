# Module 1 — Architecture, JPA & the Product module

## A. Concept

**Layered architecture** splits the app into Controller (HTTP in/out) → Service (business
rules, transactions) → Repository (persistence) → Database. **JPA** (Jakarta Persistence API)
is a specification for mapping Java objects to relational tables; **Hibernate** is the
implementation Spring Boot wires up by default. **DTOs** (Data Transfer Objects) are the
shapes that cross the HTTP boundary; they are deliberately not the same classes as your
`@Entity` objects.

## B. Why

If a controller calls the database directly, business rules end up copy-pasted across
endpoints and are impossible to unit test without spinning up HTTP. Services give you one
place to enforce a rule ("price must come from the server, not the client") and one place
to put `@Transactional` boundaries. DTOs matter for a concrete reason, not cargo-culting:
`Product` is a JPA entity — if you return it directly from a controller, Jackson will try to
serialize its lazy associations, you leak persistence-layer details (like Hibernate proxy
classes) into your API contract, and a column rename becomes a breaking API change instead
of an internal refactor.

## C. How it works internally

Spring Boot's auto-configuration scans for `@SpringBootApplication` (which is
`@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan` combined) and, seeing
`spring-boot-starter-data-jpa` on the classpath plus a `DataSource` bean it can build from
`application.yml`, wires up an `EntityManagerFactory`, a `PlatformTransactionManager`, and
implements every interface that extends `JpaRepository` with a dynamic proxy — you never
write the implementation of `ProductRepository`, Spring generates it at startup by parsing
method names (`findByActiveTrueAndNameContainingIgnoreCase`) into JPQL.

Dependency injection: every `@Service`, `@Repository`, `@RestController` is a Spring bean.
Constructor injection (used throughout this codebase, e.g. `ProductService(ProductRepository,
InventoryService)`) is preferred over field injection because it makes dependencies visible,
makes the class impossible to construct in an invalid state, and makes unit testing trivial —
you just call `new ProductService(mockRepo, mockInventoryService)`, no Spring context needed.

## D. Code walkthrough

- **`Product`** (`product/domain/Product.java`) — the entity. Note the protected no-arg
  constructor (required by JPA/Hibernate to instantiate via reflection) alongside a public
  constructor that enforces required fields. `@PrePersist`/`@PreUpdate` hooks stamp
  `createdAt`/`updatedAt` — this runs in the persistence layer, so it can never be forgotten
  in a service method.
- **`ProductRepository`** — `existsBySku` is used before insert to give a clean 409 instead
  of letting a `DataIntegrityViolationException` bubble up from the unique constraint.
- **`ProductService.create`** — responsibility: validate SKU uniqueness, persist the
  `Product`, then call `InventoryService.createInitialStock` *in the same transaction* so a
  product is never left without an inventory row. Trade-off: this couples `ProductService` to
  `InventoryService`; the alternative (an event-driven "product created" flow) is overkill at
  this scale and is exactly the pattern introduced later for Kafka, once there's an async
  boundary that justifies it.
- **`ProductController`** — intentionally thin: deserialize, call one service method,
  serialize. No `if`/business logic lives here.

## E. Request flow

`POST /api/products` → `ProductController.create` (Bean Validation runs on `ProductRequest`
before the method body executes, via `@Valid`) → `ProductService.create` (opens a
transaction) → `ProductRepository.save` (INSERT) → `InventoryService.createInitialStock`
(INSERT into `inventory`, same transaction) → commit → `ProductResponse.from(product)` →
`201 Created` with the DTO as JSON.

## F. Example

Request:
```json
POST /api/products
{"name": "Mechanical Keyboard", "description": "Hot-swappable", "sku": "KB-100", "price": 89.99, "initialQuantity": 50}
```
Response (`201`):
```json
{"id": 1, "name": "Mechanical Keyboard", "sku": "KB-100", "price": 89.99, "active": true, "createdAt": "...", "updatedAt": "..."}
```
Failure scenario: posting the same SKU twice → second request gets `409 Conflict` with
`{"error": "Conflict", "message": "SKU already exists: KB-100"}`.

## G. Interview preparation

**Q: Why DTOs instead of returning the entity directly?**
A: Decouples the API contract from the persistence model, avoids serializing lazy
associations/Hibernate proxies, and lets the database schema evolve without breaking clients.

**Q: Lazy vs eager loading — which did you use and why?**
A: `OrderItem → Order` is `FetchType.LAZY` (the default for `@ManyToOne` is actually EAGER,
so this was explicit) — loading an item rarely needs the parent order eagerly; forcing eager
fetch here would mean every `OrderItem` query triggers an extra join or N+1 queries for data
usually not needed. Follow-up: "What's N+1?" — it's issuing one query to fetch N parent rows
then N additional queries (one per row) for an association, instead of one join; JPA's lazy
loading can cause this if you iterate a collection and touch the lazy association per item.

**Q: Why constructor injection over `@Autowired` field injection?**
A: Immutability, explicit required dependencies, and testability without a Spring context.
Common mistake: using field injection and then being unable to write a plain unit test
without reflection hacks.

## H. Debugging

- `./mvnw spring-boot:run` then `curl -i localhost:8080/api/products` to see the handler
  chain actually respond.
- Set `logging.level.org.hibernate.SQL=DEBUG` (not yet in `application.yml` — add it
  temporarily) to see the exact SQL Hibernate issues per request.
- A `ConstraintViolationException` at startup usually means your entity's `@Column` doesn't
  match the Flyway-created schema — `ddl-auto: validate` is deliberately strict about this.

## I. Revision notes

- **Key takeaways:** entity ≠ DTO; services own transactions and business rules; Spring
  generates repository implementations from interface method names.
- **Important annotations:** `@Entity`, `@Table`, `@Id`, `@GeneratedValue`, `@PrePersist`,
  `@PreUpdate`, `@Valid`, `@RestController`.
- **Important commands:** `./mvnw spring-boot:run`, `./mvnw test`.
- **Memory aid:** "Controller talks, Service decides, Repository remembers."
- **Mini-quiz:**
  1. Why can't `Product` have only a public constructor requiring all fields? *(Hibernate
     needs a no-arg constructor to instantiate entities via reflection before populating
     fields.)*
  2. What HTTP status should a duplicate SKU return, and why not 400? *(409 Conflict — the
     request is well-formed, it conflicts with existing state, which is what 409 means.)*
- **Coding task:** add a `GET /api/products/by-sku/{sku}` endpoint using the existing
  `getBySku` repository method, returning 404 via `ResourceNotFoundException` when absent.
