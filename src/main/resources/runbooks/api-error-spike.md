# API Error Rate Spike (RB-API-002)

<!-- meta: version=v1; effectiveFrom=2020-01-01; effectiveTo=2999-12-31; jurisdiction=GLOBAL; product=ALL; tenant=ALL -->

## Section 1: Symptoms
5xx error rate above 2% sustained for 5 minutes, elevated p99 latency, alerts from
APM on exception rate, health check failures on one or more pods.

## Section 2: Diagnosis
Correlate with deployment events - 70% of error spikes follow a release within 30
minutes. Check dependent service health (database, cache, downstream APIs). Review
exception distribution: a single dominant exception type indicates a code defect;
mixed timeouts indicate infrastructure saturation. Check CPU/memory/GC pressure.

## Section 3: Remediation
If caused by a recent deployment: roll back to the previous stable version
immediately. If dependency failure: enable circuit breaker / fallback responses.
If resource saturation: scale out replicas and restart pods with high memory.
Verify recovery via error rate returning below 0.5% for 10 minutes.

## Section 4: Escalation
SEV1 (customer-facing outage) requires incident commander assignment and
stakeholder notification within 15 minutes. Page the on-call engineer if
rollback does not restore service within 10 minutes.
