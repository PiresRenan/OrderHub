# ADR-0002 — Orders API Hardening and Privacy

Status: TESTED

## Context

The first OrderHub vertical slice can create orders through HTTP, application,
domain and an in-memory outbound adapter.

Before introducing persistent storage and additional consumers, the HTTP
contract and domain boundaries must reject malformed, ambiguous or dangerous
input in a predictable way and must avoid unnecessary disclosure of personal
data.

Public HTTP endpoints also require explicit resource-exhaustion protections so
that syntactically valid input cannot cause disproportionate parsing, memory or
application work.

## Decision

OrderHub will:

- validate structural input at the HTTP boundary with Jakarta Bean Validation;
- preserve business invariants inside the domain independently of HTTP;
- return RFC 9457 Problem Details for API errors;
- never return rejected input values, internal exception details or stack traces;
- reject unknown JSON properties to detect client contract drift and common typos;
- reject duplicate JSON object properties because their interpretation is ambiguous;
- reject floating-point values for integral API fields instead of silently coercing them;
- maintain explicit response-negotiation failures such as HTTP 406 under the stable
  OrderHub Problem Details contract;
- configure Jackson parser constraints for document length, nesting depth and token
  count instead of depending on permissive library defaults;
- apply an externalized Orders HTTP item-count safety limit before application
  processing;
- treat parser constraints and adapter resource limits as technical safeguards,
  not domain or commercial business rules;
- return parser-level constraint failures through the generic malformed-request
  contract rather than inspecting library exception-message text;
- return HTTP 413 with `REQUEST_TOO_LARGE` when a structurally valid order exceeds
  the Orders adapter item-count safety limit;
- avoid logging request/response bodies or personal identifiers by default;
- treat identifiers linked to natural persons as personal data for engineering purposes;
- apply defensive copies to mutable collections crossing domain boundaries;
- verify module boundaries automatically with Spring Modulith;
- maintain separate HTTP DTOs, application commands and domain models.

## Technical limits

The current HTTP safety baseline is:

- maximum JSON document length: 1 MiB;
- maximum JSON nesting depth: 64;
- maximum JSON token count: 100,000;
- maximum Orders HTTP item count: 1,000.

These values are defensive operational defaults.

They do not represent empirically proven capacity limits or commercial order
rules and must be recalibrated through benchmark, production telemetry and
business requirements when that evidence becomes available.

The item-count limit remains outside the domain model so that transport-level
resource protection cannot accidentally become a business invariant.

## Consequences

Clients receive stable machine-readable failures.

Malformed, ambiguous and excessively expensive traffic is rejected before
reaching application and persistence layers whenever technically possible.

JSON parser behavior is stricter than permissive Jackson defaults, reducing
coercion and parser-differential ambiguity.

Parser safety constraints introduce deliberate finite resource boundaries and
duplicate-property detection introduces additional parsing work. Performance
costs must be measured rather than assumed.

Domain invariants remain protected when future adapters such as messaging,
gRPC or batch processing bypass the HTTP layer.

Persistence, retention, encryption, authentication and tenant isolation remain
separate concerns and will be addressed in their respective architectural phases.

## Verification

The decision is currently verified by automated tests covering:

- HTTP request validation;
- malformed and incompatible JSON values;
- unknown and duplicate JSON fields;
- rejected floating-point-to-integer coercion;
- HTTP media negotiation;
- privacy-safe Problem Details;
- configured parser constraints;
- excessive nesting;
- document-length enforcement;
- token-count enforcement;
- Orders item-count boundary behavior;
- invalid operational configuration;
- Spring Modulith structural boundaries;
- full create-order integration.

## References

- LGPD / ANPD principles of necessity, prevention and security
- RFC 9457 Problem Details
- Spring Framework ProblemDetail / ResponseEntityExceptionHandler
- Spring Boot Jackson configuration
- Jackson StreamReadConstraints
- Spring Modulith structural verification