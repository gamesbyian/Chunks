# Independent quality / release evidence ledger
Status: pre-alpha. Green code tests, real rTorrent behavior, safe mutation recovery, Android handset UX and release acceptance are distinct evidence classes.

| Gate | Evidence | Status |
|---|---|---|
| Source project scaffold | PR and exact commit SHA required | Pending |
| Android Gradle compile | GitHub Actions result, artifact and SHA required | Not tested |
| JVM model/RPC fixtures | Actual test report required | Not tested |
| Read-only remote listing | Real server version, protected connection and sanitized response evidence | Not tested |
| XML/JSON-RPC compatibility | Two server versions and failure matrix | Not tested |
| Actual remote relocation | Disposable multi-volume tests, no loss, reconnection | Not implemented |
| Rename while seeding | Data verified with engine hash/check, unchanged source protected | Not implemented |
| Intelligent sorting | Deterministic examples, confidence/rule acceptance, no silent mutations | Not implemented |
| Local BitTorrent | Real transfer, seed and Android lifecycle acceptance | Not implemented |
| Android usability | Physical phone, TalkBack/large text/gesture alternatives | Not observed |
| Release | Signed APK, privacy/security, license, reproducibility | Blocked |

The earliest development slice will only prove a protected read-only rTorrent probe and listing; nothing in this ledger may be updated to “passed” from fixture-only evidence alone.