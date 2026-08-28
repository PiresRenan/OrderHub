# ADR-0004 — Containerized Development and Runtime Platform

Status: DESIGNED

## Context

OrderHub has completed the initial Orders HTTP and domain hardening and is
ready to introduce durable infrastructure and distributed-system concerns.

Installing each infrastructure dependency directly on the developer host would
create machine-specific state, unmanaged caches, conflicting versions and an
environment that is difficult to reproduce in CI or production-like scenarios.

The platform must also prepare OrderHub for horizontal application scaling,
controlled deployment, health management and future failure/load testing before
database-specific architecture is introduced.

The current development host already provides:

- Docker Desktop using the WSL2 Linux engine;
- Docker Engine;
- Docker Compose;
- kubectl;
- Kustomize.

A standalone kind CLI is the only additional host-level platform tool required
for the initial Kubernetes development environment.

## Decision

OrderHub will use containers as the default execution boundary for
infrastructure and production-like application runtime dependencies.

Infrastructure services such as PostgreSQL, PgBouncer, Redis, Kafka and
observability components will not be installed directly on the Windows
development host.

The development/runtime platform will use three distinct execution levels.

### Level 1 — Automated integration tests

Testcontainers will provide disposable infrastructure required by automated
integration tests.

Tests must be responsible for starting and disposing of their infrastructure
where practical.

### Level 2 — Local development stack

Docker Compose will provide persistent or multi-service development
environments intended for interactive local development.

Compose environments will use explicitly named resources and documented
lifecycle commands.

### Level 3 — Production-like orchestration

Kubernetes will be used for production-like workload behavior including:

- multiple replicas;
- service discovery;
- rolling updates;
- health probes;
- graceful termination;
- resource requests and limits;
- disruption behavior;
- future autoscaling;
- failure and recovery experiments.

Local Kubernetes clusters will run through kind on top of the existing Docker
engine.

Docker Desktop's built-in Kubernetes cluster will remain disabled to avoid
maintaining two independent local Kubernetes environments.

## Host Tooling Policy

The host is limited to development and control-plane tooling that cannot
reasonably be part of the workload itself.

The intended host toolset is:

- Git;
- VS Code;
- JDK required for direct local development;
- Maven Wrapper;
- Docker Desktop / Docker Engine;
- Docker Compose;
- kubectl / Kustomize;
- kind.

Service runtimes and infrastructure servers must run in containers.

Additional host-level tools require a new demonstrated need rather than being
installed preemptively.

## Image Policy

Committed container definitions must not use floating `latest` tags.

Runtime and infrastructure images must use explicit versions.

Security-sensitive and reproducibility-critical environments should pin OCI
image digests when practical.

Base-image updates are deliberate dependency changes and must pass the same
verification flow as application changes.

The local kind Kubernetes node image will initially use Kubernetes 1.36.1
pinned by digest.

## Application Container

OrderHub will use a multi-stage container build.

The build stage may contain JDK and Maven tooling required to create the
artifact.

The final runtime stage must contain only the runtime dependencies required to
execute OrderHub.

The final container must:

- avoid running as root unless a documented technical blocker exists;
- contain no source-control metadata;
- contain no developer credentials;
- contain no build-time secrets;
- expose only required runtime ports;
- support deterministic graceful shutdown.

The host JDK or Maven installation must not be required to execute the final
container image.

## Docker Build Context

A `.dockerignore` will explicitly exclude files that do not belong in the image
build context, including at minimum:

- Git metadata;
- IDE metadata;
- local build outputs;
- local logs;
- secrets;
- local infrastructure state.

Reducing build context is both a performance and information-exposure control.

## Local Storage and Cache Policy

Docker-managed images, layers, containers, volumes and build cache remain under
the Docker runtime rather than being distributed across host-installed service
directories.

Persistent development state must use explicitly named Docker volumes when
persistence is required.

Disposable environments must have documented teardown commands.

Volume destruction must be explicit.

Broad host-wide prune operations must not be part of ordinary project startup
or shutdown because they may remove resources belonging to unrelated projects.

Build caches may be pruned using bounded age/space policies when storage
pressure justifies it.

## Kubernetes Configuration

Kubernetes manifests will be maintained using Kustomize.

The repository will separate reusable base configuration from
environment-specific overlays.

The intended structure is:

infra/
  kubernetes/
    base/
    overlays/
      local/
      staging/
      production/

Helm will not be introduced until packaging, distribution or configuration
complexity demonstrates a concrete advantage over Kustomize.

## Local Kubernetes Profiles

OrderHub will eventually maintain at least two kind configurations.

### Development profile

A resource-efficient cluster for ordinary local Kubernetes verification.

### Scale/failure profile

A multi-node cluster used for:

- replica distribution;
- pod failure;
- rolling deployment;
- disruption testing;
- future autoscaling and load experiments.

The larger profile is not required to remain running during ordinary
development.

## Health Model

Kubernetes health checks must not use business endpoints as generic health
probes.

OrderHub will expose explicit lifecycle-aware health information suitable for:

- startup probes;
- liveness probes;
- readiness probes.

Readiness must represent whether a replica should receive traffic.

Liveness must represent whether the process requires restart.

Dependency failure must not automatically imply liveness failure when restarting
the process cannot correct the dependency.

## Resource Management

Every production-like workload will eventually define CPU and memory requests
and limits.

Values introduced before benchmark evidence are safety baselines, not statements
of proven production capacity.

Horizontal scaling must not be designed independently from downstream capacity.

In particular, future OrderHub replica counts and connection-pool sizes must be
bounded by the available PostgreSQL connection and transaction budget.

Kubernetes autoscaling must therefore not be treated as an unlimited solution
to database saturation.

## Stateless Application Requirement

OrderHub application replicas must remain stateless with respect to
cross-request correctness.

Process-local constructs such as:

- synchronized blocks;
- Java locks;
- in-memory maps;
- local counters;

must not be used to enforce invariants shared across replicas.

Shared correctness must be implemented through durable/distributed mechanisms
appropriate to the invariant, including future database constraints,
transactions, atomic operations and idempotency records.

## Secrets

Secrets must not be committed to Git.

Secrets must not be embedded in images.

Docker Compose and Kubernetes development configurations may reference external
or generated secret values, but committed examples must contain only synthetic
or placeholder values.

Production secret management is deferred to the environment/platform-specific
security phase.

## Scalability Boundary

OH-006 establishes the platform required to test horizontal scaling but does
not claim application or database scalability.

Subsequent tasks will separately establish and verify:

- PostgreSQL persistence;
- database concurrency and isolation behavior;
- connection-pool budgets;
- PgBouncer;
- load shedding and backpressure;
- horizontal autoscaling;
- durable idempotency;
- promotion/coupon/loyalty concurrency invariants.

Keeping these concerns separated allows each mechanism to be tested against a
specific failure model rather than assuming Kubernetes makes application state
distributed-safe.

## Verification Strategy

OH-006 must prove at minimum:

- the application image builds reproducibly;
- the final container starts successfully;
- the final process does not run as root unless explicitly justified;
- existing automated tests remain green;
- Docker Compose configuration is valid;
- disposable Compose resources can be cleaned deterministically;
- kind cluster creation is reproducible;
- Kubernetes manifests can be applied successfully;
- OrderHub becomes ready through explicit health checks;
- multiple OrderHub replicas can run concurrently;
- deleting a replica does not destroy application state required for correctness;
- rolling replacement can complete without direct client coupling to a pod.

Performance and maximum-capacity claims require later benchmark evidence.

## Node-failure evidence

OH-006 validated abrupt worker loss using a three-node kind cluster with one
control-plane and two workload-eligible workers.

The OrderHub replicas were distributed across distinct
`kubernetes.io/hostname` topology domains before the experiment.

A worker hosting one OrderHub replica was terminated abruptly with SIGKILL,
without Kubernetes drain or graceful node shutdown.

Observed behavior:

- the surviving replica remained healthy on the second worker;
- Service traffic experienced transient failures before endpoint convergence;
- EndpointSlice converged to one Ready backend after approximately 40.7 seconds;
- the affected Node was recorded as `NotReady` approximately 45.96 seconds
  after the simulated failure;
- after convergence, 20 consecutive Service health requests succeeded;
- the affected Pod contained the Kubernetes default 300-second `NoExecute`
  tolerations for both `node.kubernetes.io/not-ready` and
  `node.kubernetes.io/unreachable`;
- no replacement Pod was observed during the initial 80-second failure window;
- the worker recovered before the 300-second toleration expired;
- Kubernetes cancelled the pending TaintManager eviction and recreated the
  affected Pod sandbox/container on the recovered worker;
- the Deployment returned to two Ready replicas and the Service returned to two
  Ready endpoints.

The four transient Service failures observed during this local kind experiment
are evidence of convergence behavior, not a production availability SLO or
capacity benchmark.

### Failover policy decision

OH-006 intentionally retains the Kubernetes default node-failure tolerations.

Shorter application-specific tolerations could reduce the time before
rescheduling after a prolonged node failure, but they do not directly remove
the earlier node/endpoint convergence window observed by this experiment.

Aggressive rescheduling also increases the importance of correctness under
network partitions, where the control plane may replace a workload whose
original process has not actually stopped.

Custom failover timing is therefore deferred until shared persistence,
idempotency and concurrency guarantees exist and can be tested under duplicate
execution and partition scenarios.

## Consequences

The repository gains additional infrastructure-as-code and container build
artifacts.

Local development becomes more reproducible and less dependent on host-installed
services.

Container images and caches consume Docker-managed disk space and therefore
require deliberate lifecycle management.

Kubernetes introduces operational complexity, but that complexity is introduced
before durable distributed state so later persistence and concurrency work can
be evaluated in a realistic multi-replica environment.

## Follow-up Decisions

ADR-0005 will define PostgreSQL persistence and transaction boundaries.

Later ADRs may define:

- concurrency/retry policy;
- connection pooling;
- idempotency;
- transactional outbox;
- production Kubernetes/managed platform topology;
- database high availability.

## References

- Docker Desktop / WSL2 documentation
- Docker Compose documentation
- Docker BuildKit documentation
- Kubernetes workload and health-probe documentation
- Kubernetes version-skew policy
- kind documentation
- Kustomize documentation
