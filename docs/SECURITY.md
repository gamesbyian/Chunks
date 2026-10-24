# Chunks security and data handling
The Chunks repository is PUBLIC. Do not commit URLs/hostnames of private servers, credentials, passkeys, torrent inventory or private metadata fixtures. Do not post live RPC dumps to issues or CI logs.

RPC endpoints can execute commands with rTorrent user's privileges. Standard direct mode is allowlisted read/write methods, never a generic execution console. Require certificate-verified HTTPS for remote hosts. Loopback HTTP may be used only for controlled local developer fixtures, never silently for an arbitrary network address. Never turn off TLS verification to make a connection succeed. Avoid following cross-host auth redirects or replaying Basic credentials to redirects.

Companion must use server-side root ACL, token/identity authorization, canonical file descriptor/path validation, symlink-safe traversal, per-torrent operation leases, bounded uploads and journals. Never expose raw arbitrary shell execution. Review XML parser XXE/entity expansion, decompression bombs, multi-value response limits and untrusted filenames.

Credential persistence must be backed by Android Keystore / encrypted-at-rest solution with documented threat model; before that is proven, keep credentials memory-only and mark that limitation. Don't auto-collect usage telemetry. All optional cloud metadata lookups must be explicit, redact private trackers and have bounded purpose.

Network error ≠ failed mutation. After an uncertain response, query server operation journal before retrying. Source seeding data is never deleted until separately verified.