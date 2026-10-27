#!/usr/bin/env node
// Dependency-free, deterministic SVG -> Android VectorDrawable conversion.
// The source SVG is the only editable icon artwork; aapt cannot consume SVG directly.
// Supports the approved icon's absolute paths, circles, linear gradients and clipping.
// SVG blur filters are omitted (the vector retains the artwork without raster shadows).
const fs = require("node:fs");
const crypto = require("node:crypto");
const path = require("node:path");
function buildVector(svg) {
  const xml = s => String(s).replace(/&/g,"&amp;").replace(/"/g,"&quot;").replace(/</g,"&lt;");
  const attrs = s => Object.fromEntries([...s.matchAll(/([:\w-]+)="([^"]*)"/g)].map(m=>[m[1],m[2]]));
  const num = n => Number(n.toFixed(3)).toString();
  const hex = h => {
    h={white:"#FFFFFF",black:"#000000"}[h]||h;
    if (!h || !h.startsWith("#")) throw new Error("Unsupported colour: "+h);
    h=h.slice(1); if(h.length===3)h=[...h].map(x=>x+x).join("");
    if(h.length!==6)throw new Error("Colour must be RGB: "+h);
    return "#"+h.toUpperCase();
  };
  const alphaColor=(rgb,alpha)=>"#"+Math.round(255*Math.max(0,Math.min(1,alpha))).toString(16).padStart(2,"0").toUpperCase()+hex(rgb).slice(1);
  const gradients={};
  for(const m of svg.matchAll(/<linearGradient\b([^>]*)>([\s\S]*?)<\/linearGradient>/g)){
    const a=attrs(m[1]);
    const stops=[...m[2].matchAll(/<stop\b([^>]*)\/>/g)].map(x=>{
      const s=attrs(x[1]);
      return {offset:Number(s.offset||0),color:alphaColor(s["stop-color"],Number(s["stop-opacity"]??1))};
    });
    if(stops.length<2)throw new Error("Gradient lacks stops "+a.id);
    gradients[a.id]={...a,stops};
  }
  const clip={};
  for(const m of svg.matchAll(/<clipPath\b([^>]*)>([\s\S]*?)<\/clipPath>/g)){
    const a=attrs(m[1]);
    const d=m[2].match(/<path\b([\s\S]*?)\/>/);
    if(!d)throw new Error("Unsupported clipPath");
    clip[a.id]=attrs(d[1]).d.replace(/\s+/g," ").trim();
  }
  const bounds=d=>{
    const tokens=d.match(/[A-Za-z]|-?\d*\.?\d+(?:[Ee][-+]?\d+)?/g)||[];
    let command=null, pairs=[], data=[];
    for(const t of tokens){
      if(/^[A-Za-z]$/.test(t)) {command=t;if(!"MLCQZ".includes(command))throw new Error("Unsupported SVG command "+command);}
      else if(command!=="Z")data.push(Number(t));
    }
    if(data.length%2)throw new Error("Odd SVG coordinate count");
    for(let i=0;i<data.length;i+=2)pairs.push([data[i],data[i+1]]);
    if(!pairs.length)throw new Error("No path points");
    return {l:Math.min(...pairs.map(x=>x[0])),t:Math.min(...pairs.map(x=>x[1])),r:Math.max(...pairs.map(x=>x[0])),b:Math.max(...pairs.map(x=>x[1]))};
  };
  const lines = [];
  lines.push('<?xml version="1.0" encoding="utf-8"?>');
  lines.push('<!-- GENERATED from docs/design/approved/chunks-app-icon-master.svg by tools/generate_launcher_vector.cjs.');
  lines.push('     SOURCE_SVG_GIT_BLOB=PLACEHOLDER_GIT_BLOB; do not edit this Android rendering directly. -->');
  lines.push('<vector xmlns:android="http://schemas.android.com/apk/res/android"');
  lines.push('        xmlns:aapt="http://schemas.android.com/aapt"');
  lines.push('        android:width="108dp" android:height="108dp"');
  lines.push('        android:viewportWidth="1200" android:viewportHeight="1200">');
  lines.push('  <group android:scaleX="0.73" android:scaleY="0.73" android:pivotX="600" android:pivotY="620">');
  const body=svg.slice(svg.indexOf("</defs>")+7,svg.lastIndexOf("</svg>"));
  const stack=[{}];
  let shapeCount=0,skipCount=0;
  for(const tag of body.match(/<!--[\s\S]*?-->|<[^>]+>/g)||[]){
    if(tag.startsWith("<!--"))continue;
    if(tag.startsWith("</g")){
      if(stack.length===1)throw new Error("Unbalanced g");
      stack.pop();lines.push("    </group>");continue;
    }
    if(tag.startsWith("<g ")){
      const a=attrs(tag),prev=stack[stack.length-1];stack.push({...prev,...a});
      lines.push("    <group>");
      if(a["clip-path"]){
        const key=a["clip-path"].match(/^url\(#(.+)\)$/)?.[1];
        if(!clip[key])throw new Error("Missing clip "+key);
        lines.push('      <clip-path android:pathData="'+xml(clip[key])+'"/>');
      }
      continue;
    }
    if(!tag.startsWith("<path ") && !tag.startsWith("<circle "))continue;
    const a=attrs(tag), merged={...stack[stack.length-1],...a};
    if(merged.filter){skipCount++;continue;}
    let d,b;
    if(tag.startsWith("<circle ")){
      const cx=Number(a.cx),cy=Number(a.cy),r=Number(a.r);
      d=`M${num(cx+r)},${num(cy)} A${num(r)},${num(r)} 0 1,1 ${num(cx-r)},${num(cy)} A${num(r)},${num(r)} 0 1,1 ${num(cx+r)},${num(cy)} Z`;
      b={l:cx-r,r:cx+r,t:cy-r,b:cy+r};
    }else{
      if(!a.d)throw new Error("Path has no d");
      d=a.d.replace(/\s+/g," ").trim();
      b=bounds(d);
    }
    const fill=merged.fill??(merged.stroke?"none":"#000000");
    const stroke=merged.stroke??"none";
    const opacity=Number(merged.opacity??1);
    const props=['android:pathData="'+xml(d)+'"'];
    if(fill==="none") props.push('android:fillColor="@android:color/transparent"');
    else if(fill.startsWith("#") || fill==="white" || fill==="black")props.push('android:fillColor="'+hex(fill)+'"');
    else if(!/^url\(#([^)]+)\)$/.test(fill))throw new Error("Invalid fill "+fill);
    props.push('android:fillAlpha="'+num(opacity*Number(merged["fill-opacity"]??1))+'"');
    if(stroke!=="none"){
      props.push('android:strokeColor="'+hex(stroke)+'"');
      props.push('android:strokeWidth="'+num(Number(merged["stroke-width"]??1))+'"');
      props.push('android:strokeAlpha="'+num(opacity*Number(merged["stroke-opacity"]??1))+'"');
      if(merged["stroke-linecap"])props.push('android:strokeLineCap="'+merged["stroke-linecap"]+'"');
      if(merged["stroke-linejoin"])props.push('android:strokeLineJoin="'+merged["stroke-linejoin"]+'"');
    }
    if(fill.startsWith("url(")){
      const key=fill.match(/^url\(#([^)]+)\)$/)[1], g=gradients[key];
      if(!g)throw new Error("Missing gradient "+key);
      const x1=Number(g.x1??0),y1=Number(g.y1??0),x2=Number(g.x2??1),y2=Number(g.y2??0);
      const coords=g.gradientUnits==="userSpaceOnUse"?
        [x1,y1,x2,y2]:[b.l+(b.r-b.l)*x1,b.t+(b.b-b.t)*y1,b.l+(b.r-b.l)*x2,b.t+(b.b-b.t)*y2];
      lines.push("      <path "+props.join(" ")+">");
      lines.push('        <aapt:attr name="android:fillColor">');
      lines.push('          <gradient android:type="linear" android:startX="'+num(coords[0])+'" android:startY="'+num(coords[1])+'" android:endX="'+num(coords[2])+'" android:endY="'+num(coords[3])+'">');
      for(const stop of g.stops)lines.push('            <item android:offset="'+num(stop.offset)+'" android:color="'+stop.color+'"/>');
      lines.push("          </gradient>");
      lines.push("        </aapt:attr>");
      lines.push("      </path>");
    }else lines.push("      <path "+props.join(" ")+"/>");
    shapeCount++;
  }
  if(stack.length!==1)throw new Error("Unclosed groups");
  lines.push("  </group>");lines.push("</vector>");
  return {body:lines.join("\n")+"\n",shapeCount,skipCount};
}
const root = path.resolve(__dirname, "..");
const source = fs.readFileSync(path.join(root, "docs/design/approved/chunks-app-icon-master.svg"), "utf8");
const blob = crypto.createHash("sha1").update("blob "+Buffer.byteLength(source)+"\0").update(source).digest("hex");
const {body,shapeCount,skipCount}=buildVector(source);
const output=body.replace("PLACEHOLDER_GIT_BLOB",blob);
const dest=path.join(root,"app/src/main/res/drawable/chunks_launcher_artwork.xml");
if(process.argv.includes("--check")){
  if(!fs.existsSync(dest)||fs.readFileSync(dest,"utf8")!==output){
    process.stderr.write("Launcher vector is stale: run node tools/generate_launcher_vector.cjs\n");
    process.exit(1);
  }
  console.log("Approved SVG and Android vector agree ("+shapeCount+" elements, "+skipCount+" raster-only shadow underlays omitted)");
}else{
  fs.mkdirSync(path.dirname(dest),{recursive:true});
  fs.writeFileSync(dest,output);
  console.log("Generated "+path.relative(root,dest)+" from approved SVG");
}
