#!/usr/bin/env python3
"""Create outline-only alternatives for legacy font-dependent SVG drafts.

Original ten SVGs remain untouched. Outlined glyph geometry is generated
from the licensed bundled Chunks ExtraBold OTF; these are exploratory
alternatives, not exact tracings of the earlier fallback system fonts.
"""
from __future__ import annotations
import argparse,json,re,xml.etree.ElementTree as ET
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/"docs/design/svg"
FONT=ROOT/"assets/fonts/chunks/Chunks-ExtraBold.otf"
INV=BASE/"master-inventory.json"
GALLERY=BASE/"gallery.html"
NS="{http://www.w3.org/2000/svg}"
ET.register_namespace("", "http://www.w3.org/2000/svg")
LEGACY=[
 "brand/compact-horizontal-lockup","brand/dark-reversed-lockup",
 "brand/green-sticker-lockup","brand/monochrome-lockup",
 "brand/pink-sticker-lockup","brand/primary-lockup",
 "brand/purple-sticker-lockup","brand/stacked-lockup",
 "brand/yellow-sticker-lockup","files/sort-az"
]
def font_to_path(node,font):
    text="".join(node.itertext())
    if not text.strip():raise ValueError("Empty SVG text")
    face=font.getGlyphSet();cmap=font.getBestCmap();metrics=font["hmtx"].metrics
    upm=font["head"].unitsPerEm
    size=float(node.get("font-size","24"))
    space=float(node.get("letter-spacing","0"))
    pen_parts=[];advance=0.0
    for char in text:
        name=cmap.get(ord(char))
        if name is None:raise ValueError("Font glyph missing: "+repr(char))
        pen=SVGPathPen(face)
        face[name].draw(pen)
        d=pen.getCommands()
        if d:
            pen_parts.append((advance,d))
        advance+=metrics[name][0]+space*upm/size
    width=advance*size/upm
    x=float(node.get("x","0"));y=float(node.get("y","0"))
    anchor=node.get("text-anchor","start")
    if anchor=="middle":x-=width/2
    elif anchor=="end":x-=width
    g=ET.Element(NS+"g")
    # Preserve fill and opacity. Glyph outlines must not inherit decorative
    # outer strokes from the icon's parent <g>.
    g.set("fill",node.get("fill","#0B0F17"))
    g.set("stroke","none")
    g.set("transform",f"translate({x:.6f} {y:.6f}) scale({size/upm:.9f} {-size/upm:.9f})")
    for offset,d in pen_parts:
        elem=ET.SubElement(g,NS+"path")
        elem.set("d",d)
        elem.set("transform",f"translate({offset:.6f} 0)")
    return g
def build():
    font=TTFont(FONT)
    inv=json.loads(INV.read_text(encoding="utf8"))
    by_id={a["id"]:a for a in inv["assets"]}
    gallery=GALLERY.read_text(encoding="utf8")
    m=re.search(r'(<script type="application/json" id="svg-catalog">)(.*?)(</script>)',gallery,re.S)
    if not m:raise ValueError("Missing embedded gallery JSON")
    listings=json.loads(m.group(2));existing={x["id"] for x in listings}
    results={}
    for identifier in LEGACY:
        old=by_id[identifier]
        source=ROOT/old["path"]
        doc=ET.parse(source).getroot()
        converted=0
        for parent in doc.iter():
            for i,node in enumerate(list(parent)):
                if node.tag==NS+"text":
                    parent.remove(node);parent.insert(i,font_to_path(node,font));converted+=1
        if not converted:raise ValueError("No text elements in "+identifier)
        if any(e.tag==NS+"text" for e in doc.iter()):
            raise ValueError("Unconverted live font glyph in "+identifier)
        doc.attrib["aria-label"]=(doc.attrib.get("aria-label") or identifier)+" outlined vector alternative"
        path="docs/design/svg/outlined-alternatives/"+identifier+".svg"
        data=ET.tostring(doc,encoding="unicode",xml_declaration=False)+"\n"
        entry={
          "id":"outlined-alternative/"+identifier,
          "category":"font-outline-alternative",
          "label":identifier+" Chunks font path-only variant",
          "status":"vector-draft","path":path,
          "source":"Derivative of "+old["path"]+"; replaces live text with existing Chunks ExtraBold glyph paths",
          "fidelity":"font-outline-interpretation",
          "sourceVariant":identifier,
        }
        results[identifier]=(entry,data)
        if entry["id"] not in by_id:inv["assets"].append(entry)
        if entry["id"] not in existing:
            listings.append({"id":entry["id"],"category":entry["category"],"label":entry["label"],
              "src":path.removeprefix("docs/design/svg/"),"source":entry["source"]})
    inv["assets"].sort(key=lambda a:a["id"])
    inv["totals"]["vectorDraft"]=825
    listings.sort(key=lambda x:x["id"])
    encoded=json.dumps(listings,separators=(",",":"),ensure_ascii=False).replace("<","\\u003c").replace(">","\\u003e").replace("&","\\u0026")
    new_gallery=gallery[:m.start(2)]+encoded+gallery[m.end(2):]
    new_gallery=re.sub(r'<strong>\d+ draft SVGs</strong>','<strong>825 draft SVGs</strong>',new_gallery)
    return results,json.dumps(inv,indent=2,ensure_ascii=False)+"\n",new_gallery
def main():
    a=argparse.ArgumentParser();a.add_argument("--write",action="store_true");args=a.parse_args()
    results,inventory,gallery=build()
    targets=[(ROOT/row["path"],content) for row,content in results.values()]
    targets.extend([(INV,inventory),(GALLERY,gallery)])
    for p,content in targets:
        if args.write:
            p.parent.mkdir(parents=True,exist_ok=True)
            p.write_text(content,encoding="utf8")
        elif not p.is_file() or p.read_text(encoding="utf8")!=content:
            raise RuntimeError("Stale outlined SVG or index: "+str(p))
    print("PASS: 10 stable path-only alternates; original font-dependent designs unchanged")
if __name__=="__main__":main()
