# Database Connection Pool Exhaustion (RB-DB-001)

<!-- meta: version=v1; effectiveFrom=2020-01-01; effectiveTo=2999-12-31; jurisdiction=GLOBAL; product=ALL; tenant=ALL -->

## Section 1: Symptoms
API latency spikes, HTTP 500s with "connection timeout" or "pool exhausted"
errors, HikariCP metrics showing active connections at max pool size, threads
blocked waiting on getConnection().

## Section 2: Diagnosis
Check pool metrics: active vs max connections. Identify long-running queries via
pg_stat_activity or APM slow-query traces. Look for connection leaks: connections
open longer than 5 minutes without query activity indicate missing close() in code.
Check for recent deployments that changed data-access patterns.

## Section 3: Remediation
Immediate: kill idle-in-transaction sessions older than 10 minutes. Raise max pool
size temporarily (max 2x baseline). Short-term: add connection timeout and leak
detection threshold in HikariCP config. Long-term: fix leaking code path, add
query timeouts, review N+1 patterns.

## Section 4: Escalation
If pool exhaustion recurs within 1 hour after remediation, escalate to DBA on-call
and consider failing over to the read replica for read-heavy endpoints.
