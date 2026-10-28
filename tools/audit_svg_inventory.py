#!/usr/bin/env python3
"""Conservative, reproducible Chunks SVG inventory/QA report."""
import argparse
import collections
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/"docs/design/svg"
INV=BASE/"master-inventory.json"
OUTPUT=BASE/"quality-dossier.json"
REPORT=BASE/"QUALITY-REVIEW.md"
def produce():
    inv=json.loads(INV.read_text(encoding="utf-8"))
    ids={a["id"]:a for a in inv["assets"]}
    assert len(ids)==len(inv["assets"])==sum(inv["totals"].values())
    rows=[]
    actual=set()
    for a in sorted(inv["assets"],key=lambda x:x["id"]):
        state=a["status"]
        entry={"id":a["id"],"category":a.get("category"),
               "inventoryStatus":state,"source":a.get("source"),
               "path":a.get("path"),"aliasOf":a.get("aliasOf")}
        if state=="asset-alias":
            target=ids.get(a.get("aliasOf"))
            assert target and target["status"] in ("vector-draft","approved-existing")
            assert not a.get("path")
            category="master-alias"
            notes=["References an existing standalone master"]
        elif state in ("vector-draft","approved-existing"):
            path=ROOT/a["path"]
            assert path.is_file() and path.suffix==".svg",str(path)
            raw=path.read_text(encoding="utf-8")
            assert "data:image/" not in raw and "<!DOCTYPE" not in raw.upper()
            doc=ET.fromstring(raw)
            assert doc.tag=="{http://www.w3.org/2000/svg}svg"
            assert len(doc.attrib.get("viewBox","").replace(","," ").split())==4
            tags=[node.tag.rsplit("}",1)[-1] for node in doc.iter()]
            assert not any(x in tags for x in ("script","image","foreignObject","iframe"))
            assert any(x in tags for x in ("rect","path","circle","ellipse","polygon","polyline","line","use"))
            entry["svgBytes"]=path.stat().st_size
            entry["liveSvgText"]="text" in tags or "textPath" in tags
            entry["usesFilter"]="filter" in tags
            entry["usesClipOrMask"]="clipPath" in tags or "mask" in tags
            entry["nestedSvg"]=tags.count("svg")>1
            notes=[]
            if entry["liveSvgText"]:notes.append("Font-dependent runtime text")
            if entry["usesFilter"]:notes.append("Filter compatibility needs optical review")
            if entry["usesClipOrMask"]:notes.append("Inspect cropped edges at small sizes")
            if state=="approved-existing":
                category="canonical-launcher"
            else:
                assert a["path"] not in actual
                actual.add(a["path"])
                fp=a["path"].lower()
                fidelity=a.get("fidelity","").lower()
                if "/approved-derived/layers/" in fp:category="canonical-geometry-derivative"
                elif "source-derived" in fidelity or "source-contour" in fidelity:category="raster-contour-draft"
                elif "font-outline" in fidelity or "otf-outlined" in fidelity:category="font-outline-interpretation"
                elif "recomposition" in fidelity:category="vector-recomposition"
                elif "/typography/" in fp:category="exploratory-letter-glyph"
                elif "/ui/" in fp:category="native-ui-design-specimen"
                elif "/palettes/" in fp:category="palette-experiment"
                else:category="illustrative-vector-draft"
        else:raise ValueError("Unfulfilled inventory entry "+a["id"])
        entry["provenanceClass"]=category
        entry["opticalApproval"]="approved" if state=="approved-existing" else "not-verified"
        entry["notes"]=notes
        rows.append(entry)
    present={str(p.relative_to(ROOT)) for p in BASE.rglob("*.svg")}
    assert actual==present,(len(actual),len(present),list(present-actual)[:10])
    assert len(actual)==inv["totals"]["vectorDraft"]
    counts=dict(sorted(collections.Counter(a["provenanceClass"] for a in rows).items()))
    unreviewed=sum(a["opticalApproval"]=="not-verified" for a in rows)
    text_dependent=[a["id"] for a in rows if a.get("liveSvgText")]
    gates=[
      "Review source overlays for image and lettering fidelity",
      "Inspect rendered artwork at 16, 24, 32, 48 and 64 px when applicable",
      "Assess light and dark contrast including colour vision and monochrome",
      "Review logo optical kerning, baselines and sticker letter counters",
      "Validate implemented Compose controls with dynamic text and accessibility",
      "Obtain Android device screenshot and 200 percent text acceptance where used"
    ]
    data={"schema":1,"purpose":"Technical inventory completeness, NOT final optical approval",
      "counts":inv["totals"],"assets":rows,"provenanceCounts":counts,
      "unverifiedOpticalEntries":unreviewed,"fontDependentSvgCount":len(text_dependent),
      "fontDependentIds":text_dependent,"openAcceptanceGates":gates,
      "canonicalLauncher":"docs/design/approved/chunks-app-icon-master.svg"}
    md=["# Chunks SVG inventory quality review","",
      "All "+str(inv["totals"]["vectorDraft"])+" first-pass SVGs exist and "+str(inv["totals"]["aliases"])+" aliases resolve. This is not a declaration that every logo, sticker, icon or UI specimen matches its source exactly.","",
      "## Recorded categories","",
      "| Provenance | Entries |","| --- | ---: |"]
    md.extend("| "+k+" | "+str(v)+" |" for k,v in counts.items())
    md.extend(["","## Remaining verification","",
      "Technical file coverage: "+str(len(actual))+"/"+str(inv["totals"]["vectorDraft"])+". Missing named first-pass slots: "+str(inv["totals"]["planned"])+".",
      "Unverified optical entries: "+str(unreviewed)+".",
      "Font-dependent SVGs: "+str(len(text_dependent))+".",
      ""])
    md.extend("- [ ] "+gate for gate in gates)
    md.extend(["","## Rules","",
      "Canonical launcher geometry is protected. Drafts, including source-contour traces, are not automatically approved.",
      "Outlined-font slogans are not necessarily faithful to the hand-drawn reference typography.",
      "SVG UI specimens do not replace accessible native Android controls.",
      "No reference PNGs or fonts are modified by this inventory audit.","",
      "The machine-readable per-asset dossier is in quality-dossier.json.",""])
    return json.dumps(data,indent=2,ensure_ascii=False)+"\n","\n".join(md)
def main():
    p=argparse.ArgumentParser();p.add_argument("--write",action="store_true")
    args=p.parse_args();a,b=produce()
    if args.write:
        OUTPUT.write_text(a,encoding="utf-8");REPORT.write_text(b,encoding="utf-8")
    else:
        assert OUTPUT.read_text(encoding="utf-8")==a,"Stale quality dossier"
        assert REPORT.read_text(encoding="utf-8")==b,"Stale quality summary"
    print("PASS: asset inventory, provenance and SVG files match the current manifest totals.")
if __name__=="__main__":main()
