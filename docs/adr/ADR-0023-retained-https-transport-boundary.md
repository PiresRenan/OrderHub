# ADR-0023 — Retained HTTPS transport boundary

Status: PROPOSED. Accepted on the governed squash merge of the OH-027 pull request;
qualification evidence is recorded there and in the promoted `pre-release` checks.

Task: OH-027, [Issue #60](https://github.com/PiresRenan/OrderHub/issues/60).
Cross-project context: OrderHub-Web-BFF BFF-013 (#14), BFF ADR-0009 D027 (retained
security envelope: browser -> BFF, BFF -> Identity and BFF -> OrderHub over HTTPS).

Classification: retained deployment transport capability. It adds no public operation
(61 remain), no migration (V1–V46 remain) and no JWT or business-authority change. Its
v1.0.0 scope classification is an owner decision recorded outside this ADR (ADR-0020).

## Problem

The retained Kubernetes baseline exposed OrderHub only as plaintext HTTP on Service port
`8080`, with HTTP probes, and the image ran HTTP by default. D027 requires the BFF to
reach OrderHub over HTTPS with normal CA and hostname validation. The BFF cannot satisfy
that by changing only itself.

## Decision

**OrderHub terminates TLS itself** on its embedded Tomcat listener (Spring Boot 4.1.1).
It uses a Spring Boot SSL bundle loaded from deployment-owned PEM files. No proxy,
sidecar, ingress, mesh, PKI product or secrets manager is introduced.

| Contract | Value |
| --- | --- |
| Activation | Spring profile `retained`, pinned by an explicit `env` entry in the base Deployment. Explicit `env` overrides `envFrom`, so no ConfigMap can remove it. |
| Scheme / port | `https` on container and Service port `8443` (non-privileged, parallel to the local `8080` convention). |
| Plaintext listener | None in the retained topology. Plaintext requests to `8443` get Tomcat's TLS-required `400`, never an application response. |
| Service | `ClusterIP` Service `orderhub`, single port named `https`, `targetPort: https`. |
| Canonical BFF-facing identity | `orderhub.<namespace>.svc`, where `<namespace>` is the deployment namespace. |
| Optional alternate identity | `orderhub.<namespace>.svc.cluster.local`, only where the cluster domain is `cluster.local` and the deployment chooses to use it. |
| Required SAN | DNS SAN for the exact identity the BFF uses, at minimum `orderhub.<namespace>.svc`. Hostname verification uses SANs, never CN. No wildcard is required. |
| Server material | Kubernetes Secret `orderhub-tls`, type `kubernetes.io/tls`, keys `tls.crt` (leaf, optionally followed by intermediates) and `tls.key` (passwordless PEM, PKCS#8 or PKCS#1). |
| Mount | Volume `orderhub-tls` at `/etc/orderhub/tls`, `readOnly: true`, `optional: false`, `defaultMode: 0440`. Read through `fsGroup 10001`; the process stays UID/GID 10001 with a read-only root filesystem. |
| Bundle | `server.ssl.bundle=orderhub-server`; `spring.ssl.bundle.pem.orderhub-server.keystore.certificate=file:/etc/orderhub/tls/tls.crt` and `.private-key=file:/etc/orderhub/tls/tls.key`. |
| Client authentication | None. mTLS is out of scope. |
| Protocols | JDK 21 defaults (TLS 1.3 and 1.2; older protocols disabled by the JDK). |

### Fail closed

- **Missing Secret:** the pod cannot start (`optional: false`). With `maxUnavailable: 0`
  existing replicas keep serving, and the rollout does not progress.
- **Missing, unreadable or malformed files:** the SSL bundle cannot be created and the
  application refuses to start. Diagnostics name the path, never the file contents.
- **No HTTP downgrade:** the retained profile has no plaintext connector and no default
  that re-enables one.

### Ownership and trust

The deployment/security owner supplies the certificate, private key and issuing CA. Git,
the image, the BFF source and OrderHub business code own none of them. No real
certificate, key, password or CA is committed. The Docker build context
(`.dockerignore` deny-by-default) cannot contain TLS material.

The BFF trusts the **issuing CA** (or intermediate) of the OrderHub serving certificate.
It verifies the chain (PKIX) and the hostname against the SAN. The BFF-side configuration
is BFF-owned and is not defined here.

### Probes

Startup `/livez`, readiness `/readyz` and liveness `/livez` keep their paths, periods,
thresholds and meaning. They use `scheme: HTTPS` on the same `https` port. The probes
therefore exercise the same TLS connector that serves application traffic, keeping the
existing "no separate management connector" invariant.

The kubelet does not verify serving certificates for HTTPS probes. This is documented
Kubernetes behaviour, not an OrderHub trust decision. Probes carry no credentials, the
health endpoints expose only `{"status":…}`, and no client trust path relies on probe
verification.

### Local and test topologies

- **Compose, the disposable developer launcher and the default (no-profile) application**
  remain plaintext HTTP on `8080`, bound to loopback. They never activate `retained`.
- **Kubernetes overlays `local` and `scale`** inherit the HTTPS-only base.
- **CI** generates a disposable synthetic CA and leaf for each run.
- **The offline first-operator bootstrap command** (ADR-0022) runs without a web server
  (`WebApplicationType.NONE`). It opens no listener and does not use the `retained`
  profile.

### Rotation

Certificate hot reload (`reload-on-update`) is deliberately not enabled or claimed.

- **Leaf or private-key rotation:** update `orderhub-tls` with a leaf that has the same
  SANs and is issued by a CA the BFF already trusts. Then run
  `kubectl rollout restart deployment/orderhub`. The existing `maxSurge 1 /
  maxUnavailable 0`, readiness gating and PDB keep capacity during the restart.
- **CA rotation (ordered):**
  1. Distribute a trust bundle containing old and new CAs to the BFF.
  2. Deploy that BFF change.
  3. Rotate the OrderHub leaf to one issued by the new CA and restart as above.
  4. Remove the old CA from BFF trust only after no serving certificate chains to it.

Disabling validation is never a rotation step.

### Rollback

- **Bad leaf, wrong SAN, bad key or wrong chain:** restore the previous known-good
  `orderhub-tls` content and run `rollout restart`. A replica that cannot load its
  material never becomes Ready, so the previous replicas keep serving under
  `maxUnavailable: 0`.
- **BFF trust not yet deployed:** keep or restore the leaf chained to the CA the BFF
  trusts.
- **Failed rollout:** `kubectl rollout undo deployment/orderhub`.

Retaining previous known-good material is a deployment prerequisite. Rollback never uses
HTTP, trust-all or hostname-verification disablement.

## Alternatives rejected

- **Deployment TLS termination or re-encryption proxy** (ingress controller, NGINX or
  Envoy sidecar): requires a new infrastructure product. It also leaves a
  plaintext hop or duplicates certificates, for no gain over native termination.
- **Service mesh / workload mTLS:** no accepted mesh authority exists. It is a new
  product and adds mTLS semantics that are out of scope.
- **Separate plaintext management port for probes:** reintroduces an HTTP listener in the
  retained topology and breaks the same-connector health invariant. Its only benefit, a
  verifying probe, is unavailable anyway because kubelet probes never verify.
- **Boolean `TLS_ENABLED` switch:** a dangerous default that could silently fall back to
  HTTP. Topology/profile separation is used instead.

## Invariants

- JWT (GENERIC/COGNITO profiles, issuer, audience, token type, time validation), internal
  identity binding, business authorization and Tenant semantics are unchanged. TLS peer
  identity confers no authority.
- OpenAPI: 61 operations, canonical LF sha256
  `ba175bc784ddd2d519b54cf9cbc7a8a28a51065bacb5d1210f4f7d4df7c1dc8a`, unchanged.
- Migrations: V1–V46, unchanged.
- Deployment envelope unchanged, except for the port, probe scheme, profile env and TLS
  volume: 2 replicas, rolling update, `minReadySeconds`, grace period, PDB, topology
  spread, non-root, read-only root, dropped capabilities, no service-account token.

## Evidence

- `RetainedHttpsTransportTest`: real Tomcat TLS with externally supplied synthetic PEM.
  - Correct CA with canonical and alternate SANs succeeds.
  - A client trusting a different CA fails PKIX.
  - A hostname outside the SAN fails the handshake.
  - Plaintext requests on the retained port are refused.
  - Real RS256 JWT behaviour over HTTPS matches the plaintext boundary: valid, wrong
    issuer, wrong audience, garbage, absent and unbound tokens.
- `RetainedHttpsFailClosedTest`:
  - Absent material refuses startup.
  - A missing key refuses startup.
  - A malformed key refuses startup without leaking its canary.
- `RetainedTransportManifestTest`: the Service, Deployment, probes, Secret mount,
  preserved envelope, the ConfigMap free of TLS material, the profile, Compose/shared
  isolation, image metadata and the CI contract.
- Platform CI (kind, both profiles):
  - Service HTTPS with a synthetic CA.
  - Wrong CA and wrong hostname give curl exit 60.
  - Plaintext refusal.
  - Secret mode `440`, read-only mount, and a fail-closed rollout without the Secret.
  - Leaf rotation through Secret update and rolling restart, with the new serial served.
  - Key canary absent from the image and logs.

kind proves only local Kubernetes, Service and TLS semantics. It does not prove
production throughput, capacity, provider SLA or internet ingress.
