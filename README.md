# ContractGuard

Portfolio reference implementation of a 2021 project, built to show automated API compatibility gates. It combines an OpenAPI breaking-change detector with consumer-driven contracts (Pact), so that an API change which would break the mobile app or checkout is caught on the PR, not in production.

**Stack:** Java 17, Jackson (YAML/JSON), Pact JVM 4.3 (JUnit 5), GitHub Actions, Pact Broker.

## OpenAPI diff (`OpenApiDiff`)
The detector compares two OpenAPI 3 documents. It follows local `$ref`s and flattens `allOf`. Each change is classified as **BREAKING**, **WARNING** or **INFO**, and the classification depends on the change's **direction**:

| Change | Request schema | Response schema |
|---|---|---|
| field removed | warning (silently ignored), or breaking if `additionalProperties: false` | **breaking** |
| field became required or optional | new required field is **breaking** (unless it has a `default`) | required→optional is **breaking** |
| enum value added | info | **breaking** (strict clients fail to parse) |
| enum value removed | **breaking** | info |
| `maxLength` / `maximum` lowered, `minLength` / `minimum` raised | **breaking** | ignored |
| `integer` → `number` | safe widening | **breaking** |
| became `nullable` | ignored | **breaking** |

Breaking changes at the operation level:
- A path or operation was removed.
- A required parameter was added, or an optional one became required.
- A parameter was removed.
- A 2xx response was removed.
- Auth or OAuth scopes were added.

Renaming a path parameter (`{id}` → `{orderId}`) is correctly **not** reported, because path parameters are positional on the wire.

```bash
mvn -q package
java -cp target/classes:... com.ericguerra.contractguard.Cli specs/orders-v1.yaml specs/orders-v2.yaml --format markdown
# exit 1 when there are un-allowed breaking changes
```
`specs/orders-v2.yaml` is the release that got blocked. It added the `PARTIALLY_SHIPPED` status, dropped `shippingMethod`, and lowered `limit` from 100 to 50.

## Consumer-driven contracts (Pact)
`CheckoutOrdersPactTest` records only what checkout depends on:
- `id`, `status` (any upper-case value, since the client maps unknown values to `UNKNOWN`), and `total.amount` / `total.currency`.
- The 404 path.

The provider verifies the pact in its own build, and `can-i-deploy` gates each release.

**Why both:** the diff catches spec-level breaks before any code runs. The pacts catch breaks the spec can't express, such as "checkout needs `total.currency` even though the spec only says `total` is an object".

## CI
`.github/workflows/api-contract.yml`:
- On PRs that touch `specs/`, it diffs against the base branch and posts the markdown report as a sticky PR comment. A `breaking-change-approved` label allows known breaks during a coordinated migration.
- It also publishes pacts and runs `can-i-deploy`.

## Test
```bash
mvn test   # diff rules (direction-aware), bundled spec gate, Pact consumer tests against the Pact mock server
```
