# Add source intake: URLs, clipboard and Android handoff

> **Implementation and acceptance (2026-11-05 UTC):** #1295–#1299 delivered foreground clipboard suggestion/opt-out/Paste and bounded, token-redacted HTTPS metainfo retrieval. Merged [#1301](https://github.com/gamesbyian/Chunks/pull/1301) qualifies the installed production **fetch → phone and real-rTorrent Add → Start → actual received/verified pieces** journey on immutable `f07a8331f81f`, including exact destination, private-address refusal, cancel/recreation recovery and unchanged saved default. [QUALITY-LEDGER](QUALITY-LEDGER.md) records exact-source receipts and limits: bounded piece outcomes, not full publisher payload completion. Physical Pixel clipboard/Share, third-party IME and external authenticated/provider cases remain unverified. This status does not relax the normative contract below.




**Status:** Owner-approved contract; implementation and automated acceptance are recorded above and in [QUALITY-LEDGER](QUALITY-LEDGER.md). **Scope:** phone-only, remote-only and Combined Add. This is the single detailed source-recognition contract; [PRODUCT](PRODUCT.md), [UX](UX.md), [SECURITY](SECURITY.md) and [WORK-QUEUE](WORK-QUEUE.md) cross-reference it. Preserve accepted A–E evidence; do not start another P0 campaign for unchanged behaviour.



## Universal Add presentation and entry-point contract

**Source is first, regardless of target.** Every + Add, Share, Open With or clipboard entry opens the one Universal Add form with torrent-source text entry and **Open .torrent / Paste** controls at the top; do not first show **On this device / On the connected server** routing buttons or fork into separate target-specific Add screens. Under source, select the exact phone or configured remote profile (and eligible Smart Routing where available), then effective **Save to** with Change, **Start when added**, and relevant collapsible source-handling, file/priority, artwork and rule choices. Selecting or changing the environment is a draft edit, never authorization to Add, and never clears a valid source. One explicit **Add transfer** action dispatches through the existing shared coordinator. Preserve source precedence and transport constraints below. Detailed layout and conditional controls: [UX](UX.md), [SMART-ROUTING-AND-SOURCE-LIFECYCLE](SMART-ROUTING-AND-SOURCE-LIFECYCLE.md).

## Ordinary journey and priority

The single existing **Add torrent** sheet accepts: (1) a user-selected local `.torrent` URI from Android's document provider, (2) a typed/pasted HTTP(S) URL serving valid `.torrent` metainfo, including URLs without a `.torrent` suffix, and (3) a valid `magnet:` URI. Opening/receiving a shared item through Android `ACTION_VIEW`, `ACTION_SEND`, or a suitable document/content URI enters the same Add draft, with no compulsory intermediate Review Add. Preserve source text, selected target environment, effective Save to destination, optional file priorities and Start when added. A local file path typed as text must be treated as a provider URI only where actually supported and authorized; do not infer filesystem permission from an absolute path.

**Clipboard prefill (on by default, opt-out in Add/Options):** On opening or returning focus to the Add screen, when the source input is empty and no already-inbound Android item/draft exists, inspect the clipboard once in the foreground. If it contains a recognizable magnet URI or plausible HTTP(S) torrent link, prepopulate the source field and identify the detected source type. Do not prefetch, start or add from clipboard alone. Never overwrite user-entered source, resumed draft, or Android Share/Open With payload; never monitor clipboard in background or repeatedly poll. A manual **Paste** affordance remains available even when automatic prefill is off. Clipboard access on modern Android may produce a system privacy notice; handle Android version restrictions and unsupported/empty/multiple clipboard items without intrusive prompts. A pasted URL need not have a `.torrent` filename extension; actual torrent validity is decided after explicit user action.

**Source precedence:** explicit current user edit > inbound Android Share/Open With item > restored draft > eligible clipboard suggestion > empty field. Accept a newer explicit share into an occupied draft only with a visible non-destructive choice to replace/keep draft. Never silently switch selected phone/remote profile because a URL or magnet arrived.

## Retrieval, security and errors

For an HTTP(S) source, perform a bounded fetch **only after explicit user intent to Add/resolve**. Follow a limited number of redirects with consistent transport and trust checks. Permit ordinary tracker query strings, including signed tokens/passkeys, but redact them from diagnostics, display summaries, crash reports, analytics and screenshots; never send them to an enrichment service. Use sensible connect/read timeouts, response and decoded-body size caps (align with existing 2 MiB metainfo cap unless the actual import contract intentionally changes), and validate bencode/metainfo and supported torrent versions before invoking the existing typed Add pipeline. Do not rely on MIME, URL suffix or Content-Disposition alone. Never execute received content.

Reject unsafe schemes, loopback/link-local/private-network destinations and redirects by default for fetched external URLs to prevent server-side-request-forgery-like access through the client. If local-network URL fetching is intentionally supported later, require a distinct reviewed trust policy; an explicit local file/content URI is a different permission mechanism. Reject HTTPS downgrade, invalid TLS or certificate bypass. Handle login pages, HTTP 401/403, expired links, HTML, redirects, malformed/oversized bodies, offline and timeouts with specific actionable errors. Do not reuse browser cookies or invent tracker authentication; an authenticated tracker URL may work via its token, otherwise user must obtain a usable authorized link. Avoid blind mutation retry after any uncertain target Add.

For remote targets, download and validate the `.torrent` on the Android device and pass verified metainfo to the existing authenticated exact-profile rTorrent Add adapter. Do not require rTorrent/seedbox to independently reach the source URL and do not expose private tracker URLs to remote URL-fetch APIs unnecessarily. Preserve exact profile, destination and explicit Start choice.

## Acceptance and implementation boundaries

Treat this as one cohesive **Add source-intake UX feature** under existing #8 (UI/Android handoff) with #1/#5 adapter touchpoints as needed, not a duplicate release gate. It can proceed autonomously with JVM/unit URL-classifier and security tests, Compose draft/focus/precedence tests, Android API29/API35 intent/provider and foreground clipboard instrumentation, bounded HTTP fixtures (extensions absent, redirects, HTML/401/403/oversize/TLS/error), and actual Add through both installed phone and authorized real rTorrent paths using licensed public metainfo. Preserve [REAL-TRANSFER-ACCEPTANCE](REAL-TRANSFER-ACCEPTANCE.md): fake fixtures prove validation/errors, not peer bytes or successful end-to-end transfers. Physical Pixel clipboard privacy prompt, OEM resolver behavior and human usability are later owner/device evidence, never silently claimed from emulator proof.

Acceptance checklist:
- One Add screen for magnet, local content URI, HTTP(S) .torrent URL, Android Share and Open With; no mandatory second review.
- Empty Add source prefills once when foregrounded with qualifying clipboard content; no prefill for unrelated text; existing input/draft/intent is preserved; opt-out and explicit Paste both work.
- Redirect and extensionless authenticated-token URLs succeed where authorized and metainfo is valid; signed query parameters never enter logs.
- Invalid/oversized/HTML/unsafe/unauthorized/failed URL retrieval yields accurate recoverable feedback and no accidental target mutation.
- Phone and chosen rTorrent profile receive equivalent validated torrent metadata with scoped destination, file selection and Start when added semantics; actual transfer outcome is observed.
- API-specific Android URI grants, intent lifecycle/onNewIntent/process restoration, 48dp controls, keyboard/TalkBack and system clipboard behavior receive appropriate installed coverage.

**Scheduling:** after established P0 A–E automated acceptance, prioritize as a high-value autonomous Add/UX slice alongside existing Add presentation, without blocking the owner's already pending physical daily-use review of the earlier APK. Change/requalify only affected acceptance paths.
