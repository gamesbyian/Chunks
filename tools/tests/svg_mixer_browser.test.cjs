"use strict";
const test=require("node:test");
const assert=require("node:assert/strict");
const fs=require("node:fs");
const path=require("node:path");
const {JSDOM}=require("jsdom");
const root=path.resolve(__dirname,"../..");
const gallery=fs.readFileSync(path.join(root,"docs/design/svg/gallery.html"),"utf8");
const engine=fs.readFileSync(path.join(root,"docs/design/svg/mix-engine.js"),"utf8");
const ui=fs.readFileSync(path.join(root,"docs/design/svg/mix-ui.js"),"utf8");
function open(){
 const dom=new JSDOM(gallery,{url:"https://example.test/gallery.html",runScripts:"outside-only",pretendToBeVisual:true});
 const inline=gallery.match(/<script>\s*"use strict";([\s\S]*?)<\/script>/);
 assert.ok(inline,"inline static gallery script missing");
 dom.window.eval('"use strict";\n'+inline[1]);
 dom.window.eval(engine);
 dom.window.eval(ui);
 return dom;
}
function search(d,id){
 const input=d.querySelector("#query");input.value=id;
 input.dispatchEvent(new d.defaultView.Event("input",{bubbles:true}));
 return [...d.querySelectorAll('#grid button[data-asset-id]')].find(x=>x.dataset.assetId===id);
}
test("gallery boots and selects an SVG layer without showing all 815 cards",()=>{
 const dom=open(),d=dom.window.document;
 assert.ok(d.querySelector("#mix-export"));
 assert.equal(d.querySelectorAll("#grid .item").length,96);
 const button=search(d,"navigation/transfers");assert.ok(button);button.click();
 assert.equal(d.querySelector("#tray").hidden,false);
 assert.equal(d.querySelectorAll("#mix .mix-layer").length,1);
 assert.equal(d.querySelector("#mix-editor").hidden,false);
 assert.match(d.querySelector("#mix-edit-label").textContent,/navigation\/transfers/);
 dom.window.close();
});
test("composition sliders, backgrounds and reordering produce distinct editable layers",()=>{
 const dom=open(),d=dom.window.document;
 search(d,"navigation/transfers").click();
 search(d,"files/folder").click();
 assert.equal(d.querySelectorAll("#mix .mix-layer").length,2);
 const scale=d.querySelector("#mix-scale");scale.value="150";
 scale.dispatchEvent(new dom.window.Event("input",{bubbles:true}));
 assert.match(d.querySelectorAll("#mix .mix-layer")[1].style.transform,/scale\(1.5\)/);
 const bg=d.querySelector("#mix-background");bg.value="#0B0F17";
 bg.dispatchEvent(new dom.window.Event("change",{bubbles:true}));
 assert.ok(d.querySelector("#mix").style.background);
 d.querySelector("#mix-back").click();
 assert.match(d.querySelectorAll("#mix .mix-layer")[0].querySelector("img").getAttribute("src"),/files\/folder/);
 dom.window.close();
});
test("an empty layer is safe and invalid recipes are rejected",async()=>{
 const dom=open(),d=dom.window.document;
 assert.equal(d.querySelector("#tray").hidden,true);
 search(d,"navigation/transfers").click();
 const id="files/folder";
 const valid=dom.window.ChunksSvgMixer.normalizeRecipe({schema:1,layers:[{id}]},
   ["navigation/transfers",id]);
 assert.equal(valid.layers[0].id,id);
 assert.throws(()=>dom.window.ChunksSvgMixer.normalizeRecipe({schema:1,layers:[{id:"../../../evil"}]},
   ["navigation/transfers"]));
 const clear=d.querySelector("#clear");clear.click();
 assert.equal(d.querySelector("#tray").hidden,true);
 dom.window.close();
});
