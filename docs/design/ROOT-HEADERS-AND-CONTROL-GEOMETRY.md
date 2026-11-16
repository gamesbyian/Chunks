# Chunks: compact root headers and control geometry (owner approved, 2026-11-15)

**Binding visual/interaction design.** Applies to persistent four-root footer, the root Transfers and Options screens and compact icon actions. This specification supersedes screenshots or prose requiring a large redundant **Transfers** or **Options** heading below the wordmark or circular Filters/Sort and Batch controls. See [UX](../UX.md), [compact Transfers](TRANSFERS-COMPACT-LIST-FIRST.md), [Options cards](OPTIONS-SOLID-COLOUR-INDEX.md), [Options information architecture](../OPTIONS-INFORMATION-ARCHITECTURE.md) and [WORK-QUEUE](../WORK-QUEUE.md).

## Headings and information density

- At the root **Transfers** screen, show the **chunks.** wordmark followed directly by the compact environment controls, Search/filter/sort/batch toolbar and the live summary above transfer rows. **Remove the separate Transfers heading.** The selected fixed footer already names the root destination.
- At the root **Options** screen, show **chunks.** followed directly by the Search options field and the eight approved solid-colour category cards. **Remove the separate Options heading** and the excessive space it leaves. Retain the existing filled-card taxonomy and real category headings.
- Preserve meaningful in-screen section titles, connection names, information and nested-screen labels needed for orientation. This rule is for *redundant root names*, not a universal ban on headings. Ensure TalkBack still exposes an accessible root screen name and that screen changes are announced semantically without creating a large visual title.
- Respect system insets, scalable typography, keyboard/IME and sticky footer. Do not introduce extra top empty space when removing a heading.

## Four-root navigation geometry

- Persistent footer buttons **Transfers / + Add / Connections / Options** use the **same rounded-rectangle silhouette** in all states. Match the geometry of the existing more rectangular **selected highlights**, including dimensions, corner radius and outer bounds, on both selected and unselected tabs. This means no pill outline around a rectangular active highlight and no shape/size jump when changing tabs.
- The selected state may change outline thickness/colour, fill and emphasis. The Add destination retains its recognisable purple fill but shares the same fundamental rounded-rectangle geometry. Footer labels, icons, accessibility selection semantics, focus/pressed states and usable minimum touch sizes remain intact.
- Rounded rectangles are the default for navigation and full text actions. **Rounded squares** are used for small icon-only toolbar buttons. Reserve full pill silhouettes for filter/status chips or controls where the capsule shape has an intentional distinct purpose.
- Do not infer selected state from colour only: outline/emphasis and accessible selected semantics continue to distinguish it.

## Transfers toolbar

- The two adjacent icon-only **Filters/Sort** and **Batch tools** controls become **rounded squares** instead of circles, with the same width, height, corner radius and stroke vocabulary; align with the flexible Search field. Preserve existing disclosure logic, icon identities, accessible names, active-filter indicators, focus and minimum **48dp hit targets**.
- Keep the search + buttons on one compact row at normal Pixel 6 Pro font size. At 320dp / 200% fonts, adapt safely rather than clipping icons or reducing targets; retain usability for both controls and editable search.
- Maintain all existing filter, sort, bulk operations and authorization/safety semantics. This is a geometric/presentation change, not a new interaction route or button functionality.

## Acceptance

Physical Pixel 6 Pro and 320/412dp captures must show no duplicate **Transfers**/**Options** root heading, no extra blank area, exact selected/unselected footer shape match and two visibly rounded-square toolbar controls. Test all four footer selection states including purple Add, state/focus/TalkBack, large text, long labels, 0/1/many transfer results, active sorting/filters/batch and scrolled screens. Verify the selected visual ring fits the button's actual bounds. Record visual and functional acceptance separately. These documentation changes **do not assert implementation or APK acceptance**.
