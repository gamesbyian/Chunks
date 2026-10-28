/* Non-shipping experimental variant workbench for the standalone gallery. */
(function () {
"use strict";
const mixer=window.ChunksSvgMixer;
if(!mixer)throw new Error("Chunks mix engine script must load before the workbench");
const editById=Object.create(null);
let active=null;
let dragging=null;
let previousSelection=[];
const workbench=document.createElement("section");
workbench.className="mix-workbench";
workbench.setAttribute("aria-label","Editable Chunks vector composition");
workbench.innerHTML=
 '<p class="note">Drag the selected layer in the preview, or use the accessible sliders. Export preserves independent SVG vectors and gradients. Four layers maximum.</p>'+
 '<div class="mix-tools"><label for="mix-background">Composition background</label>'+
 '<select id="mix-background"><option value="transparent">Transparent</option><option value="#F8FAFC">Light paper</option><option value="#0B0F17">Near-black</option><option value="#FFD93D">Yellow</option></select>'+
 '<button id="mix-back" type="button">Move layer back</button><button id="mix-forward" type="button">Move layer forward</button>'+
 '<button id="mix-reset" type="button">Reset active layer</button></div>'+
 '<fieldset id="mix-editor"><legend id="mix-edit-label">Layer adjustments</legend><div class="mix-sliders">'+
 ['x','y','scale','rotate','opacity'].map(function(key){
   const label={x:"Horizontal",y:"Vertical",scale:"Scale",rotate:"Rotation",opacity:"Opacity"}[key];
   const ranges={x:[-100,100,1,0],y:[-100,100,1,0],scale:[20,300,5,100],rotate:[-180,180,5,0],opacity:[0,100,5,100]}[key];
   return '<label for="mix-'+key+'">'+label+' <output id="mix-'+key+'-output" for="mix-'+key+'"></output>'+
    '<input type="range" id="mix-'+key+'" data-mix-key="'+key+'" min="'+ranges[0]+'" max="'+ranges[1]+
    '" step="'+ranges[2]+'" value="'+ranges[3]+'"/></label>';
 }).join('')+'</div></fieldset>'+
 '<div class="mix-tools"><button type="button" id="mix-export">Export standalone SVG</button>'+
 '<button type="button" id="mix-save">Save editable recipe</button>'+
 '<button type="button" id="mix-load">Load recipe or SVG</button>'+
 '<input id="mix-recipe-file" type="file" accept=".json,application/json,.svg,image/svg+xml" hidden/></div>'+
 '<p id="mix-message" role="status" aria-live="polite" class="note"></p>';
mix.insertAdjacentElement("afterend",workbench);
// The SVG layer preview is a keyboard-operable surface in addition to the
// sliders. Avoid making the actual SVG/image or dragged layers focus targets.
mix.tabIndex=0;
mix.setAttribute("role","group");
mix.setAttribute("aria-label","Composition canvas. Arrow keys move the selected layer by one percent. Hold Shift for ten percent.");
document.querySelector('label[for="opacity"]').hidden=true;
document.querySelector("#opacity").hidden=true;
const style=document.createElement("style");
style.textContent=
 '.mix{cursor:grab;outline-offset:4px}.mix:active{cursor:grabbing}.mix:focus-visible{outline:4px solid #FFD93D}'+
 '.mix .mix-layer{position:absolute;inset:0;transform-origin:center;pointer-events:none}'+
 '.mix .mix-layer img{position:absolute;inset:7%;width:86%;height:86%;object-fit:contain}'+
 '.mix-tools{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:14px 0}'+
 '.mix-tools button{min-height:48px}.mix-tools select{min-height:48px}'+
 '.mix-sliders{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(165px,100%),1fr));gap:12px;min-width:0;max-width:100%}'+
 '.mix-sliders label{display:grid;gap:4px;font-size:14px;min-width:0;overflow-wrap:anywhere}'+
 '.mix-sliders input{width:100%;min-width:0;max-width:100%;min-height:44px}.mix-sliders output{color:#22d97a}'+
 '#mix-editor{min-width:0;max-width:100%;border:1px solid #6b7280;border-radius:12px;padding:12px;margin:8px 0}'+
 '#mix-editor legend{font-weight:750;overflow-wrap:anywhere;max-width:100%}.tray article.mix-active{outline:3px solid #22d97a;outline-offset:2px}';
document.head.append(style);
const propKeys=["x","y","scale","rotate","opacity"];
function sourceById(id){return assets.find(a=>a.id===id);}
function current(){if(!active)return null;return editById[active];}
function status(msg,error=false){
 const p=document.querySelector("#mix-message");
 p.textContent=msg;p.style.color=error?"#ffabab":"#bed5e4";
}
function sync(){
 const added=chosen.filter(id=>!previousSelection.includes(id));
 for(const id of Object.keys(editById))if(!chosen.includes(id))delete editById[id];
 for(const id of chosen)if(!editById[id])editById[id]=mixer.edit({});
 // Newly selected assets become editable immediately, even if another layer
 // was previously active. Reordering existing layers preserves active choice.
 if(added.length)active=added.at(-1);
 else if(!chosen.includes(active))active=chosen.at(-1)||null;
 previousSelection=chosen.slice();
}
function updateLayerVisuals(){
 mix.replaceChildren();
 for(const id of chosen){
  const source=sourceById(id),e=editById[id];
  const div=document.createElement("div");div.className="mix-layer";
  div.style.transform="translate("+e.x+"%, "+e.y+"%) rotate("+e.rotate+"deg) scale("+(e.scale/100)+")";
  div.style.opacity=String(e.opacity/100);
  const image=createImage(source);image.loading="eager";
  div.append(image);mix.append(div);
 }
 const bg=document.querySelector("#mix-background").value;
 mix.style.background=bg==="transparent"?"":bg;
 // Pan normally through an empty preview, but permit direct dragging once
 // there is something to manipulate. CSS-only touch-action:none traps mobile scroll.
 mix.style.touchAction=chosen.length?"none":"pan-y";
}
function updatePanel(){
 const e=current();
 document.querySelector("#mix-editor").hidden=!e;
 document.querySelector("#mix-edit-label").textContent=e?"Editing: "+active:"Layer adjustments";
 if(e){
  for(const key of propKeys){
   const input=document.querySelector("#mix-"+key);
   const output=document.querySelector("#mix-"+key+"-output");
   input.value=String(e[key]);
   output.textContent=String(e[key])+(key==="rotate"?"°":"%");
  }
 }
 document.querySelector("#mix-back").disabled=!e||chosen.indexOf(active)===0;
 document.querySelector("#mix-forward").disabled=!e||chosen.indexOf(active)===chosen.length-1;
 document.querySelector("#mix-reset").disabled=!e;
 document.querySelector("#mix-save").disabled=!e;
 document.querySelector("#mix-export").disabled=!e;
 for(const card of comp.querySelectorAll('article[data-chunks-asset-id]')){
  const selected=card.dataset.chunksAssetId===active;
  card.classList.toggle("mix-active",selected);
  const button=card.querySelector("button");
  if(button)button.setAttribute("aria-pressed",String(selected));
 }
}
const oldRender=renderTray;
renderTray=function(){
 oldRender();sync();
 comp.querySelectorAll("article").forEach((card,i)=>{
  const id=chosen[i];card.dataset.chunksAssetId=id;
  const button=document.createElement("button");
  button.type="button";button.textContent="Edit layer";button.setAttribute("aria-pressed",String(id===active));
  button.setAttribute("aria-label","Edit "+id);
  button.addEventListener("click",()=>{active=id;updatePanel();});
  card.append(button);
 });
 updateLayerVisuals();updatePanel();
};
function move(delta){
 if(!active)return;
 const index=chosen.indexOf(active),next=index+delta;
 if(next<0||next>=chosen.length)return;
 chosen.splice(index,1);chosen.splice(next,0,active);renderTray();
}
function download(filename,content,type){
 const blob=new Blob([content],{type});
 const href=URL.createObjectURL(blob);
 const a=document.createElement("a");
 a.href=href;a.download=filename;document.body.append(a);a.click();a.remove();
 setTimeout(()=>URL.revokeObjectURL(href),1500);
}
function recipe(){
 return mixer.recipe(chosen.map(id=>({id,...editById[id]})),document.querySelector("#mix-background").value);
}
for(const input of workbench.querySelectorAll("input[data-mix-key]")){
 input.addEventListener("input",()=>{
  const e=current();if(!e)return;
  e[input.dataset.mixKey]=Number(input.value);
  updateLayerVisuals();updatePanel();
 });
}
document.querySelector("#mix-background").addEventListener("change",updateLayerVisuals);
document.querySelector("#mix-back").addEventListener("click",()=>move(-1));
document.querySelector("#mix-forward").addEventListener("click",()=>move(1));
document.querySelector("#mix-reset").addEventListener("click",()=>{
 if(!active)return;editById[active]=mixer.edit({});updateLayerVisuals();updatePanel();
});
mix.addEventListener("pointerdown",event=>{
 if(!active||!event.isPrimary)return;
 const bounds=mix.getBoundingClientRect(),e=current();
 if(bounds.width<=0||bounds.height<=0)return;
 dragging={id:active,pointer:event.pointerId,x:event.clientX,y:event.clientY,
   initialX:e.x,initialY:e.y,w:bounds.width,h:bounds.height};
 mix.setPointerCapture(event.pointerId);
});
mix.addEventListener("pointermove",event=>{
 if(!dragging||dragging.pointer!==event.pointerId||!editById[dragging.id])return;
 const e=editById[dragging.id];active=dragging.id;
 e.x=Math.max(-100,Math.min(100,Math.round(dragging.initialX+100*(event.clientX-dragging.x)/dragging.w)));
 e.y=Math.max(-100,Math.min(100,Math.round(dragging.initialY+100*(event.clientY-dragging.y)/dragging.h)));
 updateLayerVisuals();updatePanel();
});
function finishDrag(event){
 if(dragging&&dragging.pointer===event.pointerId)dragging=null;
}
mix.addEventListener("pointerup",finishDrag);
mix.addEventListener("pointercancel",finishDrag);
mix.addEventListener("lostpointercapture",finishDrag);
mix.addEventListener("keydown",event=>{
 if(!active||event.target!==mix)return;
 const key=event.key;
 if(!["ArrowLeft","ArrowRight","ArrowUp","ArrowDown"].includes(key))return;
 event.preventDefault();
 const e=current(),step=event.shiftKey?10:1;
 if(key==="ArrowLeft")e.x=Math.max(-100,e.x-step);
 if(key==="ArrowRight")e.x=Math.min(100,e.x+step);
 if(key==="ArrowUp")e.y=Math.max(-100,e.y-step);
 if(key==="ArrowDown")e.y=Math.min(100,e.y+step);
 updateLayerVisuals();updatePanel();
 status("Moved "+active+" to horizontal "+e.x+"%, vertical "+e.y+"%.");
});
document.querySelector("#mix-save").addEventListener("click",()=>{
 if(!chosen.length)return;
 download("chunks-svg-mix.recipe.json",JSON.stringify(recipe(),null,2)+"\n","application/json");
 status("Editable mix recipe saved.");
});
document.querySelector("#mix-load").addEventListener("click",()=>document.querySelector("#mix-recipe-file").click());
document.querySelector("#mix-recipe-file").addEventListener("change",async event=>{
 const f=event.target.files?.[0];if(!f)return;
 try{
  const isSvg=f.name.toLowerCase().endsWith(".svg")||f.type==="image/svg+xml";
  if(f.size>(isSvg?6000000:32768))throw new Error("File exceeds allowed size");
  const contents=await f.text();
  let recipeData;
  if(isSvg){
   // Only parse inert XML metadata. Never insert uploaded SVG markup into DOM.
   if(/<!DOCTYPE|<!ENTITY|<script\b|<foreignObject\b/i.test(contents)){
    throw new Error("Unsupported XML constructs");
   }
   const parsed=new DOMParser().parseFromString(contents,"image/svg+xml");
   if(parsed.querySelector("parsererror"))throw new Error("Malformed SVG");
   const metadata=parsed.getElementById("chunks-mix-recipe");
   if(!metadata || metadata.tagName.toLowerCase()!=="metadata"){
    throw new Error("SVG has no Chunks edit recipe");
   }
   recipeData=JSON.parse(metadata.textContent);
  }else recipeData=JSON.parse(contents);
  const data=mixer.normalizeRecipe(recipeData,assets.map(a=>a.id));
  chosen.splice(0);for(const key of Object.keys(editById))delete editById[key];
  for(const layer of data.layers){chosen.push(layer.id);editById[layer.id]=mixer.edit(layer);}
  active=chosen.at(-1);document.querySelector("#mix-background").value=data.background;
  refresh();renderTray();status("Editable composition restored.");
 }catch(error){status("Recipe rejected: "+error.message,true);}
 finally{event.target.value="";}
});
document.querySelector("#mix-export").addEventListener("click",async event=>{
 if(!chosen.length)return;
 event.currentTarget.disabled=true;status("Assembling source-vector layers…");
 try{
  const selection=recipe();
  const contents=await Promise.all(selection.layers.map(async layer=>{
   const source=sourceById(layer.id);
   if(!source||source.src.startsWith("/")||source.src.includes("..")||!source.src.endsWith(".svg")){
    throw new Error("Unrecognized SVG path");
   }
   const response=await fetch(source.src,{credentials:"same-origin"});
   if(!response.ok)throw new Error("Failed to read "+source.src+": HTTP "+response.status);
   const svg=await response.text();
   return {...layer,svg};
  }));
  const xml=mixer.compose(contents,selection.background);
  download("chunks-custom-composition.svg",xml,"image/svg+xml");
  status("Standalone SVG exported. Original variants remain unchanged.");
 }catch(error){
  status("Export unavailable: "+error.message+". Serve the gallery through HTTP(S) with its SVG files.",true);
 }finally{event.currentTarget.disabled=false;}
});
renderTray();
})();
