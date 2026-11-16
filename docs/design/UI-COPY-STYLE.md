# UI copy: concise, consistent, friendly

This is working guidance for user-visible Chunks copy. Apply it when wording genuinely improves; **leave strong text alone**. The purpose is clarity, not a blanket rewrite or changes to app behavior.

## Voice and terminology

- Lead with the outcome or the useful next step. Name the client when the distinction matters.
- Use short, natural sentences and contractions when they improve readability. Be calm, matter-of-fact, and helpful when something goes wrong.
- Favor familiar words: **transfer**, **folder**, **download**, **on this device**, **server**, **files**, **connection**.
- Prefer concrete explanations to internal implementation language such as *RPC, exact hash, readback, journal, method discovery, capability, preflight, directory services*. Keep those details in advanced diagnostics when they are useful.
- Avoid explaining five internal safeguards when one clear, truthful statement about the outcome and a next step will do.
- Don't repeat “nothing changed” unnecessarily, but keep it when the distinction matters.
- Use client-neutral language for equivalent Local and remote outcomes. A difference in what has actually been verified can still require different wording.
- Keep already-useful control names and short labels stable. This is a prose style guide, not a demand to rename every setting.

## Safety and accuracy

- Never call a request successful unless its outcome was confirmed. “The server accepted the request” does not mean files were deleted.
- If a command may have applied, say **“Chunks couldn't confirm…”**, explain what to check, and make clear **Chunks won't retry automatically**.
- Never casually invite a manual retry after uncertain Add, Start, Stop, Remove, folder creation, file selection, or deletion. The person must first check current state.
- Distinguish session removal from actual data deletion, and confirmed non-deletion from unverified deletion.
- Preserve essential consequences concerning irreversible actions, credentials, tracker passkeys, privacy, filesystem permissions, seeding, storage, and location. Some warnings should remain longer.
- UI wording cannot change operation gates, authorization boundaries, command behavior, or persistence guarantees.

## Examples

| Before | Prefer |
| --- | --- |
| “Both directory services report the folder absent. Chunks will not retry creation.” | “The folder wasn't found. Chunks won't try to create it again.” |
| “Local volume capacity unavailable. No space estimate is shown.” | “Couldn't check this device's storage capacity.” |
| “Chunks could not save its change journal, so no priority was changed.” | “Couldn't save the pending change. File priorities weren't changed.” |
| “Queued: ruTorrent accepted nonforce data cleanup. physical data deletion is NOT yet verified.” | “The server accepted the file-deletion request, but Chunks hasn't confirmed the files are gone.” |

## Development checklist

1. Inspect the actual Compose string and any concatenated fragments before editing.
2. Confirm the shorter wording preserves the precise observed outcome, state uncertainty, client and file effects, and safe next step.
3. Update tests that assert superseded wording. Prefer testing safety meaning over freezing an entire sentence when possible.
4. Check narrow layouts, large text, and TalkBack where longer descriptions or state announcements changed.
5. Keep the change scoped. Do not turn a copy edit into a new UX flow or a broad refactor.
