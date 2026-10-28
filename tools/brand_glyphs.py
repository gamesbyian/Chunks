#!/usr/bin/env python3
"""Create fully vector Chunks wordmark-derived glyphs in five CFF face weights.

The source is a retained reference SVG with explicit per-letter skeletons.
A smooth, rounded Minkowski stroke is sampled and scaled into each face's native
advance-height box. All other glyphs retain the upstream OFL master outlines.
"""
from __future__ import annotations

from pathlib import Path
from xml.etree import ElementTree as ET

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.svgLib.path import parse_path
from shapely.geometry import LineString, Polygon
from shapely.ops import unary_union

STROKE = {400: 34, 500: 43, 600: 53, 700: 62, 800: 70}
REFERENCE_INSET = {400: 12.0, 500: 9.0, 600: 6.0, 700: 3.0, 800: 0.0}
LETTERS = 'Chunks'  # C, h, u, n, k, s; duplicates handled once.


def _cubic(p0, p1, p2, p3, t):
    q=1-t
    return (q**3*p0[0]+3*q*q*t*p1[0]+3*q*t*t*p2[0]+t**3*p3[0],
            q**3*p0[1]+3*q*q*t*p1[1]+3*q*t*t*p2[1]+t**3*p3[1])


def stroke_skeleton(d, width, samples=22):
    """Return a unioned shapely polygon, preserving enclosed counters as holes."""
    recorder=RecordingPen()
    parse_path(d, recorder)
    chains=[]; cur=[]; point=None; first=None
    for op,args in recorder.value:
        if op == 'moveTo':
            if len(cur)>1: chains.append(cur)
            point=args[0];first=point;cur=[point]
        elif op == 'lineTo':
            point=args[0];cur.append(point)
        elif op == 'curveTo':
            for i in range(0,len(args),3):
                a,b,c=args[i:i+3]
                if len((a,b,c))<3: raise ValueError('Malformed cubic SVG')
                p=point
                cur.extend(_cubic(p,a,b,c,j/samples) for j in range(1,samples+1))
                point=c
        elif op == 'endPath':
            pass
        elif op == 'closePath':
            if point != first: cur.append(first)
            point=first
        else:
            raise ValueError(f'Unsupported SVG instruction {op}')
    if len(cur)>1: chains.append(cur)
    if not chains: raise ValueError('No stroke skeletons')
    return unary_union([LineString(c).buffer(width/2,quad_segs=8,cap_style='round',join_style='round') for c in chains])


def _quadratic(p0,p1,p2,t):
    q=1-t
    return (q*q*p0[0]+2*q*t*p1[0]+t*t*p2[0],
            q*q*p0[1]+2*q*t*p1[1]+t*t*p2[1])


def filled_reference_outline(d, samples=6):
    """Sample the six reference-traced, independently closed quadratic outlines.

    Unlike stroked skeletons, these are actual filled, raster-reconstructed brand
    silhouettes. A small negative buffer produces lighter weight derivatives.
    """
    recorder=RecordingPen();parse_path(d,recorder)
    closed=[];cur=[];point=None;first=None
    for op,args in recorder.value:
        if op=='moveTo':
            if cur: raise ValueError('Unclosed reference contour')
            point=args[0];first=point;cur=[point]
        elif op=='lineTo':
            point=args[0];cur.append(point)
        elif op=='qCurveTo':
            # SVG parser normally emits exactly one control+end point per Q.
            if len(args)!=2:raise ValueError('Unexpected multi-point quadratic in traced SVG')
            control,end=args
            cur.extend(_quadratic(point,control,end,j/samples) for j in range(1,samples+1))
            point=end
        elif op=='curveTo':
            if len(args)!=3:raise ValueError('Unexpected cubic in traced SVG')
            a,b,c=args
            cur.extend(_cubic(point,a,b,c,j/samples) for j in range(1,samples+1))
            point=c
        elif op=='closePath':
            if cur:
                if cur[-1]!=first:cur.append(first)
                closed.append(cur)
            cur=[];point=None;first=None
        elif op=='endPath':
            raise ValueError('Filled reference glyph requires closed SVG contours')
        else:raise ValueError('Unsupported SVG command '+op)
    if cur:raise ValueError('Unclosed silhouette')
    if len(closed)!=1:raise ValueError('Expected exactly one independent silhouette contour')
    shape=Polygon(closed[0]);
    if not shape.is_valid:shape=shape.buffer(0)
    if shape.is_empty:raise ValueError('Empty silhouette')
    return shape


def source_paths(svg_path:Path):
    root=ET.parse(svg_path).getroot()
    result={}
    for e in root.iter():
        ch=e.get('data-glyph') or (e.get('id','')[6:] if e.get('id','').startswith('glyph-') else None)
        if ch:
            if ch in result: raise ValueError(f'duplicate master glyph {ch!r}')
            result[ch]=e.get('d')
    if set(result)!=set(LETTERS):
        raise ValueError(f'Expected exactly wordmark glyphs {set(LETTERS)}, saw {set(result)}')
    return result


def _polygons(geom):
    if geom.geom_type=='Polygon': return [geom]
    if geom.geom_type=='MultiPolygon': return list(geom.geoms)
    raise ValueError(f'Unexpected stroke outline: {geom.geom_type}')


def apply_brand_glyphs(font, weight:int, master:Path):
    """Patch CFF base glyph outlines from editable paths, preserving widths and shaping.

    All six characters use ink-bounding-box fitting to the source font weight.
    Other characters, including unused accents, retain native font outlines.
    This produces an editorial display candidate, not a general-purpose glyph redesign.
    """
    assert 'CFF ' in font and 'hmtx' in font
    top=font['CFF '].cff.topDictIndex[0]
    glyphset=font.getGlyphSet()
    cmap=font.getBestCmap()
    paths=source_paths(master)
    is_trace=bool('id="glyph-' in master.read_text(encoding='utf8'))
    detail={}
    for ch,d in paths.items():
        name=cmap[ord(ch)]
        bounds=BoundsPen(glyphset)
        glyphset[name].draw(bounds)
        if not bounds.bounds: raise ValueError('Missing base glyph bounds '+ch)
        x0,y0,x1,y1=bounds.bounds
        # Reference-traced filled contours are the primary glyph sources. The
        # original stroke-skeleton variant remains supported for A/B studies.
        if is_trace:
            shape=filled_reference_outline(d)
            inset=REFERENCE_INSET[weight]
            if inset:shape=shape.buffer(-inset,join_style='round')
            if shape.is_empty:raise ValueError(f'Weight erosion removed {ch} at {weight}')
        else:
            shape=stroke_skeleton(d,STROKE[weight]*(0.7 if ch=='s' else 1.0))
        bx0,by0,bx1,by1=shape.bounds
        # CFF y axis grows upwards, SVG downwards; preserve regular output widths
        # and baseline while giving each native weight its own stroke density.
        fx=(x1-x0)/(bx1-bx0)
        fy=(y1-y0)/(by1-by0)
        def pt(p): return (round(x0+(p[0]-bx0)*fx,3),round(y0+(by1-p[1])*fy,3))
        pen=T2CharStringPen(width=0,glyphSet=None)
        for polygon in _polygons(shape):
            for ring in [polygon.exterior,*polygon.interiors]:
                seq=list(ring.coords)
                if len(seq)<4: continue
                pen.moveTo(pt(seq[0]))
                for p in seq[1:-1]:pen.lineTo(pt(p))
                pen.closePath()
        top.CharStrings[name]=pen.getCharString(private=top.Private,globalSubrs=top.GlobalSubrs)
        detail[ch]={'native_advance':font['hmtx'][name][0], 'native_bbox':[round(i) for i in (x0,y0,x1,y1)],
                    'outline_source':('reference_trace' if is_trace else 'stroke_skeleton'),
                    'nominal_stroke':STROKE[weight], 'reference_inset':REFERENCE_INSET[weight],
                    'silhouette_polygons':len(_polygons(shape))}
    return detail