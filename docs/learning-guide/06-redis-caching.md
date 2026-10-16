# Module 6 — Redis caching

## A. Concept

A **cache hit** means the data you need is already sitting in a fast store (Redis, in
memory); a **cache miss** means it wasn't there, so you fall back to the real source of
truth (MySQL) and, typically, store the result for next time. **Cache-aside** (a.k.a.
lazy-loading) is the pattern where the application, not the cache, is responsible for that
fallback-and-populate logic — which is exactly what `@Cacheable` automates. **TTL**
(time-to-live) is how long a cached entry is allowed to live before it's considered stale and
evicted automatically, without anyone having to explicitly delete it.

## B. Why

`GET /api/products/{id}` is read-heavy and read-mostly (products change far less often than
they're viewed) — a textbook cache-aside candidate. Caching it means repeat reads for a popular
product skip a MySQL round trip entirely. The two real risks to manage are **staleness** (the
cache showing an old price/name after an update) and **unavailability** (what happens to the
whole request if Redis itself is down) — both are addressed explicitly below, not left implicit.

## C. How it works internally

`ProductService.getById` is annotated `@Cacheable(value = "products", key = "#id")`. Like
`@Transactional`, this works via a Spring AOP proxy: an external call to `getById(7)` first asks
the configured `CacheManager` for a cache named `"products"`, looks up key `7`. On a hit, the
method body never runs at all — the cached value is returned directly. On a miss, the real
method runs (hits `ProductRepository.findById`), and the returned `Product` is serialized and
written to Redis under key `products::7` before being returned to the caller.

`ProductService.update` and `ProductService.deactivate` are annotated
`@CacheEvict(value = "products", key = "#id")` — removing that one key the moment the underlying
row changes, rather than waiting for the TTL to expire. **TTL is the second line of defense**,
not the only one: it exists to bound staleness from any write path that *isn't* explicitly
evicted (there currently isn't one for this cache, but it's a bound on unknowns, not a crutch),
currently configured at `PRODUCT_CACHE_TTL_SECONDS` (default 300s / 5 minutes) in
`CacheConfig`.

**Serialization** matters more than it looks: `GenericJackson2JsonRedisSerializer` converts the
`Product` object to JSON for storage and back. Its own no-arg constructor builds an internal
`ObjectMapper` with two things already configured — "default typing" (embedding an `@class`
field so a later `deserialize()` call, which only gets raw bytes with no type hint from the
cache abstraction at that point, knows to reconstruct a `Product` and not a generic
`LinkedHashMap`) and *not* `java.time` support. This project supplies its own `ObjectMapper`
instead (to add `java.time` support), which **replaces** the default one rather than extending
it — so both java.time support *and* default typing have to be configured by hand on the
custom mapper. Missing either one causes a real, distinct failure (see section H) — this project
hit both, in sequence, during its own live verification, and both are fixed in `CacheConfig`.

**Resilience**: Redis here is an optimization, not a dependency the app's correctness relies on.
`LoggingCacheErrorHandler` intercepts any Redis-originating exception during a cache get/put/
evict/clear and logs it instead of letting it propagate — so a request that would otherwise fail
with a `500` just runs as if caching weren't configured at all (always correct, just not sped
up). For this to actually be *fast* fallback rather than a *multi-minute hang*,
`spring.data.redis.timeout`/`connect-timeout` are both set to `1s` — Lettuce's own defaults are
far longer, and the difference was the single biggest bug this module surfaced (see section H).

## D. Code walkthrough

- **`CacheConfig`** implements `CachingConfigurer` (not just `@Bean CacheManager`) specifically
  so both the custom `CacheManager` *and* the custom `CacheErrorHandler` are guaranteed to be
  wired together — Spring Boot's auto-configured `RedisCacheManager` backs off automatically
  once a `CacheManager` bean is present (`@ConditionalOnMissingBean`).
- **`LoggingCacheErrorHandler`** is a small, standalone class (not an anonymous inner class)
  specifically so it can be reused identically by both the real `CacheConfig` and
  `CacheResilienceTest` — the test proves the exact handler that runs in production, not a
  stand-in that merely resembles it.
- **`ProductService.getById`/`update`/`deactivate`** — see section C. Note `update` and
  `deactivate` both call `this.getById(id)` internally to load the entity; that's a
  *self-invocation*, which bypasses the `@Cacheable` proxy entirely (Spring AOP only intercepts
  calls arriving from outside the bean) — documented in detail, with a test proving the exact
  call count, in `docs/learning-guide/01-architecture-and-jpa.md`'s sibling test
  `ProductServiceCacheTest`.

## E. Request flow

`GET /api/products/7` (cache hit) → `ProductController.getById` → AOP proxy intercepts the call
to `ProductService.getById` *before* the method body → `CacheManager.getCache("products").get(7)`
→ Redis `GET products::7` → JSON deserialized back into a `Product` (using the embedded `@class`
hint) → returned directly, `ProductRepository`/MySQL never touched for this request.

`PUT /api/products/7` → `ProductService.update` runs normally (loads entity via self-invoked
`getById`, mutates it, transaction commits) → **after** the method returns, the `@CacheEvict`
advice runs `CacheManager.getCache("products").evict(7)` → Redis `DEL products::7`. The next
`GET /api/products/7` is therefore guaranteed a cache miss and re-reads the fresh row.

## F. Example — a real, repeatable measurement (not invented)

Run against this project's own local MySQL + Redis, 2026-10-15 (see `docs/TESTING.md` for the
exact commands):

| Call | Path | `time_total` |
|---|---|---|
| 1st `GET /api/products/1` | cold — MySQL + cache populate | `0.223s` |
| 2nd `GET /api/products/1` | cache hit | `0.011s` |
| 3rd `GET /api/products/1` | cache hit | `0.004s` |

This is a single local run on one machine, not a load test — it demonstrates the mechanism
works and roughly how much a MySQL round trip costs relative to a Redis one here, not a
production-representative throughput number. To reproduce: time a `curl` to a product endpoint
immediately after startup (guaranteed cold), then time it again immediately after (guaranteed
cache-hit, same process, same TTL window).

## G. Interview preparation

**Q: Why is only `getById` cached, not the list/search endpoint?**
A: A list/search result's cache key would have to encode every distinct combination of name
filter, page, size, and sort the clients actually request — a much larger and messier key space
than "one key per product id" — and invalidating "every list variation that might include this
product" on a single write is far harder to get right than evicting one key. Get-by-id is the
highest-value, lowest-complexity candidate; the list endpoint is already served efficiently by
an indexed, paginated SQL query.

**Q: What's your stale-data policy?**
A: Explicit eviction on every known write path (`update`, `deactivate`) handles the common case
immediately; TTL (5 minutes, configurable) bounds staleness for anything that isn't an explicit
eviction path. A customer could theoretically see a stale product for up to the TTL window in an
edge case — an accepted, bounded, documented trade-off, not an unlimited one.

**Q: What happens if Redis goes down mid-traffic?**
A: Every cache operation throws; `LoggingCacheErrorHandler` catches and logs it instead of
propagating, so the annotated method just runs as if uncached — correct responses, slower.
Verified live: stopping the local Redis service, hitting the endpoint, and confirming `200`
responses continued (at ~2s instead of ~5ms, bounded by the 1s Lettuce timeout, not by multi-
minute default behavior — see the debugging section for why that bound mattered).

**Q: Redis vs. a plain in-memory `java.util.Map` — why Redis?**
A: A `ConcurrentHashMap` cache would be invisible across multiple app instances (each process
gets its own copy, so one instance's eviction after an update wouldn't clear another instance's
stale entry) and wouldn't survive an app restart. Redis is shared and external, so cache state
is consistent across however many instances of this app are running.

**Common mistake:** assuming `@Cacheable` calls made from within the same class go through the
cache. They don't — see the self-invocation note in section D, and the test that actually proves
the resulting call count.

## H. Debugging

Three real, distinct bugs were found and fixed building this module — each is worth knowing as
a *category* of Redis-caching bug, not just a one-off:

1. **`Could not write JSON: Java 8 date/time type 'java.time.Instant' not supported`** — the
   serializer's `ObjectMapper` has no JSR-310 module registered. Fix: `new
   ObjectMapper().findAndRegisterModules()` (or register `JavaTimeModule` explicitly) before
   handing the mapper to `GenericJackson2JsonRedisSerializer`.
2. **`ClassCastException: LinkedHashMap cannot be cast to Product`**, appearing specifically on
   a genuine cache *hit* (not a miss) — the serializer's `ObjectMapper` has no "default typing"
   activated, so deserialized JSON comes back as a generic `Map`, not the original type. This one
   is sneaky because it only surfaces on read, so a quick "did the PUT succeed" check (e.g. `redis-
   cli EXISTS`) looks fine right up until something actually reads the value back. Fix:
   `objectMapper.activateDefaultTyping(validator, DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY)`
   — and check the resulting JSON in `redis-cli GET` for an `"@class"` field as direct evidence
   it's configured correctly.
3. **A ~120-second request hang when Redis was stopped**, rather than a fast, graceful fallback.
   Lettuce's own default command timeout is long, with background reconnect attempts in between
   - `LoggingCacheErrorHandler` was already correct and already prevented a `500`, but "correct
   after two minutes" isn't a resilience story worth claiming. Fix: `spring.data.redis.timeout`
   and `connect-timeout` both set to `1s`, verified to cut the same scenario down to ~2 seconds.

General debugging tools: `redis-cli KEYS 'products*'`, `redis-cli GET <key>` (read the raw cached
JSON directly — the `@class` field is the fastest way to confirm serialization is configured
correctly), `redis-cli TTL <key>` (confirm expiry is set, and roughly how long is left),
`redis-cli FLUSHALL` (nuke all cached state during local testing), and application logs for
`LoggingCacheErrorHandler` WARN lines, which name the exact cache operation and key that failed.

## I. Revision notes

- **Key takeaways:** cache-aside via `@Cacheable`/`@CacheEvict`; TTL bounds staleness, explicit
  eviction handles the common case; a cache failure must degrade speed, not correctness — and
  "degrades" has to mean seconds, not minutes, which requires an explicit timeout; self-invocation
  silently bypasses Spring AOP caching.
- **Important annotations:** `@EnableCaching`, `@Cacheable`, `@CacheEvict`.
- **Important commands:** `redis-cli KEYS 'products*'`, `redis-cli GET <key>`, `redis-cli TTL
  <key>`, `brew services stop/start redis` (for exactly this kind of live outage test).
- **Memory aid:** "Cache speeds you up; it should never be able to slow you down for minutes or
  take you down at all."
- **Mini-quiz:**
  1. Why does supplying a custom `ObjectMapper` to `GenericJackson2JsonRedisSerializer` require
     re-adding default typing, when the no-arg constructor "just works"? *(The no-arg constructor
     builds its own mapper with default typing already active; supplying your own mapper replaces
     that mapper entirely rather than extending it.)*
  2. Why did the `ClassCastException` only appear on a cache hit, not a cache miss? *(A miss never
     deserializes anything — it runs the real method and returns its live object. Only a hit reads
     bytes back out of Redis and has to reconstruct a Java type from them.)*
- **Coding task:** add a `@Cacheable` for `InventoryService.getByProductId` with a much shorter
  TTL (e.g. 10s, since stock changes far more often than product details), and explain in a
  comment why its eviction story is harder than the product cache's (stock changes via atomic
  SQL `UPDATE`s in `InventoryRepository`, which have no entity to hang a `@CacheEvict` off of).
