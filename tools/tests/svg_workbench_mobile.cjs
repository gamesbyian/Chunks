"use strict";
// Real Chromium browser acceptance. Evidence is separate from Android certification.
const assert=require("node:assert/strict"),fs=require("node:fs"),path=require("node:path"),http=require("node:http");
const {chromium}=require("playwright");
const root=path.resolve(__dirname,"../../docs/design/svg");
const out=path.resolve(process.argv[2]||"generated/svg-workbench-mobile");
fs.mkdirSync(out,{recursive:true});
const mime={".svg":"image/svg+xml",".html":"text/html; charset=utf-8",".js":"text/javascript; charset=utf-8",".json":"application/json; charset=utf-8"};
const server=http.createServer((req,res)=>{
 let name;
 try{name=decodeURIComponent(new URL(req.url,"http://localhost").pathname);}
 catch{return res.writeHead(400).end();}
 if(!name.startsWith("/")||name.includes("..")||name.includes("\\"))return res.writeHead(403).end();
 const file=path.resolve(root,"."+name);
 if(!file.startsWith(root+path.sep)||!mime[path.extname(file)])return res.writeHead(403).end();
 fs.readFile(file,(error,content)=>{
  if(error)return res.writeHead(404).end();
  res.writeHead(200,{"content-type":mime[path.extname(file)],"cache-control":"no-store","x-content-type-options":"nosniff"});
  res.end(content);
 });
});
async function dimensions(page,stage){
 const d=await page.evaluate(()=>({inner:innerWidth,client:document.documentElement.clientWidth,scroll:document.documentElement.scrollWidth}));
 assert.ok(d.scroll<=d.client+2,stage+" horizontal overflow: "+JSON.stringify(d));return d;
}
async function main(){
 await new Promise(resolve=>server.listen(0,"127.0.0.1",resolve));
 let browser;
 const records=[];
 try{
  browser=await chromium.launch({headless:true,args:["--no-sandbox"]});
  for(const cfg of [{width:320,height:700},{width:412,height:892},{width:791,height:900}]){
   const context=await browser.newContext({viewport:cfg,deviceScaleFactor:1,isMobile:true,hasTouch:true,acceptDownloads:true,reducedMotion:"reduce"});
   const page=await context.newPage(),errors=[];
   page.on("pageerror",e=>errors.push(e.message));
   await page.goto("http://127.0.0.1:"+server.address().port+"/gallery.html",{waitUntil:"domcontentloaded"});
   await page.waitForFunction(()=>document.querySelectorAll("#grid .item").length===96);
   assert.equal(await page.locator("#mix-export").isDisabled(),true);
   const initial=await dimensions(page,"initial "+cfg.width);
   await page.screenshot({path:path.join(out,"gallery-"+cfg.width+".png"),animations:"disabled"});
   await page.locator("#load-more").click();
   assert.equal(await page.locator("#grid .item").count(),192);
   await page.locator("#query").fill("navigation/transfers");
   await page.locator('#grid button[data-asset-id="navigation/transfers"]').click();
   assert.equal(await page.locator("#mix .mix-layer").count(),1);
   const canvas=page.locator("#mix"),x=page.locator("#mix-x"),y=page.locator("#mix-y");
   await canvas.scrollIntoViewIfNeeded();await canvas.focus();
   await page.keyboard.press("ArrowRight");
   assert.equal(Number(await x.inputValue()),1);
   await page.keyboard.press("Shift+ArrowDown");
   assert.equal(Number(await y.inputValue()),10);
   assert.match(await page.locator("#mix-message").innerText(),/Moved navigation\/transfers/);
   const box=await canvas.boundingBox();assert.ok(box&&box.width>80&&box.height>80);
   await page.mouse.move(box.x+box.width/2,box.y+box.height/2);
   await page.mouse.down();
   await page.mouse.move(box.x+box.width/2+25,box.y+box.height/2,{steps:5});
   await page.mouse.up();
   assert.ok(Number(await x.inputValue())>1,"Pointer drag must move a layer");
   const edited=await dimensions(page,"edited "+cfg.width);
   await canvas.scrollIntoViewIfNeeded();
   await page.screenshot({path:path.join(out,"mixer-"+cfg.width+".png"),animations:"disabled"});
   await page.locator("#mix-background").selectOption("#0B0F17");
   await canvas.scrollIntoViewIfNeeded();
   await page.screenshot({path:path.join(out,"dark-"+cfg.width+".png"),animations:"disabled"});
   if(cfg.width===412){
    const event=page.waitForEvent("download");
    await page.locator("#mix-export").click();
    const download=await event;
    assert.equal(download.suggestedFilename(),"chunks-custom-composition.svg");
    const svg=fs.readFileSync(await download.path(),"utf8");
    assert.match(svg,/<metadata id="chunks-mix-recipe"/);
    assert.match(svg,/data-chunks-id="navigation\/transfers"/);
    assert.doesNotMatch(svg,/<image\b|<script\b|data:image/i);
    fs.writeFileSync(path.join(out,"verified-composition.svg"),svg);
   }
   if(cfg.width===320){
    await page.evaluate(()=>{document.documentElement.style.fontSize="200%";});
    const expanded=await dimensions(page,"200% root text");
    await canvas.scrollIntoViewIfNeeded();
    await page.screenshot({path:path.join(out,"mixer-text-200pct-320.png"),animations:"disabled"});
    records.push({viewport:cfg,scale:"200% root font",dimensions:expanded});
   }
   const buttons=await page.locator(".controls button:visible, .mix-tools button:visible").evaluateAll(list=>list.map(el=>({label:el.textContent.trim(),height:el.getBoundingClientRect().height})));
   for(const button of buttons)assert.ok(button.height>=44,"Touch target too small: "+JSON.stringify(button));
   assert.deepEqual(errors,[],"Uncaught page JavaScript errors");
   records.push({viewport:cfg,initial,edited,keyboard:true,pointerDrag:true,touchTargets:buttons.length});
   await context.close();
   process.stdout.write("PASS Chromium "+cfg.width+"x"+cfg.height+"\n");
  }
  fs.writeFileSync(path.join(out,"report.json"),JSON.stringify({scope:"Chromium browser only; no native or device acceptance",records},null,2)+"\n");
  console.log("SVG workbench real browser PASS");
 }finally{
  if(browser)await browser.close();
  await new Promise(resolve=>server.close(resolve));
 }
}
main().catch(e=>{console.error(e.stack||e);process.exitCode=1;});
