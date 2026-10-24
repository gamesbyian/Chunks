# Independent quality / release evidence ledger
Status: pre-alpha. Green code tests, real rTorrent behavior, safe mutation recovery, Android handset UX and release acceptance are distinct evidence classes.

| Gate | Evidence | Status |
|---|---|---|
| Source project scaffold | [merged #11](https://github.com/gamesbyian/Chunks/pull/11), main commit `6dba63fed72c92c7f97128ef3fa08d278a37b730` | Established |
| Android Gradle compile | [run 40057523588](https://github.com/gamesbyian/Chunks/actions/runs/40057523588), tested SHA `201b53fc3d16482cf2aa1a08c9c01d9e0351174b`, debug artifact `chunks-debug-prealpha` | Passed for that SHA |
| JVM model/RPC fixtures | `:app:testDebugUnitTest` in run 40057523588; codec/classifier/relocation planner deterministic tests | Passed for that SHA; live RPC not proven |
| Read-only remote listing | Real server version, protected connection and sanitized response evidence | Not tested |
| XML/JSON-RPC compatibility | Two server versions and failure matrix | Not tested |
| Actual remote relocation | Disposable multi-volume tests, no loss, reconnection | Not implemented |
| Rename while seeding | Data verified with engine hash/check, unchanged source protected | Not implemented |
| Intelligent sorting | Deterministic examples, confidence/rule acceptance, no silent mutations | Not implemented |
| Local BitTorrent | Real transfer, seed and Android lifecycle acceptance | Not implemented |
| Android usability | Physical phone, TalkBack/large text/gesture alternatives | Not observed |
| Release | Signed APK, privacy/security, license, reproducibility | Blocked |

The first merged slice supports a read-only RPC probe and listing in source, but has **not** connected to a real rTorrent instance. This ledger records actual Gradle/JVM evidence only, with no claims about actual seedbox interoperability or device behavior. Subsequent commits require their own tests and evidence.