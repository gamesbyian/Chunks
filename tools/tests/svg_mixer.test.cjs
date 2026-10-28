"use strict";
const test=require("node:test"),assert=require("node:assert/strict"),fs=require("node:fs");
const path=require("node:path"),{spawnSync}=require("node:child_process");
const mix=require("../../docs/design/svg/mix-engine.js");
const root=path.resolve(__dirname,"../..");
const load=(id,file)=>({id,svg:fs.readFileSync(path.join(root,file),"utf8")});
const a=load("navigation/transfers","docs/design/svg/icons/navigation/transfers.svg");
const b=load("navigation/storage","docs/design/svg/icons/navigation/storage.svg");
const c=load("files/folder","docs/design/svg/icons/files/folder.svg");
const xmlOk=s=>{
 const r=spawnSync("python3",["-c","import sys,xml.etree.ElementTree as E;E.fromstring(sys.stdin.read())"],{input:s,encoding:"utf8"});
 assert.equal(r.status,0,r.stderr);
};
test("self-contained multi-layer SVG renders as parseable XML",()=>{
 const xml=mix.compose([a,b,c],"#F8FAFC");xmlOk(xml);
 assert.match(xml,/id="mix-layer-2"/);
 assert.match(xml,/data-chunks-id="navigation\/storage"/);
 assert.doesNotMatch(xml,/<image\b|data:image|<script\b/);
});
test("independent geometry, alpha, namespaced titles and gradients",()=>{
 const out=mix.compose([{...a,x:25,y:-10,scale:130,rotate:45,opacity:70},{...b,opacity:30}]);xmlOk(out);
 assert.match(out,/translate\(250 -100\) rotate\(45\) scale\(1.3\)/);
 assert.match(out,/opacity="0.7"/);assert.match(out,/opacity="0.3"/);
 assert.match(out,/id="mix0-title"/);assert.match(out,/id="mix1-title"/);
 const source='<svg viewBox="0 0 64 64"><defs><linearGradient id="g"><stop offset="0" stop-color="#fff"/></linearGradient></defs><path d="M0 0H10V10Z" fill="url(#g)"/></svg>';
 const two=mix.compose([{id:"a/a",svg:source},{id:"b/b",svg:source}]);xmlOk(two);
 assert.match(two,/url\(#mix0-g\)/);assert.match(two,/url\(#mix1-g\)/);
});
test("rejects executable content, remote references and bitmap embeddings",()=>{
 for(const fragment of ['<script>go()</script>','<image href="data:image/png;base64,x"/>','<foreignObject/>','<path onclick="go()"/>','<path fill="url(http://bad.invalid/a)"/>']){
  assert.throws(()=>mix.compose([{id:"files/folder",svg:'<svg viewBox="0 0 64 64">'+fragment+'</svg>'}]));
 }
});
test("standalone SVG carries a recoverable inert JSON layer recipe",()=>{
 const source=mix.compose([{...a,x:17,scale:145,opacity:80},{...c,y:-9}],"#FFD93D");
 const script='import sys,json,xml.etree.ElementTree as E;root=E.fromstring(sys.stdin.read());node=root.find("{http://www.w3.org/2000/svg}metadata");assert node is not None and node.attrib["id"]=="chunks-mix-recipe";print(node.text)';
 const p=spawnSync("python3",["-c",script],{input:source,encoding:"utf8"});
 assert.equal(p.status,0,p.stderr);
 const data=JSON.parse(p.stdout);
 assert.deepEqual(data,mix.recipe([{id:a.id,x:17,scale:145,opacity:80},{id:c.id,y:-9}],"#FFD93D"));
 assert.doesNotMatch(source,/<script\b|data:image/i);
});
test("roundtrip recipes, duplicate/unknown/reckless controls rejected",()=>{
 const ids=[a.id,b.id,c.id];
 const recipe=mix.recipe([{id:a.id,x:13,opacity:70},{id:c.id,y:-20,scale:135}],"#0B0F17");
 assert.deepEqual(mix.normalizeRecipe(JSON.parse(JSON.stringify(recipe)),ids),recipe);
 assert.throws(()=>mix.normalizeRecipe({schema:1,layers:[{id:"../../escape"}]},ids));
 assert.throws(()=>mix.normalizeRecipe({schema:1,layers:[{id:a.id,scale:500}]},ids));
 assert.throws(()=>mix.normalizeRecipe({schema:1,layers:[{id:a.id},{id:a.id}]},ids));
 assert.throws(()=>mix.normalizeRecipe({schema:1,background:"red",layers:[{id:a.id}]},ids));
 assert.throws(()=>mix.compose([]));
});
