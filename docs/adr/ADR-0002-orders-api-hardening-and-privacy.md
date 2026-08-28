# ADR-0002 — Orders API Hardening and Privacy

Status: TESTED

## Context

The first OrderHub vertical slice can create orders through HTTP, application,
domain and an in-memory outbound adapter.

Before introducing persistent storage and additional consumers, the HTTP
contract and domain boundaries must reject malformed or dangerous input in
a predictable way and must avoid unnecessary disclosure of personal data.

## Decision

OrderHub will:

- validate structural input at the HTTP boundary with Jakarta Bean Validation;
- preserve business invariants inside the domain independently of HTTP;
- return RFC 9457 Problem Details for API errors;
- never return rejected input values, internal exception details or stack traces;
- reject unknown JSON properties to detect client contract drift and common typos;
- avoid logging request/response bodies or personal identifiers by default;
- treat identifiers linked to natural persons as personal data for engineering purposes;
- apply defensive copies to mutable collections crossing domain boundaries;
- verify module boundaries automatically with Spring Modulith;
- maintain separate HTTP DTOs, application commands and domain models.

## Consequences

Clients receive stable machine-readable failures.

Invalid traffic is rejected before reaching application and persistence layers.

Domain invariants remain protected when future adapters such as messaging,
gRPC or batch processing bypass the HTTP layer.

Persistence, retention, encryption, authentication and tenant isolation remain
separate concerns and will be addressed in their respective architectural phases.

## References

- LGPD / ANPD principles of necessity, prevention and security
- RFC 9457 Problem Details
- Spring Framework ProblemDetail / ResponseEntityExceptionHandler
- Spring Modulith structural verification