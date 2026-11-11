# Research and existing AI skills to consult, without blind installs

## Research foundations

| Work | Direct operational lesson |
|---|---|
| Novak and Cañas, IHMC, *The Theory Underlying Concept Maps* (2006/2008): https://cmap.ihmc.us/docs/theory-of-concept-maps.php | Focus question, concept parking lot, meaningful linking-word propositions, revisions and cross-links. A dense map without informative relations is not understanding |
| Cañas et al., *Mining the Web to Suggest Concepts during Concept Mapping* (2002): https://www.ihmc.us/users/acanas/Publications/ConceptSuggester/From%20Word%20--Concept%20Suggester%20SBIE%202002.htm | Suggest new concepts from partial-map structure and web retrieval; suggestion is not automatic acceptance. Precisely the “expand map before finding genuinely new blanks” problem |
| Noy and McGuinness, Stanford, *Ontology Development 101*: https://protege.stanford.edu/publications/ontology_development/ontology101-noy-mcguinness.html | Explicit domain/scope, reuse ontologies, terminology, classes/properties/instances, competency questions, iterative refinement |
| Ganter, *Attribute Exploration with Background Knowledge* (1999): https://www.sciencedirect.com/science/article/pii/S0304397598002710 | Probe proposed implications with counterexamples; don't assume observed regularity is a valid general rule |
| Holzer et al., *Knowledge Acquisition under Incomplete Knowledge* (2004): https://journals.sagepub.com/doi/10.3233/FUN-2004-63102 | Three-valued true/false/unknown contexts and interactive exploration; preserve unknown separately |
| Max Planck Institute, *Completeness, Recall, and Negation in Open-World Knowledge Bases*: https://www.mpi-inf.mpg.de/iswc-2021-tutorial | Unstated is not false; use scoped completeness assertions and relative recall, not universal claims |
| W3C SHACL recommendation: https://www.w3.org/TR/shacl/ | Machine-check constraints on graph data. A schema validation pass cannot prove real-world completeness |
| Jackson, Barrella and Bodnar, *Concept Maps in Engineering Education* systematic review: https://onlinelibrary.wiley.com/doi/full/10.1002/jee.20548 | Compare maps with expert references and held-out propositions, not merely node counts |
| CQ4OE benchmark: https://oeg-upm.github.io/cq4oe-benchmark/ | Evaluate ontology construction against competency questions and independently annotated reference ontologies |

## Existing agent skills with reusable ideas

- [douinc/graph-engineering](https://github.com/douinc/agent-skills/blob/main/skills/graph-engineering/SKILL.md): decision-scoped, evidence-backed dependency graph; observed/inferred/proposed/unknown. Strong for code architecture and durable traceability.
- [Mokee04/ontology-agent-suite](https://github.com/Mokee04/ontology_research/blob/main/ontology-agent-suite/SKILL.md): staged ontology planning, questions, research, construction and validation, with human gates. Adapt to user's preference for independent research first; do not impose its blanket pause/checkpoint routine on a development pipeline.
- [sensein/StructSense](https://github.com/sensein/agent_skills/blob/main/skills/structsense/SKILL.md): entity extraction, ontology alignment, provenance, JSON/RDF validation and competency-question checks. Scientific-domain assumptions and dependencies must be reviewed before copying.
- [preetamnath/find-gaps](https://www.skills.sh/preetamnath/agent-skills/find-gaps): multiple orthogonal blind-spot lenses; explicitly audits unstated assumptions. Good for independent passes, but a finding still needs corpus/novelty disconfirmation.
- [CQ4OE](https://oeg-upm.github.io/cq4oe-benchmark/): evaluation resource, not an agent skill; useful for held-out competency-question method.

## Implementations rather than SKILL.md instructions

- [IHMC CmapTools](https://cmap.ihmc.us/) maps propositions and has research on map-aware concept suggestions.
- [Microsoft GraphRAG](https://github.com/microsoft/graphrag) extracts and clusters entities/relations from text. Its upstream README currently describes maintenance mode and indexing costs; it does not certify model completeness.
- [Graphiti](https://github.com/getzep/graphiti) maintains provenance-backed temporal relationships and evolving ontologies. Useful when claims change over time; a graph database is not required for an initial skill.
- [W3C RDF/SHACL](https://www.w3.org/TR/shacl/) and [Protégé](https://protege.stanford.edu/) become useful when interoperability or formal reasoning is actually needed.

None of these automatically installs into the target agent runtime. Inspect licenses, scripts, revision pins and required external services before adoption; this skill only links, does not vendor outside code.
