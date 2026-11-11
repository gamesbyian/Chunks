#!/usr/bin/env python3
"""Dependency-free structural checks. No claim of real-world decision correctness."""
import argparse
import json
from pathlib import Path
import re
import sys

ROOT=Path(__file__).resolve().parents[1]
NAME=re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
LINK=re.compile(r"\]\(([^)]+)\)")
CHOICES={"A","B","C"}

def check(root=ROOT):
    root=Path(root).resolve()
    errors=[]
    skill=root/"SKILL.md"
    if not skill.is_file():
        return ["Missing SKILL.md"]
    s=skill.read_text(encoding="utf-8")
    if not s.startswith("---\n") or "\n---\n" not in s[4:]:
        errors.append("Missing skill frontmatter")
    else:
        fm=s.split("---",2)[1]
        name=re.findall(r"(?m)^name:\s*(\S+)\s*$",fm)
        desc=re.findall(r"(?m)^description:\s*(.*)$",fm)
        if len(name)!=1 or len(name[0])>64 or not NAME.fullmatch(name[0]):
            errors.append("Bad skill name")
        if len(desc)!=1 or not (1<=len(desc[0])<=1024):
            errors.append("Bad description")
    if len(s.splitlines())>500:
        errors.append("Main skill body exceeds progressive disclosure limit")
    # Allow verified sibling links only when the full skills package is present.
    package_root = root.parent if (root.parent / "CAPABILITY-ARCHITECTURE.md").is_file() else root
    for p in root.rglob("*.md"):
        doc=p.read_text(encoding="utf-8")
        if not doc.strip():
            errors.append(f"Empty {p.relative_to(root)}")
        for target in LINK.findall(doc):
            if target.startswith(("https://","http://","mailto:","#")):
                continue
            filename=target.split("#",1)[0]
            if not filename:
                continue
            resolved=(p.parent/filename).resolve()
            if not resolved.is_relative_to(package_root) or not resolved.is_file():
                errors.append(f"Broken/out-of-root local reference {p.relative_to(root)} -> {target}")
    def load(path):
        try:
            return json.loads((root/path).read_text(encoding="utf-8"))
        except (OSError,json.JSONDecodeError,UnicodeError) as exc:
            errors.append(f"Missing/invalid {path}: {exc}")
            return {}
    patterns=load("assets/patterns.json")
    prompts=load("assets/transfer-probes.json")
    answers=load("assets/transfer-probe-key.json")
    pids=set()
    if patterns.get("schema")!="engineering-judgment/patterns-v1" or not isinstance(patterns.get("patterns"),list) or len(patterns["patterns"])<12:
        errors.append("Insufficient/invalid pattern catalog")
    else:
        for p in patterns["patterns"]:
            ident=p.get("id")
            if not isinstance(ident,str) or not NAME.fullmatch(ident) or ident in pids:
                errors.append(f"Invalid/duplicate pattern {ident}")
            pids.add(ident)
            for field in ("name","critical_exception","positive","discriminator","source"):
                if not isinstance(p.get(field),str) or not p[field].strip():
                    errors.append(f"Pattern {ident} missing {field}")
            if not str(p.get("source","")).startswith("https://github.com/"):
                errors.append(f"Pattern {ident} missing source")
            for field in ("triggers","hidden"):
                if not isinstance(p.get(field),list) or not p[field]:
                    errors.append(f"Pattern {ident} missing {field}")
    items=prompts.get("probes",[])
    key=answers.get("answers",[])
    if (prompts.get("schema")!="engineering-judgment/transfer-prompts-v1" or
        answers.get("schema")!="engineering-judgment/transfer-key-v1" or
        not isinstance(items,list) or len(items)<15 or not isinstance(key,list)):
        errors.append("Invalid/too small probe corpus")
        return errors
    prompt_ids=set()
    for p in items:
        ident=p.get("id")
        if ident in prompt_ids or not isinstance(ident,str) or not re.fullmatch(r"J\d\d",ident):
            errors.append(f"Duplicate/invalid probe {ident}")
        prompt_ids.add(ident)
        if set(p)!={"id","prompt","options"}:
            errors.append(f"Probe {ident} leaks key fields or lacks prompt")
        opts=p.get("options",[])
        if not isinstance(opts,list) or len(opts)!=3 or {o.get("id") for o in opts}!={"A","B","C"}:
            errors.append(f"Probe {ident} lacks A/B/C")
        if not isinstance(p.get("prompt"),str) or len(p["prompt"])<70:
            errors.append(f"Probe {ident} too short")
    key_ids=set()
    for a in key:
        ident=a.get("id")
        if ident in key_ids or ident not in prompt_ids:
            errors.append(f"Invalid key ID {ident}")
        key_ids.add(ident)
        if a.get("answer") not in CHOICES or a.get("pattern") not in pids or not a.get("reason"):
            errors.append(f"Invalid answer or pattern for {ident}")
    if prompt_ids!=key_ids:
        errors.append("Prompt and key ID sets disagree")
    if len({a.get("pattern") for a in key})<9:
        errors.append("Transfer probes have inadequate conceptual coverage")
    return errors

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skill-root",type=Path,default=ROOT)
    args=parser.parse_args()
    errors=check(args.skill_root)
    for error in errors:
        print("FAIL:",error,file=sys.stderr)
    if errors:
        return 1
    print("PASS: frontmatter, progressive references, evidence-linked patterns, prompt/key integrity")
    return 0

if __name__=="__main__":
    sys.exit(main())
