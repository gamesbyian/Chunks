# Chunks interaction journeys and visual acceptance
Status: proposed experience; no owner-approved mockups or handset captures yet.

## Design language
Vibrant, modern and lightly irreverent, not childish. Big buttons with restrained density options; colour supports information but never carries it alone. Prefer plain labels: Transfers / Organize / Servers / Settings; Places as folder destinations, Collections as semantic groups. Expert inspector is progressive disclosure, not a second hidden app.

## Journey A: Add
User shares a magnet or .torrent → choose Local or a named connected rTorrent server → choose suggested, recent, favorite or browsed Place → optionally create new directory if actually supported → files/priorities → Add. Default Place must be visible, never hidden in Settings. Error states explain why a server or storage provider cannot grant a location.

## Journey B: Move
Select transfer → Move → source/destination, relative path, same-volume vs copy, estimated risk, collision and remaining space shown → preview → confirm → progress and cancellation semantics → verified completion. On disconnect, show Recovering/Unknown rather than success or a destructive retry.

## Journey C: Rename
Rename the engine-managed file when supported, or create a separately named library copy while preserving seed source. Warn before data-affecting operations; never imply a display alias is a real rename.

## Journey D: Organize
Show detected grouping (TV, franchise, series, films, comics, ebooks, music), confidence and provenance. User can Accept, Apply to selected, Teach Chunks, Ignore. Categories, Collections and concrete storage paths are independently editable. No silent file action just because category confidence is high.

## Accessibility
48dp minimum primary hit targets, one-hand paths, large fonts, TalkBack reading order and action descriptions, keyboard focus, non-gesture alternatives, reduced motion, landscape/tablet adaptation. Actual Android observation required; mockups are drafts.