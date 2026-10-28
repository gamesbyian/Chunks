#!/usr/bin/env python3
"""Conservatively graft detached licensed accents onto experimental Chunks Latin bases.

Never replaces approved Chunks fonts. Only simple canonical, precomposed Latin
characters with independently separated accents are reconstructed; connected
marks, i/j dot substitutions, and exotic/non-Latin scripts remain untouched.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import unicodedata as ud
from fontTools.ttLib import TTFont
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.recordingPen import RecordingPen, replayRecording
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.t2CharStringPen import T2CharStringPen

WEIGHTS={400:'Regular',500:'Medium',600:'Semibold',700:'Bold',800:'ExtraBold'}
STEMS='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghklmnopqrstuvwxyz' # exclude i/j for removable dot treatment
SOURCE='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'
GAP_UNITS=28


def bounds(recording, glyphset):
    p=BoundsPen(glyphset)
    replayRecording(recording,p)
    return p.bounds


def glyph_record(glyphset,name):
    pen=RecordingPen();glyphset[name].draw(pen)
    return pen.value


def contours(recording):
    out=[];curr=[]
    for op,pts in recording:
        if op=='moveTo' and curr:
            out.append(curr);curr=[]
        curr.append((op,pts))
        if op in ('closePath','endPath'):
            out.append(curr);curr=[]
    if curr:out.append(curr)
    return out


def candidates(original:TTFont):
    cmap=original.getBestCmap()
    for cp,glyph in sorted(cmap.items()):
        ch=chr(cp)
        if not ud.name(ch,'').startswith('LATIN '):continue
        nfd=ud.normalize('NFD',ch)
        if len(nfd)!=2 or nfd[0] not in STEMS or ud.category(nfd[1])!='Mn':continue
        if ord(nfd[0]) not in cmap:continue
        yield ch,nfd[0],glyph,cmap[ord(nfd[0])]


def create_variant(source:Path,reference:Path,out:Path)->dict:
    """Reconstruct only precomposed glyphs whose marks are entirely detached.

    Em-size baseline is preserved and accents are shifted minimally to clear
    the *new* reference outline; native source accents are never scaled.
    """
    original=TTFont(source,recalcTimestamp=False)
    final=TTFont(reference,recalcTimestamp=False)
    if original.getBestCmap()!=final.getBestCmap():
        raise AssertionError('Reference base and licensed source Unicode cmap differ')
    if original.sfntVersion!='OTTO' or final.sfntVersion!='OTTO':
        raise AssertionError('CFF OpenType required')
    chars=final['CFF '].cff.topDictIndex[0].CharStrings
    oldset=original.getGlyphSet();newset=final.getGlyphSet()
    built=[];skipped=[]
    for ch,base,compound,base_name in candidates(original):
        new_base=glyph_record(newset,base_name)
        baseb=bounds(new_base,newset)
        old_base=glyph_record(oldset,base_name)
        oldb=bounds(old_base,oldset)
        if not baseb or not oldb:continue
        old_compound=glyph_record(oldset,compound)
        marks=[]
        for path in contours(old_compound):
            pb=bounds(path,oldset)
            if not pb:continue
            if pb[1]>oldb[3]+6:
                marks.append((path,pb,'above'))
            elif pb[3]<oldb[1]-6:
                marks.append((path,pb,'below'))
        if not marks:
            skipped.append(ch);continue
        if any(d!=marks[0][2] for _,_,d in marks):
            skipped.append(ch);continue
        markbox=(min(m[1][0] for m in marks),min(m[1][1] for m in marks),
                 max(m[1][2] for m in marks),max(m[1][3] for m in marks))
        xshift=round((baseb[0]+baseb[2]-oldb[0]-oldb[2])/2)
        yshift=0
        if marks[0][2]=='above':
            yshift=round(max(0,baseb[3]+GAP_UNITS-markbox[1]))
        else:
            yshift=round(min(0,baseb[1]-GAP_UNITS-markbox[3]))
        markbb=(markbox[0]+xshift,markbox[1]+yshift,
                markbox[2]+xshift,markbox[3]+yshift)
        fullbb=(min(markbb[0],baseb[0]),min(markbb[1],baseb[1]),
                max(markbb[2],baseb[2]),max(markbb[3],baseb[3]))
        # Keep Unicode-wide line metrics and prevent marks overflowing the em.
        if fullbb[1]<-480 or fullbb[3]>1040:
            skipped.append(ch);continue
        dest=T2CharStringPen(width=0,glyphSet=final.getGlyphSet())
        replayRecording(new_base,dest)
        trans=TransformPen(dest,(1,0,0,1,xshift,yshift))
        for path,_,_ in marks:replayRecording(path,trans)
        charstring=dest.getCharString(private=final['CFF '].cff.topDictIndex[0].Private,
                                      globalSubrs=final['CFF '].cff.GlobalSubrs)
        charstring.compile()
        if not charstring.bytecode:raise AssertionError(f'{ch}: empty CFF')
        chars[compound]=charstring
        final['hmtx'][compound]=(final['hmtx'][base_name][0],round(fullbb[0]))
        built.append({'glyph':ch,'unicode':f'U+{ord(ch):04X}','base':base,
            'direction':marks[0][2],'parts':len(marks),
            'x_adjust':xshift,'y_adjust':yshift,
            'bounds':[round(n,2) for n in fullbb]})
    for idnum,text in ((1,'Chunks Reference Panel Accents'),(16,'Chunks Reference Panel Accents')):
        for platform,encoding,lang in [(3,1,0x409),(1,0,0)]:
            final['name'].setName(text,idnum,platform,encoding,lang)
    final.recalcTimestamp=False
    out.parent.mkdir(parents=True,exist_ok=True)
    final.save(out)
    check=TTFont(out)
    if check.getBestCmap()!=original.getBestCmap():raise AssertionError('Lost Unicode coverage')
    if not {'GPOS','GSUB','GDEF'}.issubset(check.keys()):raise AssertionError('Lost shaping tables')
    return {'font':out.name,'built':len(built),'skipped_undetected':len(skipped),
            'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
            'reference_sha256':hashlib.sha256(reference.read_bytes()).hexdigest(),
            'output_sha256':hashlib.sha256(out.read_bytes()).hexdigest(),
            'glyphs':built,'skipped_chars':''.join(skipped)}


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--source-dir',type=Path,default=Path('assets/fonts/chunks'))
    p.add_argument('--weight-studies',type=Path,default=Path('assets/fonts/experimental/weight-studies'))
    p.add_argument('--extra-bold',type=Path,default=Path('assets/fonts/experimental/ChunksReferencePanel-ExtraBold-Optical.otf'))
    p.add_argument('--out',type=Path,default=Path('assets/fonts/experimental/accent-studies'))
    p.add_argument('--weights',type=int,nargs='*')
    args=p.parse_args()
    summary={}
    for weight,style in WEIGHTS.items():
        if args.weights and weight not in args.weights:continue
        old=args.source_dir/f'Chunks-{style}.otf'
        face=args.extra_bold if weight==800 else args.weight_studies/f'ChunksReferencePanel-{style}-Study.otf'
        target=args.out/f'ChunksReferencePanel-{style}-AccentStudy.otf'
        summary[style]=create_variant(old,face,target)
        print(style,'constructed',summary[style]['built'],'accents',flush=True)
    args.out.mkdir(parents=True,exist_ok=True)
    (args.out/'reference-accent-study-metrics.json').write_text(json.dumps({'status':'NONSHIPPING hybrid accent graft study, manual review needed','weights':summary},indent=2,ensure_ascii=False)+'\n')

if __name__=='__main__':main()