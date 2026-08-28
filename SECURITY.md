# Security policy

## Supported versions

Until a later maintenance policy is announced, security fixes are provided for the newest public
ComicViewer release.

| Version | Supported |
|---|---|
| 1.0.x | Yes |
| Development builds and older versions | No |

## Reporting a vulnerability

Please report vulnerabilities through
[GitHub private vulnerability reporting](../../security/advisories/new). Do not disclose an
unfixed vulnerability in a public issue, discussion, pull request, or social-media post.

Include, where possible:

- the affected ComicViewer version and Android version;
- the comic format and a minimal reproduction that contains no private content;
- the impact and steps needed to reproduce it;
- whether the issue is already public or being actively exploited.

Do not send signing keys, credentials, copyrighted comics, or personal documents.

The maintainer will acknowledge a usable report as availability permits, validate the issue,
coordinate a fix, and publish an advisory when disclosure is appropriate. No bounty program or
guaranteed response deadline is currently offered.

The application's hostile-input and build-security boundaries are documented in
[docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md).
