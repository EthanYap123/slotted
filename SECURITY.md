# Security policy

Slotted is a portfolio project, but security reports are welcome and taken seriously.

## Reporting a vulnerability

Please report it privately through GitHub: open the repository's **Security** tab and choose **Report a vulnerability**. Do not open a public issue.

Include what you found, how to reproduce it, and which environment (staging or production) you used. You can expect a reply within a week.

## Scope

- The API, the web app and the deployment configuration in this repository
- The public demo deployments

Please don't run automated scanners or load tests against the demo: it runs on free tiers with small limits. Use a local copy instead (see the README).

## Secrets

No secrets belong in this repository. CI runs gitleaks on every pull request. If you notice a secret in the history, report it as above so it can be rotated.
