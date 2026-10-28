"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const {spawnSync} = require("node:child_process");
const mixer = require("../../docs/design/svg/mix-engine.js");
const ROOT = path.resolve(__dirname, "../..");
const SOURCES = [
  ["navigation/transfers", "docs/design/svg/icons/navigation/transfers.svg"],
  ["navigation/storage", "docs/design/svg/icons/navigation/storage.svg"],
  ["files/folder", "docs/design/svg/icons/files/folder.svg"]
];
const layers = SOURCES.map(([id, f]) => ({id, svg: fs.readFileSync(path.join(ROOT, f), "utf8")}));
test("makes a well-formed native vector document with multiple sources", () => {
  const xml = mixer.compose(layers, "#F8FAFC");
  assert.match(xml, /viewBox="0 0 1000 1000"/);
  assert.match(xml, /id="mix-layer-2"/);
  assert.match(xml, /data-chunks-id="navigation\/storage"/);
  assert.doesNotMatch(xml, /<image\b|data:image|<script\b/);
  const proc = spawnSync("python3", ["-c",
    "import sys,xml.etree.ElementTree as E;E.fromstring(sys.stdin.read())"], {
    input: xml, encoding: "utf8"
  });
  assert.equal(proc.status, 0, proc.stderr);
});
test("exports offsets, opacity, rotation and separate title IDs", () => {
  const xml = mixer.compose([
    {...layers[0], x: 25, y: -10, scale: 130, rotate: 45, opacity: 70},
    {...layers[1], opacity: 30}
  ]);
  assert.match(xml, /translate\(250 -100\) rotate\(45\) scale\(1.3\)/);
  assert.match(xml, /opacity="0.7"/);
  assert.match(xml, /opacity="0.3"/);
  assert.match(xml, /id="mix0-title"/);
  assert.match(xml, /id="mix1-title"/);
  assert.match(xml, /aria-labelledby="mix1-title"/);
});
test("rejects unsafe source content and remote image links", () => {
  for (const fragment of [
    "<image href='data:image/png;base64,abc'/>",
    "<script>alert(1)</script>",
    '<foreignObject></foreignObject>',
    '<path onclick="go()" d="M0 0"/>',
    '<path fill="url(http://unsafe.invalid/a)" d="M0 0"/>'
  ]) {
    assert.throws(() => mixer.compose([{id: "files/folder",
      svg: '<svg viewBox="0 0 64 64">' + fragment + '</svg>'}]));
  }
});
test("recipes reject duplicates, unknown IDs and nonsensical values", () => {
  const known = SOURCES.map(x => x[0]);
  assert.throws(() => mixer.normalizeRecipe({schema:1, layers:[{id:"../../escape"}]}, known));
  assert.throws(() => mixer.normalizeRecipe({schema:1, layers:[{id:known[0],scale:500}]}, known));
  assert.throws(() => mixer.normalizeRecipe({schema:1,
    layers:[{id:known[0]},{id:known[0]}]}, known));
  assert.throws(() => mixer.normalizeRecipe({schema:1,
    background:"red", layers:[{id:known[0]}]}, known));
  assert.throws(() => mixer.compose([]));
});
test("recipe roundtrip retains selected IDs and editable parameters", () => {
  const output = mixer.recipe([{id:"navigation/transfers",x:13,rotate:15,opacity:70},
    {id:"files/folder",y:-20,scale:135}], "#0B0F17");
  assert.deepEqual(mixer.normalizeRecipe(JSON.parse(JSON.stringify(output)),
    SOURCES.map(x=>x[0])), output);
});
test("renames duplicate internal gradients and clipping identifiers", () => {
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">'
    + '<defs><linearGradient id="gradient"><stop offset="0" stop-color="#fff"/></linearGradient></defs>'
    + '<path d="M0 0H64V64Z" fill="url(#gradient)"/></svg>';
  const out = mixer.compose([{id:"a/a",svg},{id:"b/b",svg}]);
  assert.match(out,/id="mix0-gradient"/);
  assert.match(out,/id="mix1-gradient"/);
  assert.match(out,/url\(#mix0-gradient\)/);
  assert.match(out,/url\(#mix1-gradient\)/);
  const run=spawnSync("python3",["-c",
    "import sys,xml.etree.ElementTree as E;E.fromstring(sys.stdin.read())"],
    {input:out,encoding:"utf8"});
  assert.equal(run.status,0,run.stderr);
});
