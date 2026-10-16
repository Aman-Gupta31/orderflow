# Module 5 — Security: JWT authentication & authorization

## A. Concept

**Authentication** answers "who are you?" (proving identity). **Authorization** answers
"what are you allowed to do?" (permissions), and only makes sense once authentication has
already happened. A **JWT** (JSON Web Token) is a signed, self-contained token: a Base64
header + payload + signature, where the signature lets anyone holding the signing key verify
the payload hasn't been tampered with, without needing to look anything up in a database to
validate the signature itself.

## B. Why

HTTP is stateless and this API has no server-side sessions (`SessionCreationPolicy.STATELESS`
in `SecurityConfig`) - every request must carry proof of identity itself. A JWT lets the client
(a browser, Postman, a future mobile app) attach `Authorization: Bearer <token>` to every
request, and the server can verify it cheaply (one HMAC check) instead of needing a shared
session store. Role-based authorization (`CUSTOMER` vs `ADMIN`) exists because not every action
should be available to every authenticated user - creating a product is fundamentally different
from browsing one.

## C. How it works internally

**Registration/login** (`AuthService`): registration hashes the password with BCrypt
(`PasswordEncoder`) and stores a `User` row with `role = CUSTOMER` always - there is no `role`
field anywhere in `RegisterRequest`, so a client can never grant itself ADMIN by crafting a
request. Login delegates to Spring Security's `AuthenticationManager`, which (via
`DaoAuthenticationProvider`) calls `AppUserDetailsService.loadUserByUsername(email)`, then
compares the submitted password against the stored BCrypt hash using constant-time comparison.
On success, `JwtService.generateToken` signs a token (HS256) containing the user's email as
subject, plus `uid` and `role` claims, expiring in `app.jwt.expiration-seconds` (3600s / 1 hour
by default - see the limitation noted in section I).

**Every subsequent request** passes through `JwtAuthenticationFilter`, registered via
`.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)` in
`SecurityConfig`. It reads the `Authorization` header, and if a `Bearer` token is present and
`JwtService.extractValidSubject` confirms the signature and expiry are valid, it calls
`AppUserDetailsService.loadUserByUsername(email)` **again** - re-querying the database rather
than trusting the token's embedded `role` claim. This is a deliberate trade-off: trusting the
claim would be faster (zero DB hits per request), but a role change or account deactivation
wouldn't take effect until the token expires. Re-loading means it takes effect on the very next
request, at the cost of one extra query per authenticated call - defensible at this scale, and
exactly the kind of trade-off worth stating explicitly in an interview rather than hiding.

**Authorization** happens at two different layers, which is why there are two different error
producers for what looks like the same "you can't do that" situation:

1. **URL-level rules** in `SecurityConfig.securityFilterChain` (`.requestMatchers(...).hasRole("ADMIN")`)
   are evaluated by Spring Security's filter chain *before* the request ever reaches
   `DispatcherServlet`/MVC. A denial here never reaches `@RestControllerAdvice`, so it needs its
   own handler: `RestAccessDeniedHandler` (403) and `RestAuthenticationEntryPoint` (401, for "not
   authenticated at all").
2. **Method-level rules** (`@PreAuthorize` on `OrderController.getById`/`cancel`) run via a Spring
   AOP proxy wrapping the controller method - this executes *during* MVC dispatch, so a denial
   here (an `AccessDeniedException`) **is** caught by `GlobalExceptionHandler`'s own
   `@ExceptionHandler(AccessDeniedException.class)`, the same `@RestControllerAdvice` used for
   every other domain exception in this app.

Ownership enforcement (`@orderSecurity.canAccess(#id)`) is the sharpest part of this design:
`OrderSecurity.canAccess` loads the order and compares `order.getUserId()` to the current
principal's id, but returns `true` (letting the request through) when the order **doesn't
exist** - otherwise a customer requesting someone else's order ID would get `403 Forbidden`
and a customer requesting a nonexistent ID would *also* get `403`, both masking the real `404`
the controller method itself would otherwise produce. Getting this distinction right - 403
means "I can see this exists but you can't touch it," 404 means "nothing to see here" - is
exactly the kind of detail interviewers probe for.

## D. Code walkthrough

- **`UserPrincipal`** - adapts the `User` entity to Spring Security's `UserDetails` contract,
  kept as a separate class so the JPA entity doesn't have to implement a framework interface.
- **`JwtService.generateToken`/`extractValidSubject`** - the only two operations this project
  needs from JWTs: issue, and validate-then-read-subject. `extractValidSubject` returns `null`
  (not an exception) on any failure - malformed, expired, wrong signature - so the filter has one
  simple branch: null means "don't authenticate this request," full stop.
- **`JwtAuthenticationFilter`** - checks `SecurityContextHolder.getContext().getAuthentication()
  == null` before doing anything, so it never overwrites an authentication another mechanism
  (like `@WithMockUser` in tests) already set.
- **`AdminSeeder`** - the only way an ADMIN account gets created. Reads `ADMIN_EMAIL`/
  `ADMIN_PASSWORD` from environment variables with **no hardcoded fallback** - if either is
  missing, it logs a warning and does nothing, rather than falling back to an insecure default
  that would end up committed to source control.
- **`OrderSecurity`** - see the ownership-check discussion in section C. Registered as
  `@Component("orderSecurity")` specifically so `@PreAuthorize`'s SpEL can reference it by that
  bean name (`@orderSecurity.canAccess(...)`).

## E. Request flow

`GET /api/orders/5` with `Authorization: Bearer <token>`:
1. `JwtAuthenticationFilter` validates the token, loads the fresh `UserDetails` for that email,
   populates `SecurityContextHolder`.
2. Spring Security's authorization filter checks URL-level rules - `/api/orders/**` isn't in any
   `permitAll`/`hasRole` matcher, so it falls to `.anyRequest().authenticated()`, which passes
   (a token was present and valid).
3. Request reaches `DispatcherServlet`, which resolves `OrderController.getById` - but first, the
   `@PreAuthorize` AOP proxy evaluates `hasRole('ADMIN') or @orderSecurity.canAccess(5)`.
4. If that SpEL expression is false, `AccessDeniedException` is thrown right there, caught by
   `GlobalExceptionHandler`, mapped to `403`.
5. If true, `OrderController.getById` runs, calls `OrderService.getById`, returns the DTO.

## F. Example

```json
POST /api/auth/login
{"email": "alice@example.com", "password": "AlicePass123!"}

200 OK
{"accessToken": "eyJhbGciOi...", "tokenType": "Bearer", "expiresInSeconds": 3600}
```
Bob (a different customer) requesting Alice's order:
```json
GET /api/orders/1
Authorization: Bearer <bob's token>

403 Forbidden
{"status": 403, "error": "Forbidden", "message": "You do not have permission to perform this action", ...}
```

## G. Interview preparation

**Q: Why re-query the database for the user on every request instead of trusting the JWT's
role claim?**
A: A JWT, once issued, can't be "un-issued" before it expires - if you trust its embedded role
claim, a demoted or deactivated user keeps their old permissions until the token's natural
expiry (here, up to an hour). Re-loading the user via `UserDetailsService` on every request
closes that gap at the cost of one extra DB read per request - an explicit, documented
trade-off, not an oversight.

**Q: What's the limitation of this JWT implementation, stated honestly?**
A: No refresh-token rotation. Access tokens last 1 hour (`JWT_EXPIRATION_SECONDS`) and once
expired, the client must log in again with credentials - there's no long-lived refresh token to
silently mint new access tokens. This is a real, acknowledged gap, not something papered over.

**Q: Why does `OrderSecurity.canAccess` return `true` for a nonexistent order?**
A: So authorization and resource-existence stay separate concerns: a missing order should
surface as `404` (from the controller/service, which still runs `ResourceNotFoundException`
after a `canAccess` pass), not get masked by a `403` that implies "it exists but isn't yours."

**Q: Why are there two different mechanisms producing 401/403 (`RestAuthenticationEntryPoint`/
`RestAccessDeniedHandler` vs. `GlobalExceptionHandler`)?**
A: They intercept at different points in the request lifecycle - URL-level security-filter
denials happen *before* Spring MVC dispatch even starts (so `@RestControllerAdvice` can't see
them), while `@PreAuthorize`/ownership-check denials happen *during* MVC dispatch (so
`@RestControllerAdvice` does see them). Both are wired to produce the same `ApiError` JSON
shape so a client never has to tell them apart.

**Common mistake:** hashing passwords with a fast, non-adaptive hash (plain SHA-256, MD5) or -
worse - storing them in plain text. BCrypt is deliberately slow and salted per-password, which
is the whole point: it makes brute-forcing a stolen hash database expensive.

## H. Debugging

- A `401` with "A valid access token is required" on every request, even right after login,
  usually means the `Authorization` header isn't being sent, or is missing the `Bearer ` prefix.
- A `403` where you expected `200`: check the role actually embedded in the token
  (`echo '<payload-part>' | base64 -d` on the middle JWT segment) matches what the endpoint
  requires, and separately check `OrderSecurity.canAccess` isn't comparing against the wrong id.
- Startup failing with a `DecodingException`/`WeakKeyException` from `JwtService`: `JWT_SECRET`
  is either not valid Base64 (check for an embedded newline - `openssl rand -base64 64` wraps
  output across two lines by default; strip it with `tr -d '\n'`) or too short for HS256 (needs
  ≥32 decoded bytes). This exact bug happened once during this project's own local verification -
  see `docs/TESTING.md`.
- `LazyInitializationException` appearing on an endpoint that touches `Order.items`: see
  [docs/learning-guide/03-order-transactions-state-machine.md](03-order-transactions-state-machine.md)
  for the real instance of this bug found during Stage 3 verification and how it was fixed
  (DTO mapping must happen inside the transactional service method, never after).

## I. Revision notes

- **Key takeaways:** authentication (who) vs authorization (what); BCrypt for passwords, never
  anything faster; stateless JWTs mean re-validating identity on every request, not trusting a
  session; URL-level vs method-level authorization are different mechanisms with different error
  paths; ownership checks must preserve 404 vs 403 semantics.
- **Important annotations:** `@EnableWebSecurity`, `@EnableMethodSecurity`, `@PreAuthorize`,
  `@AuthenticationPrincipal`.
- **Important commands:** `openssl rand -base64 64 | tr -d '\n'` to generate a JWT secret.
- **Memory aid:** "Sign it, check it, re-load it - never just trust it."
- **Mini-quiz:**
  1. Why does `RegisterRequest` have no `role` field? *(So a client can never self-grant ADMIN;
     every self-registered account is CUSTOMER by construction.)*
  2. What's the one concrete limitation of this project's JWT setup that a real production
     system would need to close? *(No refresh-token rotation - tokens are only short-lived
     access tokens.)*
- **Coding task:** add a `PUT /api/users/me/password` endpoint (change own password, requiring
  the current password) and write a test proving it returns `401` when the current password is
  wrong.
