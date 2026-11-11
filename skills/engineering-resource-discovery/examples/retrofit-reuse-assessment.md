# Teaching case: Retrofit reference-photo composition resource audit

**Hypothetical; no vendor or GitHub package was inspected.** The candidate categories below are search surfaces, not recommendations or verified implementations.

Desired outcome: a user marks a boiler in a mechanical-room photograph, selects catalog reference views, and explicitly reviews a composition packet sent through AYA.

**Decompose:** photo import and persistent URI; marker/canvas editor; equipment metadata; reference-image cache and license; prompt/attachment packet; AYA adapter; receiving-assistant availability; optional local masking; externally generated image import and comparison. Each has its own input/output and risk.

**Candidates and knockouts:** Android Photo Picker/SAF first-party primitives; offline editable annotation libraries; imaging tools for transforms and masking; authorized vendor images; existing AYA adapter; optional segmentation models; manual markup baseline. Cloud segmentation with unconsented uploads fails the privacy boundary. An SDK supporting only source-level demos is not installed-device evidence.

**Spikes:** (A) large rotation-corrected image with 3 repositionable markers surviving app process death and accessibility; (B) handoff multiple exact-grant URIs with one explicit recipient review, tested with actual receiver; (C) compare local mask composition with an external reference under a realistic occlusion fixture and untouched-pixel check. Each is separately tiered. A spike that succeeds in annotation does not prove generated photorealistic geometry.

**Disposition:** absent inspected evidence, all are INVESTIGATE. The appropriate first work is source/candidate due diligence and cheap probes, not declaring that a bespoke image engine is required.
