# Teaching case: compile one Retrofit workflow

**Hypothetical; not a project authority or a deployed capability.** This example teaches trace construction.

**Owner goal:** user loads a mechanical-room photograph, marks a target, chooses a reference model, writes a proposed modification and explicitly sends a reviewed set of attachments through AYA.

**Atomic outcomes:** REQ-PHOTO import exact original; REQ-MARK edit and persist marker on selected photo revision; REQ-REF bind the intended equipment model and reference angle; REQ-PROMPT include instructions and protected elements; REQ-SHARE review destination/attachments before external grant; REQ-RESULT receive independently generated concept visualization without claiming certified geometry.

**States for REQ-SHARE:** DRAFT (editable, no external side effect); REVIEW (exact recipient and URI inventory); AUTHORIZED (explicit user decision); ATTEMPTED (system launch/receiver outcome uncertain); CONFIRMED (observable acceptance if receiver exposes it); CANCELLED (draft retained). A share-sheet launch alone is not confirmed receipt.

**Variant axes:** photo URI expires vs persists, single vs multiple attachments, model missing from catalog, offline while editing, rotate/crop after marker placement, background/reopen, user rejects share, accessible keyboard-only selection.

**Adapter contract candidate:** immutable packet ID, exact photo version, selected reference IDs, MIME types, content URIs and grants, deliberate recipient review, no background upload. Do not assume an AYA package exists on a device simply because its repository has an adapter proposal.

**Acceptance:** REAL photo picker returns a URI; edited annotations survive recreation; before sending, attachment list and recipient are reviewed; refusing external share retains draft and never invokes receiver; actual receiver test distinguishes launched intent from accepted content. Each is separately tiered.

**Readiness:** local annotation can be READY with source and device proof while photorealistic preserved-room composition is RESEARCH NEXT. No inferred catalog of 400 models is silently promoted to an existing dataset.
