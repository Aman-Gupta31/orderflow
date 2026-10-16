# Module 2 — Inventory concurrency

## A. Concept

A **race condition** happens when the outcome of concurrent operations depends on timing.
A **lost update** is the classic race: two transactions read the same value, each computes
a new value based on the stale read, and one write clobbers the other. **Optimistic locking**
detects this after the fact (via a version column); **pessimistic locking** prevents it by
holding a database lock for the duration of the transaction; an **atomic conditional UPDATE**
sidesteps the problem entirely by never reading-then-writing in application code at all.

## B. Why

Overselling is the single most visible bug an e-commerce backend can have: two customers both
see "1 in stock," both click buy, and without protection both reservations succeed, leaving
inventory at -1. This module exists to make that structurally impossible, not just unlikely.

## C. How it works internally

The naive, broken approach: `Inventory inv = repo.findByProductId(id); if (inv.getQuantityAvailable()
>= qty) { inv.setQuantityAvailable(inv.getQuantityAvailable() - qty); repo.save(inv); }`. Between
the `findByProductId` read and the `save` write, another thread (or another application
instance entirely) can do the exact same read, see the same pre-decrement value, and also pass
the check. Both saves "succeed" and you've sold more than you had. A Java `synchronized` block
would only serialize threads inside a single JVM — it does nothing for a second application
instance running on another pod/server, which is why the brief explicitly rules it out.

This project uses a single atomic SQL statement instead
(`InventoryRepository.reserve`, backed by `@Modifying @Query`):

```sql
UPDATE inventory
   SET quantity_available = quantity_available - :quantity,
       quantity_reserved  = quantity_reserved + :quantity
 WHERE product_id = :productId
   AND quantity_available >= :quantity
```

MySQL's InnoDB engine evaluates the `WHERE` clause and performs the write as one atomic,
isolated operation per row — it takes an exclusive row lock on the matching row for the
duration of the statement, so a second concurrent `UPDATE` targeting the same row simply waits
until the first commits, then re-evaluates the (now-updated) `WHERE` clause against the new
value. There is no window where both transactions can see the same "1 available" value and both
proceed: whichever UPDATE's WHERE clause is evaluated second sees the row the first one already
decremented. `@Modifying` queries return the number of affected rows instead of an entity;
`reserve()` returning `0` is the signal that the condition failed (not enough stock), and
`InventoryService.reserve` turns that into `InsufficientStockException`.

The `@Version` field on `Inventory` is kept as a secondary guard for any code path that still
does load-then-save on the entity (e.g. a future admin UI that loads, edits, and saves a whole
`Inventory` object) — it's optimistic locking as a safety net, not the primary defense.
`adjustAvailable` (admin restock) uses the same atomic-UPDATE pattern for the same reason: even
an admin action done from two browser tabs shouldn't be able to race.

## D. Code walkthrough

- **`InventoryRepository.reserve`/`release`/`adjustAvailable`** — three purpose-built atomic
  UPDATEs rather than one generic "set quantity" method; each encodes its own invariant in its
  `WHERE` clause (never let available go negative, never let reserved go negative).
- **`InventoryService.reserve`** — responsibility: call the atomic UPDATE, and turn "0 rows
  affected" into a typed exception the global handler maps to `409 Conflict`. It does *not*
  retry — a legitimate "no stock" result should not be retried; this matters because a human
  reviewer glancing at this method should be able to tell at a glance it's not silently
  looping.
- **`OrderService.createOrder`** calls `InventoryService.reserve` once per line item inside its
  own `@Transactional` method — covered in detail in [Module 3](03-order-transactions-state-machine.md).

## E. Request flow

`POST /api/orders` with two items → `OrderService.createOrder` (transaction opens) → for each
item, `InventoryService.reserve(productId, qty)` → one `UPDATE ... WHERE quantity_available >=
qty` per item → if item #2's reserve affects 0 rows, `InsufficientStockException` propagates out
of `createOrder`, Spring's transaction interceptor rolls back the *entire* transaction — including
item #1's already-applied reservation, because it was never committed.

## F. Example

Two concurrent requests both try to reserve the last unit of product 42 (`quantity_available = 1`).
- Request A's `UPDATE` runs first inside its transaction, matches the `WHERE` clause, decrements
  to 0, and that transaction commits.
- Request B's `UPDATE`, whether it started before or after A committed, re-evaluates `quantity_available
  >= 1` against the current committed value — which is now 0 — so 0 rows match, and B gets
  `InsufficientStockException` → `409 Conflict`.

This exact scenario is what `ConcurrentStockReservationIT` automates (see
[docs/TESTING.md](../TESTING.md) for its current run status on this machine).

## G. Interview preparation

**Q: Optimistic vs pessimistic locking — which would you use here and why?**
A: Neither is the primary mechanism — an atomic conditional UPDATE avoids the read-modify-write
cycle both locking strategies are designed to protect. Pessimistic locking (`SELECT ... FOR
UPDATE`) would also work but holds a row lock for the whole service method, including any
app-side logic in between, which hurts throughput under contention. Optimistic locking
(`@Version`) is cheap but means failures surface as "someone else modified this, retry" at the
application layer, retrying load-then-write cycles under high contention — more moving parts
than needed for a single-row decrement.

**Q: Why doesn't `synchronized` solve this?**
A: It only coordinates threads within one JVM process. The moment you run two instances behind
a load balancer (which any real deployment does for availability), `synchronized` provides zero
protection — the two instances have no shared memory to synchronize on. The lock has to live in
the shared resource (the database), not the application.

**Q: What does "0 rows affected" tell you, and what's the common mistake here?**
A: It tells you the WHERE condition didn't hold at the moment the UPDATE ran. Common mistake:
treating a `@Modifying` query's return value as irrelevant and not checking it — that silently
turns a failed reservation into a no-op that looks like success.

## H. Debugging

- Reproduce by hand: open two `mysql` client sessions, start a transaction in each, and race the
  same `UPDATE ... WHERE quantity_available >= 1` statement — watch the second block until the
  first commits, then see it fail the WHERE check.
- If `reserve()` always returns 0 unexpectedly, check `inventory.product_id` actually has a row —
  a missing `Inventory` row looks identical to "zero stock" from this query's perspective; that's
  why `InventoryService.getByProductId` throws a distinct `ResourceNotFoundException` for that
  case instead.
- `ConcurrentStockReservationIT` failing with `NoSuchFileException (/var/run/docker.sock)` means
  Docker isn't running, not a code bug — see [docs/TESTING.md](../TESTING.md).

## I. Revision notes

- **Key takeaways:** never read-then-write stock in application code; let the database's
  atomic UPDATE do the check-and-decrement as one statement; `synchronized` doesn't survive past
  a single process.
- **Important annotations:** `@Modifying`, `@Query`, `@Version`.
- **Important commands:** none new — this is tested via `ConcurrentStockReservationIT` (`./mvnw
  verify`).
- **Memory aid:** "Check and decrement in the same breath — SQL's breath, not Java's."
- **Mini-quiz:**
  1. Why can two threads both reading `quantity_available = 1` both decide to proceed, under the
     naive approach? *(Because the read and the write are two separate operations with a gap
     between them where both can observe the same stale value.)*
  2. What does `reserve()` returning `0` mean, concretely? *(The UPDATE's WHERE clause matched no
     rows — either no inventory row exists for that product, or the available quantity at that
     instant was less than requested.)*
- **Coding task:** write a repository method `restock(productId, quantity)` that's just
  `adjustAvailable` with a positive delta, and add a unit test proving a negative delta that
  would drive stock below zero is rejected.
