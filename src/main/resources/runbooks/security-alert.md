# Security Alert Response (RB-SEC-003)

<!-- meta: version=v1; effectiveFrom=2020-01-01; effectiveTo=2999-12-31; jurisdiction=GLOBAL; product=ALL; tenant=ALL -->

## Section 1: Symptoms
IDS/IPS alerts, unusual authentication failure bursts, impossible-travel logins,
egress traffic to unknown IPs, file-integrity monitoring alerts, or reports of
phishing targeting staff credentials.

## Section 2: Diagnosis
Isolate scope: which accounts, hosts, and services are affected. Preserve evidence
- snapshot logs and memory before any remediation. Determine initial vector:
credentials, vulnerability exploit, or insider. Check for lateral movement
indicators in authentication logs.

## Section 3: Remediation
Containment first: disable compromised credentials, isolate affected hosts from
the network, block malicious IPs at the WAF. Never power off suspected hosts -
forensic evidence must be preserved. Rotate exposed secrets. Apply emergency
patches only after evidence capture.

## Section 4: Escalation
All suspected breaches escalate immediately to the security incident response
team and CISO. Data exfiltration indicators trigger legal/compliance notification
procedures per breach-response policy. Never attempt attribution publicly.
