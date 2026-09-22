# Release experiment

Run separately from other builds/tests or workloads. Java 21, Docker Linux
containers and a successfully verified test classpath are required. The probe
creates its own disposable application, issuer and PostgreSQL database; it does
not read `.env`, attach an operator database, or mutate unrelated containers.

After `./mvnw -B clean verify`, build the classpath:

```powershell
.\mvnw.cmd -B dependency:build-classpath "-Dmdep.outputFile=target/qualification-classpath.txt"
$dependencies = (Get-Content target/qualification-classpath.txt -Raw).Trim()
$classpath = "target/classes;target/test-classes;$dependencies"
java --class-path $classpath scripts/qualification/OperationalProbe.java 15 2 5 > qualification.log 2>&1
```

On POSIX use `./mvnw` and `:` classpath separators. Arguments are stage seconds,
repeat count and warmup seconds. Output records prefixed `OH022` carry metrics;
ordinary framework logs are separate. Inspect exit status and all stages, not
only the fastest run. No credentials should be printed or committed.

The mixed workload uses one contended SKU: catalog/inventory/administrative
reads, receipt/adjustment with replays, Order create/replay and expected 422
fingerprint conflict. Each repeat runs concurrency 1, 4, 16, 64, then 1 for
recovery. It reports per-operation percentiles/max/status counts, throughput,
JVM CPU/heap/GC, sampled pool occupancy/waits, database statistics and sampled
owned-container CPU/memory. Expected 422 is distinguished from errors.

After load, the probe deliberately holds all pool connections to observe bounded
failure, releases them, and checks recovery. It then temporarily revokes LOGIN
from the owned synthetic database role and terminates its other connections,
retaining one recovery connection to restore LOGIN in a finally block. This
checks dependency unavailability, probes, failed authenticated reads, recovery
and unchanged stock without Docker's ephemeral-port remapping. It does not
simulate a PostgreSQL server crash. All owned resources close when the experiment
finishes; shutdown/recovery failures invalidate the result.

This is a closed-loop, single-replica experiment with client and application in
the same JVM, a small growing dataset and no imposed container resource limits.
It cannot establish open-loop capacity, internet/JWK latency, multiple-replica
throughput, production ingress behavior or an SLA. Pool saturation, queueing and
recovery must be interpreted in that environment. The normal verification suite
separately exercises cross-process correctness and publication restart recovery.
