# Module 4 — Error handling & API standards

## A. Concept

**Bean Validation** (`jakarta.validation`, implemented by Hibernate Validator) lets you declare
constraints (`@NotBlank`, `@DecimalMin`) on a DTO and have them enforced automatically before a
controller method body runs. A **global exception handler** (`@RestControllerAdvice`) is a
single place that converts every exception type the app can throw into a consistent JSON error
shape and the right HTTP status code.

## B. Why

Without centralization, every controller method needs its own try/catch, and different
developers inevitably produce different error JSON shapes for the same failure class, which
makes client-side error handling miserable. It also risks leaking internals: an uncaught
exception's default handling in Spring can include a stack trace in the response — fine in
local dev, a security and professionalism problem in anything resembling production.

## C. How it works internally

`@RestControllerAdvice` registers `GlobalExceptionHandler` as an interceptor that Spring's
`DispatcherServlet` consults whenever a controller method (or validation) throws. Spring picks
the *most specific* matching `@ExceptionHandler` method for the thrown exception's type — so
`InsufficientStockException` (a `RuntimeException` subclass) is caught by
`handleInsufficientStock`, not the catch-all `handleUnexpected`, even though the catch-all's
parameter type (`Exception`) would also technically match. The catch-all exists as a safety net
for anything not explicitly modeled yet, and it intentionally returns a generic message while
logging the real exception with `log.error(..., ex)` — the stack trace goes to your logs, where
only you can see it, never into the HTTP response.

Bean Validation triggers *before* your handler method's body ever runs: `@Valid` on a
`@RequestBody` parameter tells Spring to run the validator against the deserialized object, and
if any constraint fails, Spring throws `MethodArgumentNotValidException` immediately — your
`ProductController.create` method body never executes for an invalid request.

## D. Code walkthrough

- **`ApiError`** (`common/response/ApiError.java`) — a `record`, chosen because it's an
  immutable data carrier with no behavior; every error response has the same shape
  (`timestamp`, `status`, `error`, `message`, `path`, `fieldErrors`), so a client only has to
  learn to parse this once.
- **`GlobalExceptionHandler.handleValidation`** — maps every failed field constraint to an
  `ApiError.FieldError(field, message)` so a client can highlight the exact form field that
  failed, not just show a generic "bad request" toast.
- **`GlobalExceptionHandler.handleUnexpected`** — the catch-all; logs full detail server-side,
  returns a deliberately generic `"An unexpected error occurred"` to the client.
- Each domain exception (`ResourceNotFoundException`, `InsufficientStockException`,
  `InvalidOrderStateException`, `DuplicateResourceException`) is a small, purpose-named
  `RuntimeException` subclass rather than one generic `BusinessException` with a status code
  field — this keeps the *meaning* of the failure in the type system (a reviewer sees
  `throw new InsufficientStockException(...)` and immediately knows which HTTP status and
  which business rule is involved), at the small cost of one extra class per failure kind.

## E. Request flow

`POST /api/products` with `price` missing → Spring binds the JSON to `ProductRequest` → `@Valid`
triggers Hibernate Validator → `@DecimalMin`/`@NotNull` fails on `price` →
`MethodArgumentNotValidException` thrown before `ProductController.create`'s body runs →
`GlobalExceptionHandler.handleValidation` catches it → `400 Bad Request` with a `fieldErrors`
entry for `price`.

## F. Example

```json
400 Bad Request
{
  "timestamp": "2026-10-15T01:08:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/products",
  "fieldErrors": [{"field": "price", "message": "must not be null"}]
}
```

## G. Interview preparation

**Q: 400 vs 404 vs 409 vs 422 — when would you use each?**
A: `400` — the request itself is malformed or fails validation (missing required field, wrong
type). `404` — the request is well-formed but the referenced resource doesn't exist. `409` —
the request is well-formed and the resource exists, but applying it conflicts with the current
state (insufficient stock, duplicate SKU, invalid state transition). `422` is sometimes used
instead of `400` for semantically invalid-but-well-formed input; this project uses `400` for all
Bean Validation failures for consistency, and documents that choice rather than mixing both.

**Q: Why not just let Spring's default error handling return its built-in error page/JSON?**
A: Spring Boot's default `/error` response shape differs from a custom shape you control, and
by default (depending on configuration) can include more internal detail than you want exposed.
A `@RestControllerAdvice` gives one guaranteed, documented shape regardless of which exception
fired.

**Q: What's the risk of a single catch-all `Exception` handler, and how did you mitigate it?**
A: The risk is masking real bugs behind a generic 500 with no visibility. Mitigated by logging
the full exception with stack trace server-side (`log.error(..., ex)`) before returning the
generic client-facing message — the information isn't lost, it's just not exposed externally.

## H. Debugging

- Hit any endpoint with deliberately bad JSON (`curl -X POST .../api/products -d '{}' -H
  'Content-Type: application/json'`) and confirm you get the `fieldErrors` array, not a stack
  trace.
- If a new exception type isn't being mapped correctly, check `GlobalExceptionHandler` picks the
  most specific applicable `@ExceptionHandler` — an exception that doesn't extend any of the
  four domain exceptions falls through to the generic 500 handler by design.
- Check `logging.level.com.aman.orderflow=INFO` in `application.yml` — bump it to `DEBUG`
  locally to see more detail while diagnosing a handler not firing as expected.

## I. Revision notes

- **Key takeaways:** validate at the boundary (DTOs), centralize exception-to-response mapping,
  never leak stack traces to clients, log full detail server-side instead.
- **Important annotations:** `@RestControllerAdvice`, `@ExceptionHandler`, `@Valid`,
  `@NotBlank`, `@DecimalMin`.
- **Important commands:** `curl -i -X POST localhost:8080/api/products -H 'Content-Type:
  application/json' -d '{}'` to see a live 400 response.
- **Memory aid:** "One handler, one shape, no surprises."
- **Mini-quiz:**
  1. Why does `MethodArgumentNotValidException` get thrown *before* your controller method body
     runs? *(`@Valid` triggers validation during argument resolution, which happens before the
     method is invoked.)*
  2. What's logged, and what's returned to the client, for an unexpected `NullPointerException`?
     *(Full stack trace logged via `log.error`; client gets a generic 500 message with no stack
     trace.)*
- **Coding task:** add a `@ExceptionHandler` for `MethodArgumentTypeMismatchException` (thrown
  when, e.g., `/api/products/abc` is requested and `abc` can't convert to `Long`), returning
  `400` instead of the default `500`.
