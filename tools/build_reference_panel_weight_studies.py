#!/usr/bin/env python3
"""Build reversible, NOT SHIPPING Chunks Reference Panel weight prototypes.

Starts with the actual owner reference's 62 independent SVGs. Uses contour-
aware offsets and per-glyph stroke protection to create 400/500/600/700 draft
outlines. Holds the source ExtraBold glyphs untouched. Each candidate is compiled
into CFF and optically remeasured against its changed final shapes.

This is a reproducible *interim design study*; offsets cannot replace manually
redrawn masters and are explicitly not installed in Android.
"""
from __future__ import annotations
import argparse, hashlib, json, tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.svgLib.path import parse_path
from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.pens.transformPen import TransformPen
from shapely.geometry import Polygon, MultiPolygon
from shapely.geometry.polygon import orient
from shapely.ops import unary_union
from build_chunks_fonts import optical_kerning, add_gpos_pairs
from spacing_optimizer import ink_gaps_at_size, measured_presets

EXPECTED='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
BASELINES={'uppercase-A-M':336, 'uppercase-N-Z':380, 'lowercase-a-m':431,
           'lowercase-n-z':473,'digits':524}
SCALE=19.2
WEIGHTS={400:'Regular',500:'Medium',600:'Semibold',700:'Bold'}
SAMPLES=['Chunks','TORRENTS, YOUR WAY.','Downloading 68%',
         '0123456789','Résumé télévision','Ärger Übergröße','Files (156)']


def source_polygons(svg:Path, min_area:float):
    root=ET.fromstring(svg.read_text())
    paths=[el.attrib['d'] for el in root.iter() if el.tag.endswith('path')]
    if len(paths)!=1:raise ValueError(f'{svg.name}: expected exactly one compound path')
    rec=RecordingPen();parse_path(paths[0],rec)
    rings=[];ring=[];pos=None
    for cmd, points in rec.value:
        if cmd=='moveTo':
            if ring:rings.append(ring)
            pos=points[0];ring=[pos]
        elif cmd=='qCurveTo':
            # The current reference alphabet contains exact quadratic spans.
            if len(points)!=2:raise ValueError(f'{svg.name}: unexpected curve: {points}')
            ctrl,end=points
            for j in range(1,9):
                t=j/8;u=1-t
                ring.append((u*u*pos[0]+2*u*t*ctrl[0]+t*t*end[0],
                             u*u*pos[1]+2*u*t*ctrl[1]+t*t*end[1]))
            pos=end
        elif cmd=='lineTo':
            pos=points[0];ring.append(pos)
        elif cmd=='closePath':
            rings.append(ring);ring=[];pos=None
        else:raise ValueError(f'{svg.name}: unsupported op {cmd}')
    if ring:rings.append(ring)
    geom=None
    for ring in rings:
        if len(ring)<3:continue
        poly=Polygon(ring)
        if not poly.is_valid:poly=poly.buffer(0)
        if poly.is_empty or poly.area<min_area:continue
        geom=poly if geom is None else geom.symmetric_difference(poly)
    if geom is None or geom.is_empty:raise ValueError(f'{svg.name}: no usable ink')
    return geom


def components(geom):
    if isinstance(geom,Polygon):return [geom]
    if isinstance(geom,MultiPolygon):return list(geom.geoms)
    if hasattr(geom,'geoms'):
        return [p for item in geom.geoms for p in components(item)]
    return []


def topology(geom):
    c=components(geom)
    return (len(c),sum(len(p.interiors) for p in c))


def subtract_stroke(geometry, amount:float, tol:float):
    """Topology-constrained inward offset, preserving small dots and counters.

    If a requested offset would remove a component/counter, back it off for the
    affected component. Renders are not regarded as editorially approved.
    """
    result=[];clamps=[]
    for poly in components(geometry):
        old_holes=len(poly.interiors)
        chosen=None
        for factor in (1.0,.9,.8,.7,.6,.5,.4,.3,.2,.1,0.0):
            d=amount*factor
            candidate=poly.buffer(-d,quad_segs=12,join_style=1) if d>0 else poly
            if candidate.is_empty:continue
            if topology(candidate)!=(1,old_holes):continue
            # Do not erase more than 55% of a component's original ink mass.
            if candidate.area < .45*poly.area:continue
            chosen=candidate
            if factor<1:clamps.append(round(factor,2))
            break
        if chosen is None:raise AssertionError('No topology-preserving offset found')
        simp=chosen.simplify(tol,preserve_topology=True)
        if topology(simp)==(1,old_holes):chosen=simp
        result.append(chosen)
    return unary_union(result),clamps


def draw_geometry(geom,pen):
    """Explicit opposite winding for shell/hole to preserve CFF nonzero fill."""
    for polygon in components(geom):
        p=orient(polygon,sign=1.0)
        for contour in [p.exterior,*p.interiors]:
            points=list(contour.coords)[:-1]
            if len(points)<3:raise AssertionError('Degenerate outline')
            pen.moveTo(tuple(points[0]))
            for xy in points[1:]:pen.lineTo(tuple(xy))
            pen.closePath()


def make_weight(base:Path, glyphs_dir:Path, profile:dict, weight:int, raw:Path, overrides:Path|None=None):
    font=TTFont(base,recalcBBoxes=True,recalcTimestamp=False)
    cmap=font.getBestCmap()
    reference=json.loads((glyphs_dir/'manifest.json').read_text())
    if set(reference['glyphs'])!=set(EXPECTED):raise AssertionError('Reference 62 glyph set missing')
    if font.sfntVersion!='OTTO' or 'CFF ' not in font:raise ValueError('CFF baseline required')
    characters=font['CFF '].cff.topDictIndex[0].CharStrings
    report={}
    amount=profile['per_weight_erosion_in_reference_pixels'][str(weight)]
    minimum=profile['min_valid_component_area']
    for ch in EXPECTED:
        v=reference['glyphs'][ch]
        geom=source_polygons(glyphs_dir/f'U+{ord(ch):04X}.svg', minimum)
        d=amount*profile['per_glyph_multiplier'].get(ch,1.0)
        override_svg=(Path(overrides)/str(weight)/f'U+{ord(ch):04X}.svg') if overrides is not None else None
        has_master=override_svg is not None and override_svg.is_file()
        tol=profile['min_simplify_tolerance_px'] if d<.5 else profile['max_simplify_tolerance_px']
        if has_master:
            reduced=source_polygons(override_svg,minimum)
            clamps=[]
            if not .30<=reduced.area/geom.area<=1.20:
                raise AssertionError(f'{ch}: source override area outside safe envelope')
            old_bounds=geom.bounds;new_bounds=reduced.bounds
            if any(abs(old_bounds[i]-new_bounds[i])>3.0 for i in range(4)):
                raise AssertionError(f'{ch}: source override moves glyph outside reference envelope')
        else:
            reduced,clamps=subtract_stroke(geom,d,tol)
        if topology(reduced)!=topology(geom):
            raise AssertionError(f'{ch}: expected {topology(geom)} got {topology(reduced)}')
        if reduced.is_empty or reduced.area<=0:raise AssertionError(f'{ch}: erased')
        y0=v['source_bbox'][1]
        baseline=BASELINES[v['row']]
        pen=T2CharStringPen(width=0,glyphSet=font.getGlyphSet())
        transform=(SCALE,0,0,-SCALE,52,SCALE*(baseline-y0))
        draw_geometry(reduced,TransformPen(pen,transform))
        charstring=pen.getCharString(private=font['CFF '].cff.topDictIndex[0].Private,
                                     globalSubrs=font['CFF '].cff.GlobalSubrs)
        charstring.compile()
        if not charstring.bytecode:raise AssertionError(f'{ch} empty charstring')
        glyph=cmap[ord(ch)]
        characters[glyph]=charstring
        font['hmtx'][glyph]=(round((v['width']+6)*SCALE), round(52+reduced.bounds[0]*SCALE))
        report[ch]={'components':topology(reduced)[0],'counters':topology(reduced)[1],
                    'ink_area_ratio':round(reduced.area/geom.area,4),
                    'stroke_offset_px':(None if has_master else round(d,3)),
                    'outline_source':('weight-specific-svg-master' if has_master else 'contour-aware-derivation'),
                    'override_svg_sha256':(hashlib.sha256(override_svg.read_bytes()).hexdigest() if has_master else None),
                    'topology_adjustments':clamps}
    style=WEIGHTS[weight]
    for nameid,text in ((1,'Chunks Reference Panel'),(16,'Chunks Reference Panel'),
                        (2,style),(17,style),(4,f'Chunks Reference Panel {style}'),
                        (6,f'ChunksReferencePanel-{style}'),
                        (3,'Nonshipping traced-outline weight study. Derived from Baloo 2, SIL OFL 1.1.')):
        font['name'].setName(text,nameid,3,1,0x409)
        font['name'].setName(text,nameid,1,0,0)
    font['OS/2'].usWeightClass=weight
    # Mutually exclusive regular/bold style flags; avoids invalid Bold 700 metadata.
    prior=font['OS/2'].fsSelection
    font['OS/2'].fsSelection=(prior & ~(1<<5 | 1<<6)) | (1<<5 if weight>=700 else 1<<6)
    font.recalcTimestamp=False
    raw.parent.mkdir(parents=True,exist_ok=True)
    font.save(raw)
    check=TTFont(raw)
    if set(check.getBestCmap())!=set(cmap):raise AssertionError('Codepoints lost')
    if not {'GDEF','GPOS','GSUB'}.issubset(check.keys()):raise AssertionError('Shaping tables lost')
    return report


def run(base_dir:Path, glyphs:Path, profile_path:Path, out:Path, weights=None, overrides:Path|None=None):
    profile=json.loads(profile_path.read_text())
    chosen=weights or list(WEIGHTS)
    all_results={}
    out.mkdir(parents=True,exist_ok=True)
    for weight in chosen:
        if weight not in WEIGHTS:raise ValueError(f'Unsupported experimental weight: {weight}')
        style=WEIGHTS[weight]
        with tempfile.TemporaryDirectory() as t:
            raw=Path(t)/f'{style}-raw.otf'
            changes=make_weight(base_dir/f'Chunks-{style}.otf',glyphs,profile,weight,raw,overrides)
            target=out/f'ChunksReferencePanel-{style}-Study.otf'
            font=TTFont(raw,recalcTimestamp=False)
            records,measures=optical_kerning(raw,font,weight)
            cmap=font.getBestCmap()
            changed={cmap[ord(ch)] for ch in EXPECTED}
            selected=[(a,b,v) for a,b,v in records if a in changed or b in changed]
            add_gpos_pairs(font,selected)
            font.recalcTimestamp=False
            font.save(target)
            check=TTFont(target)
            if check.getBestCmap()!=TTFont(raw).getBestCmap():raise AssertionError('Unicode regression')
            if check['OS/2'].usWeightClass!=weight:raise AssertionError('Weight regression')
            if check.sfntVersion!='OTTO' or 'CFF ' not in check:raise AssertionError('CFF regression')
            gaps={str(n):round(min(ink_gaps_at_size(target,SAMPLES,px=n)),3) for n in (14,24,48)}
            if min(gaps.values())<0.25:raise AssertionError(f'Ink collision at {style}: {gaps}')
            tracking,leading,detail=measured_presets(target,check,weight)
            all_results[style]={'weight':weight,'file':target.name,'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),
                'glyphs_changed':len(changes),'codepoints':len(check.getBestCmap()),
                'kerning_direct_pairs':len(selected),'kerning_probe':measures,
                'min_sampled_gap_px':gaps,'tracking_em':tracking,'leading_em':leading,
                'tracking_details':detail,'glyph_geometry':changes}
            print(style,target.stat().st_size,len(selected),'optical pairs',gaps,flush=True)
    manifest_hash=hashlib.sha256((glyphs/'manifest.json').read_bytes()).hexdigest()
    profile_hash=hashlib.sha256(profile_path.read_bytes()).hexdigest()
    override_paths=sorted(p for p in Path(overrides).rglob('U+*.svg') if p.is_file()) if overrides is not None and Path(overrides).exists() else []
    override_hash=hashlib.sha256(b''.join(p.relative_to(overrides).as_posix().encode()+b'\0'+p.read_bytes() for p in override_paths)).hexdigest()
    metrics_path=out/'reference-panel-weight-study-metrics.json' 
    if set(chosen)!=set(WEIGHTS) and metrics_path.exists():
        old=json.loads(metrics_path.read_text())
        if old.get('profile_sha256')==profile_hash and old.get('source_manifest_sha256')==manifest_hash and old.get('overrides_sha256')==override_hash:
            all_results={**old['weights'],**all_results}
    stats={'status':'NONSHIPPING interim weight study: automated offsets; manual masters still required',
           'source_manifest_sha256':manifest_hash,
           'profile_sha256':profile_hash,
           'overrides_sha256':override_hash,
           'overrides_used':len(override_paths),
           'source_svg_sha256':hashlib.sha256(b''.join((glyphs/f'U+{ord(ch):04X}.svg').read_bytes() for ch in EXPECTED)).hexdigest(),
           'accepted_extra_bold_remains_separate':'assets/fonts/experimental/ChunksReferencePanel-ExtraBold-Optical.otf',
           'weights':all_results}
    metrics_path.write_text(json.dumps(stats,indent=2)+'\n')
    return stats

if __name__=='__main__':
    cli=argparse.ArgumentParser()
    cli.add_argument('--base-dir',type=Path,default=Path('assets/fonts/chunks'))
    cli.add_argument('--reference',type=Path,default=Path('assets/fonts/source/reference-alphabet'))
    cli.add_argument('--profile',type=Path,default=Path('tools/reference_weight_profiles.json'))
    cli.add_argument('--out',type=Path,default=Path('assets/fonts/experimental/weight-studies'))
    cli.add_argument('--overrides',type=Path,default=Path('assets/fonts/source/reference-alphabet/weight-overrides'))
    cli.add_argument('--weights',type=int,nargs='*',default=None)
    a=cli.parse_args()
    run(a.base_dir,a.reference,a.profile,a.out,a.weights,a.overrides)