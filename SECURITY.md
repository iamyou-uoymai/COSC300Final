# Security Policy

## Supported Versions
Until tagged releases exist, `main` is the only supported branch.

## Reporting a Vulnerability
Please **do not** open a public issue for sensitive vulnerabilities.

<!-- Update the contact address below if your security contact changes -->
Instead, email: `security@example.com` including:
- Affected component / path
- Impact & exploit scenario
- Reproduction steps or PoC
- Suggested remediation (if any)
- (Optional) Desired disclosure timeline / embargo

We aim to acknowledge within 72 hours and provide an initial assessment within 7 days. Remediation timeline will depend on severity and complexity; critical issues will be prioritized.

## Scope
- Code execution / injection
- Sensitive data exposure
- Authentication / authorization flaws (future when auth is added)
- Supply chain / dependency risk

## Out of Scope (unless high-impact chaining)
- DoS via excessive well-formed requests
- Theoretical issues with no realistic exploit path

## Coordinated Disclosure
We appreciate responsible disclosure. We'll credit reporters in release notes unless anonymity requested. If you prefer encryption, provide a PGP key in your initial email and we will respond with an encrypted channel.
