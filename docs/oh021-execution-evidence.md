# OH-021 execution evidence

Status: COMPLETE — implementation integrated into `pre-release`; post-merge
governance recorded by the dedicated docs-only closure change.

## Authority rediscovery — 2026-09-12

Repository root: `C:/Dev/OrderHub`; origin:
`https://github.com/PiresRenan/OrderHub.git`. Initial branch `pre-release`, clean
tracked/untracked status. Safe `git fetch --prune origin` completed. Both
`origin/pre-release` and `git ls-remote --heads origin` agree:

- HEAD: `c741cb0bfbfd3c8ec66e2c96ad4bc636d357390f`;
- tree: `e8088e44a0ab7f989c70c6b1e6e1ade1b5b75349`;
- source subtree: `be89f553df23c2cab69be7168b50e7f8f398271a` (OH-020 evidence);
- OH-020 functional PR #45 and governance PR #46 merged;
- Issue #39 closed, Issue #40 open with no initial comments;
- no open PRs; remote branches `main` and `pre-release` only.

The active pre-release ruleset requires PR integration, resolved review threads
and strict checks `ci-build`, `branch-policy`, `platform-validation`. Required
approving review count is zero; no actor can bypass the ruleset. The ordinary
branch-protection endpoint's 404 does not mean the ruleset is absent.

Registered worktrees at discovery:

| Path | Branch / state | HEAD |
| --- | --- | --- |
| `C:/Dev/OrderHub` | `pre-release`, clean | `c741cb0...` |
| `C:/Dev/OrderHub-OH21` | old `feat/OH-021-api-contract-production-readiness`, clean | `f1f7891...` |
| `C:/Dev/OrderHub-OH21-latest-rehearsal` | detached, clean | `f1f7891...` |

The old branch merge-base is `9e87d15e98db88f95f5829f6e8f57a4aadfb4b80`.
Its ten unintegrated commits are provisional OH-019/OH-020 work, not an API
contract implementation. Direct comparison omits integrated lifecycle
migrations V38–V43 and moves retention V44 to V38. No old migration, provisional
ADR status or stale source is imported. History remains intact.

New task branch: `feat/OH-021-verified-api-readiness`, created from the verified
remote authority in the main worktree. Naming follows the enforced repository
branch-policy pattern.

## Discovery findings and refined contract

[ADR-0019](adr/ADR-0019-api-contract-and-production-readiness.md) records the
admitted implementation and acceptance criteria before main implementation.

- 60 real business operations, six controllers, no `/v1` route prefix.
- No existing generated OpenAPI or principal README.
- Exact money/quantity/revision and sealed proof responses need explicit
  metadata beyond reflection; no invented wire fields or secret examples.
- Accepted Flyway history is 42 scripts through V44 (V19/V20 absent), effective
  Flyway 12.4.0. Community B44 support is subject to executable equivalence and
  upgrade proof; accepted scripts remain immutable.
- Compose requires JWT configuration omitted by `.env.example`; no existing
  runnable local identity provider or complete demo bootstrap exists.
- Three Kubernetes `ORDERHUB_JSON_*` variables are inert with the current
  hard-coded parser settings; fix requires a configuration regression.
- Existing health, dependency readiness, shutdown, hardened image and event
  recovery provide the starting posture rather than needing replacement.

Claude Code Sonnet was run with medium effort and read-only `Read,Glob,Grep`
tools for a bounded HTTP inventory. Its operation summary undercounted routes
and mislabeled Orders key reuse; independent source reconciliation corrected
these to 60 and HTTP 422. Agent output is evidence to review, never authority.

## Qualification ledger

The refinement checkpoint is `bb4c9dc223abc20699c43b1648e876175f50460b`.
The task branch is published with upstream tracking and no history rewrite.

Behavioral REDs exposed missing runtime documentation, absent local launcher,
bare authentication/denial bodies and inert JSON environment aliases. The first
generated contract also exposed incorrect integer/string types and overlapping
issued/replay unions. Corrections preserve business authorization and wire
payloads while describing them accurately; fixed 401/403 bodies disclose no
internal cause or private selector.

On 2026-09-13, this command passed **17 tests, zero failures/errors/skips**:

```powershell
.\mvnw.cmd -B '-Dtest=OpenApiContractTest,ProductionHttpPostureTest,ParserEnvironmentBindingTest,DocumentationDriftTest,FlywayBaselineMigrationTest' test
```

The six migration proofs cover fresh B44, physical schema and canonical data
equivalence, historical upgrade preservation, future V45 convergence, immutable
accepted checksums and pooled-session settings. Review found and corrected two
material generator defects: session-scoped dump settings leaked to reused pool
connections, and line-oriented native output corrupted embedded carriage returns
inside nine SQL whitespace constraints. Transaction-local directives and byte
copy/LF-only processing fix these without weakening direct catalog comparisons.
B44 regenerated identically with SHA-256
`ad1b03dbbfd4b1d9ec3a04be4961dfa6a54aaf75bb36b19fd9d93b441f5c07a6`
after representing the nine trim literals with equivalent explicit Unicode
escapes. This avoids hidden controls/trailing whitespace without changing SQL
meaning; all six migration proofs passed again on that final representation.
All 42 accepted versioned scripts remain unchanged.

Independent JSON Schema 2020-12 validation of the regenerated contract accepts
numeric prices and both legitimate proof outcomes; rejects fractional/negative/
overflow prices, unknown request fields, empty or partial proof outcomes; and
verifies every generated int32/int64 property is integer. The contract's 60
operations exactly match MVC handlers. A real-JWT local development acceptance
test previously passed its full stock, Customer Order/replay and persona-denial
flow; it is included again in full verification with runtime dual Flyway locations.

The first full Wrapper `clean verify` passed **1537 tests, zero failures/errors/
skips**, including Modulith, in 10:56 on 2026-09-13. Subsequent baseline escaping
and contract assertions passed 12 focused tests. Sonnet's bounded read-only
security/seed review reported no material findings, but independent follow-up
found an alternate Hikari JDBC property could redirect startup connectivity.
A new RED reproduced that failure with a deliberately unreachable local URL.
The development configuration now constructs its pool from the owned container;
the separate Flyway connection and pre-seed URL check remain pinned.

The production image built successfully. An isolated Compose project using
`.env.example` reached minimal readiness UP, UID 10001:10001, read-only root and
all capabilities dropped. Its database recorded `44 | SQL_BASELINE | true`.
Packaged-JAR inspection confirmed B44 and absence of development/test identity
classes. The owned Compose project and its disposable storage were removed.

Implementation checkpoint `08ec6c6d2ba0f2f8cf5f87bf1d27949bca5f0296`
was published in [PR #47](https://github.com/PiresRenan/OrderHub/pull/47).
All three required checks passed on that candidate: [Java CI](https://github.com/PiresRenan/OrderHub/actions/runs/34769634131),
[platform CI](https://github.com/PiresRenan/OrderHub/actions/runs/34769634073)
and [branch policy](https://github.com/PiresRenan/OrderHub/actions/runs/34769634096).

The documented launcher was executed on the default ports with real PowerShell
token, Customer create/replay/read and Staff Catalog/stock/receipt commands.
The flow passed with one commitment of two units and on-hand 105 after receipt.
The abbreviated README command initially failed because an existing replica
test worker also has a main method; README now names the same explicit launcher
as the detailed guide. No credentials were printed.

[GitHub Codex review](https://github.com/PiresRenan/OrderHub/pull/47#pullrequestreview-5191413121)
found a P2 machine-readable administrative-name bound omission. The original
schema accepted a rejected 121-code-point name. A raw `maxLength: 120` would
incorrectly reject existing supported whitespace-padded names, already covered
by an HTTP test with 120 astral Unicode characters. The correction expresses
the actual stripped 1–120-code-point invariant as an anchored Unicode pattern,
without changing runtime normalization. Fifteen targeted HTTP/contract/docs
tests passed; independent JSON Schema validation rejects 121 while accepting
120 astral characters with padding. A new generated-contract regression compares
the schema pattern with the real validator, including nonbreaking spaces.

The [second Codex review](https://github.com/PiresRenan/OrderHub/pull/47#pullrequestreview-5192356369)
identified two further P2 issues: an ECMAScript consumer without the Unicode
flag counted surrogate halves, and raw brand/display-name caps contradicted
owner normalization. Both were reproduced. Generated patterns now use explicit
code-point atoms that work in Java, Python and ECMAScript with/without Unicode
mode. Product/Category name caps remain raw 160-code-point limits because their
application boundary imposes that limit before normalization; brand/display-name
caps apply after stripping, and their control-character rejection is preserved.
SKU, MPN and attribute values likewise expose their existing nonblank, unpadded,
control-free constraints. Explicit end-of-input assertions prevent regex engines
from accepting a final newline in otherwise bounded identifiers.

Sixteen focused Java tests and **296 ECMAScript string cases** passed on the
second-review correction. The latter run as
`node scripts/verify-openapi-text.mjs` after Maven in CI; no application Node
dependency is introduced. Python JSON Schema checks also confirm normalized
brand/Unicode acceptance and final-newline rejection.

A third exact-HEAD Codex review identified one further P2 contract-fidelity gap:
`POST /orders` documented the `Idempotency-Key` visible-ASCII grammar in prose,
while the generated schema exposed only its 1-128 length bounds. The runtime
already rejects whitespace, comma, controls and non-ASCII input. RED reproduced
the omission against the generated OpenAPI without changing runtime behavior.
The correction publishes the existing grammar as a strict OpenAPI/ECMAScript
pattern: visible ASCII `0x21`-`0x7E`, comma excluded, one through 128 characters,
with explicit end-of-input semantics.

The generated-contract regression covers accepted and rejected keys, including
length, whitespace, comma, controls and non-ASCII input. The existing
ECMAScript portability gate now exercises the same boundary both without flags
and with Unicode mode. After correcting a local shell-encoding test-vector
artifact to use the source escape `\u00E9`, the focused OpenAPI suite passed
**7 tests, zero failures/errors/skips**, and the portability gate passed
**320 generated OpenAPI string cases** in ECMAScript legacy and Unicode modes.

Final local qualification of this implementation candidate used a fresh
Wrapper:

```powershell
.\mvnw.cmd -B clean verify
```

It passed **1541 tests, zero failures/errors/skips** with `BUILD SUCCESS` in
11:41, finishing at `2026-09-14T00:03:33-03:00`. Independent aggregation of the
fresh Surefire/Failsafe XML reports reproduced the same totals. The post-verify
OpenAPI portability gate again passed all **320** ECMAScript cases, and
`git diff --check` remained clean. Raw local qualification logs are retained
outside the repository under `C:/Dev/oh021-evidence`.

## Final integration — 2026-09-14

The final corrective checkpoint was
`b738b8850b139060d9001c90aa82860c28d3d4f3`, tree
`b11d27346d49f7087fd2d8ec8c7aa1adb067cdeb`. The exact-head required workflows
all passed:

- CI run `34801905358` — success;
- Platform CI run `34801905443` — success;
- Branch Policy run `34801905339` — success.

All recorded GitHub review threads were resolved. The remaining
`Idempotency-Key` P2 was answered with the exact regression and qualification
evidence above. At the user's direction no further Codex review was invoked
after that finding because external Codex capacity was unavailable; an explicit
exact-head governance review of the five-file corrective delta found no
unresolved material issue. This limitation is recorded rather than represented
as a Codex approval.

PR [#47](https://github.com/PiresRenan/OrderHub/pull/47) was then squash-integrated
into `pre-release` as
`6e6efc1d8bb13f636c5639afee3fb420abf3105e`. The integrated commit is
signed/verified, has parent
`c741cb0bfbfd3c8ec66e2c96ad4bc636d357390f`, and tree
`b11d27346d49f7087fd2d8ec8c7aa1adb067cdeb`, exactly matching the qualified
candidate tree. The integration therefore introduces no post-qualification
source drift.

The accepted 42 versioned migrations remain immutable. B44 remains the derived
fresh-install checkpoint at SHA-256
`ad1b03dbbfd4b1d9ec3a04be4961dfa6a54aaf75bb36b19fd9d93b441f5c07a6`;
normal evolution continues with V45+. No `main` promotion, release tag or
v1.0.0 qualification occurred; those remain exclusively OH-022.

Post-merge governance promotes ADR-0019 to `TESTED`, marks OH-021 complete in
the roadmap and records this evidence before Issue #40 is closed as completed.
