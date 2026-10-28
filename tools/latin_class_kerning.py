"""Bounded accent-inherited kerning for previously unmeasured Latin codepoints.

Only explicit Latin canonical decompositions inherit half-strength corrections;
non-Latin shaping and directly measured optical pairs are never duplicated.
The inheritance is represented by TWO compact GPOS PairPos Format 2 lookups:
  A) first glyph unmeasured, second glyph measured or unmeasured
  B) first glyph measured, second glyph unmeasured
Both classes are disjoint for first-position glyphs, so double correction is
impossible. Matrices use zero for every unrelated second-position class.
"""
from __future__ import annotations
import unicodedata
from fontTools.ttLib.tables import otTables
from fontTools.ttLib.tables.otBase import ValueRecord


def _char_base(ch):
    n=unicodedata.normalize('NFD',ch)
    if len(n)>1 and all(unicodedata.combining(x) for x in n[1:]):
        return n[0]
    return ch


def _eligible_variants(font, measured):
    cmap=font.getBestCmap()
    reverse={chr(k):v for k,v in cmap.items()}
    eligible={};excluded=[]
    for ch,glyph in reverse.items():
        if ch in measured or 'LATIN' not in unicodedata.name(ch,''):
            continue
        norm=unicodedata.normalize('NFD',ch)
        if len(norm)<2 or norm[0] not in measured or not all(unicodedata.combining(c) for c in norm[1:]):
            excluded.append(ch);continue
        root=norm[0];rep=reverse.get(root)
        if not rep or glyph==rep:continue
        advance=font['hmtx'][glyph][0];base=font['hmtx'][rep][0]
        if base<=0 or abs(advance-base)>max(20,base*.12):
            excluded.append(ch);continue
        eligible[glyph]=rep
    return eligible,excluded


def _lookup(font, coverage, classes1, classes2, pairs, ordered_bases):
    order={name:i for i,name in enumerate(font.getGlyphOrder())}
    classids={g:i+1 for i,g in enumerate(ordered_bases)}
    sub=otTables.PairPos();sub.Format=2
    sub.Coverage=otTables.Coverage();sub.Coverage.glyphs=sorted(coverage,key=lambda g:order[g])
    sub.ValueFormat1=4;sub.ValueFormat2=0
    sub.ClassDef1=otTables.ClassDef();sub.ClassDef1.classDefs={g:classids[base] for g,base in classes1.items()}
    sub.ClassDef2=otTables.ClassDef();sub.ClassDef2.classDefs={g:classids[base] for g,base in classes2.items()}
    sub.Class1Count=len(ordered_bases)+1;sub.Class2Count=len(ordered_bases)+1
    sub.Class1Record=[]
    for row_base in [None]+ordered_bases:
        row=otTables.Class1Record();row.Class2Record=[]
        for column_base in [None]+ordered_bases:
            v=0
            if row_base is not None and column_base is not None:
                v=int(pairs.get((row_base,column_base),0))
            record=otTables.Class2Record()
            record.Value1=ValueRecord();record.Value1.XAdvance=v
            row.Class2Record.append(record)
        sub.Class1Record.append(row)
    lookup=otTables.Lookup();lookup.LookupType=2;lookup.LookupFlag=0
    lookup.SubTable=[sub];lookup.SubTableCount=1
    return lookup


def extend_latin_kerning(font, direct_records, measured_chars):
    """Extend calibrated pairs to NFD-equivalent glyphs, without replacing source GPOS.

    ``direct_records`` are the *additional* optical corrections, not the native
    source kern. Inheritance is conservatively reduced to 50% and capped at 20
    font units; we do not guess actual mark positions or invent script shaping.
    """
    cmap=font.getBestCmap();reverse={chr(cp):g for cp,g in cmap.items()}
    measured={ch for ch in measured_chars if ch in reverse}
    eligible,excluded=_eligible_variants(font,measured)
    if not eligible:return {'eligible_accent_variants':0,'class_lookups':0,'unrecovered_latin':len(excluded)}
    all_bases={_char_base(ch) for ch in measured if ch in reverse}
    all_bases.update(eligible.values())
    # Convert base character grouping into a single representative glyph name.
    reps=set()
    for base in all_bases:
        if base in reverse: reps.add(reverse[base])
        elif base in font.getGlyphOrder():reps.add(base)
    ordered=sorted(reps,key=lambda g:font.getGlyphID(g))
    records={(g1,g2):int(v) for g1,g2,v in direct_records}
    pairs={(a,b):max(-20,min(20,round(records.get((a,b),0)*0.5))) for a in ordered for b in ordered}
    measured_mapping={}
    for ch in sorted(measured):
        g=reverse.get(ch);base=reverse.get(_char_base(ch))
        if g and base in reps:measured_mapping[g]=base
    # A: only new accented forms in Coverage; any measured/unmeasured second glyph.
    class2_a={**measured_mapping, **eligible}
    a=_lookup(font,eligible.keys(),eligible,class2_a,pairs,ordered)
    # B: only directly-measured first glyphs, but second must be new.
    # Class2 id 0 for any measured second glyph has a guaranteed zero matrix value.
    b=_lookup(font,measured_mapping.keys(),measured_mapping,eligible,pairs,ordered)
    gpos=font['GPOS'].table;start=len(gpos.LookupList.Lookup)
    gpos.LookupList.Lookup.extend([a,b]);gpos.LookupList.LookupCount=len(gpos.LookupList.Lookup)
    feature_count=0
    for rec in gpos.FeatureList.FeatureRecord:
        if rec.FeatureTag=='kern':
            rec.Feature.LookupListIndex.extend([start,start+1]);rec.Feature.LookupCount=len(rec.Feature.LookupListIndex)
            feature_count+=1
    if not feature_count:raise RuntimeError('No kern feature to attach accent inheritance')
    return {'eligible_accent_variants':len(eligible),'class_lookups':2,
            'base_classes':len(ordered),'unrecovered_latin':len(excluded),
            'direct_probe_characters':len(measured),'matrix_nonzero_pairs':sum(bool(v) for v in pairs.values())}