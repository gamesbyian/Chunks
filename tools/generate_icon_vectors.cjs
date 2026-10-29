#!/usr/bin/env node
// Dependency-free, deterministic conversion of selected Chunks library icons
// (docs/design/svg/icons/**, 64-unit grid, stroke/fill paths, rects, circles)
// into Android VectorDrawables. The SVGs stay the only editable artwork.
//   node tools/generate_icon_vectors.cjs          regenerate
//   node tools/generate_icon_vectors.cjs --check  fail if any output is stale
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

const root = path.resolve(__dirname, "..");
// drawable name -> library source (or {source, onDark}). onDark swaps the
// near-black outline for off-white so the icon reads on dark surfaces; fills
// are unchanged. Add entries here, then regenerate.
const ICONS = {
  chunks_ic_play: "docs/design/svg/icons/transfers/play.svg",
  chunks_ic_stop: "docs/design/svg/icons/transfers/stop.svg",
  chunks_ic_remove: "docs/design/svg/icons/transfers/remove-transfer.svg",
  chunks_ic_folder: "docs/design/svg/icons/files/folder.svg",
  chunks_ic_add_torrent: "docs/design/svg/icons/transfers/add-torrent.svg",
  chunks_ic_magnet: "docs/design/svg/icons/transfers/magnet-link.svg",
  chunks_ic_copy: "docs/design/svg/icons/transfers/copy.svg",
  chunks_ic_move: "docs/design/svg/icons/transfers/move.svg",
  chunks_ic_phone: "docs/design/svg/icons/connectivity/phone.svg",
  chunks_ic_server: "docs/design/svg/icons/connectivity/server.svg",
  chunks_ic_archive: "docs/design/svg/icons/files/archive-zip.svg",
  chunks_ic_document: "docs/design/svg/icons/files/document.svg",
  chunks_ic_file_video_on_dark: { source: "docs/design/svg/icons/files/video.svg", onDark: true },
  chunks_ic_file_audio_on_dark: { source: "docs/design/svg/icons/files/audio.svg", onDark: true },
  chunks_ic_file_image_on_dark: { source: "docs/design/svg/icons/files/image.svg", onDark: true },
  chunks_ic_file_document_on_dark: { source: "docs/design/svg/icons/files/document.svg", onDark: true },
  chunks_ic_file_archive_on_dark: { source: "docs/design/svg/icons/files/archive-zip.svg", onDark: true },
};

const attrs = s => Object.fromEntries([...s.matchAll(/([:\w-]+)="([^"]*)"/g)].map(m => [m[1], m[2]]));
const num = n => Number(Number(n).toFixed(3)).toString();
const color = c => {
  if (!c || c === "none") return null;
  if (!/^#[0-9a-fA-F]{6}$/.test(c)) throw new Error("Unsupported colour " + c);
  return "#FF" + c.slice(1).toUpperCase();
};
const rectPath = a => {
  const x = +a.x || 0, y = +a.y || 0, w = +a.width, h = +a.height;
  const r = Math.min(+(a.rx || a.ry || 0), w / 2, h / 2);
  if (!r) return `M${num(x)} ${num(y)} H${num(x + w)} V${num(y + h)} H${num(x)} Z`;
  return `M${num(x + r)} ${num(y)} H${num(x + w - r)} A${num(r)} ${num(r)} 0 0 1 ${num(x + w)} ${num(y + r)} ` +
    `V${num(y + h - r)} A${num(r)} ${num(r)} 0 0 1 ${num(x + w - r)} ${num(y + h)} H${num(x + r)} ` +
    `A${num(r)} ${num(r)} 0 0 1 ${num(x)} ${num(y + h - r)} V${num(y + r)} A${num(r)} ${num(r)} 0 0 1 ${num(x + r)} ${num(y)} Z`;
};
const ellipsePath = (cx, cy, rx, ry) =>
  `M${num(cx - rx)} ${num(cy)} A${num(rx)} ${num(ry)} 0 1 1 ${num(cx + rx)} ${num(cy)} ` +
  `A${num(rx)} ${num(ry)} 0 1 1 ${num(cx - rx)} ${num(cy)} Z`;
const circlePath = a => ellipsePath(+a.cx, +a.cy, +a.r, +a.r);

const NEAR_BLACK = "#FF0B0F17", OFF_WHITE = "#FFF8FAFC";
function build(svg, source, onDark) {
  const view = (svg.match(/viewBox="([^"]+)"/) || [])[1];
  if (view !== "0 0 64 64") throw new Error(source + ": expected viewBox 0 0 64 64");
  if (/<(linearGradient|radialGradient|filter|use|text|image|mask|clipPath)\b/.test(svg))
    throw new Error(source + ": unsupported element");
  const group = attrs((svg.match(/<g\b([^>]*)>/) || ["", ""])[1]);
  const shapes = [...svg.matchAll(/<(path|rect|circle|ellipse)\b([^>]*?)\/?>/g)];
  if (!shapes.length) throw new Error(source + ": no shapes");
  const out = shapes.map(([, tag, raw]) => {
    const a = { ...group, ...attrs(raw) };
    const d = tag === "path" ? a.d.replace(/\s+/g, " ").trim() : tag === "rect" ? rectPath(a)
      : tag === "ellipse" ? ellipsePath(+a.cx, +a.cy, +a.rx, +a.ry) : circlePath(a);
    const fill = color(a.fill);
    let stroke = color(a.stroke);
    if (onDark && stroke === NEAR_BLACK) stroke = OFF_WHITE;
    let p = `  <path android:pathData="${d}"`;
    if (fill) p += ` android:fillColor="${fill}"`;
    if (stroke) {
      p += ` android:strokeColor="${stroke}" android:strokeWidth="${num(a["stroke-width"] || 1)}"`;
      if (a["stroke-linecap"]) p += ` android:strokeLineCap="${a["stroke-linecap"]}"`;
      if (a["stroke-linejoin"]) p += ` android:strokeLineJoin="${a["stroke-linejoin"]}"`;
    }
    return p + "/>";
  });
  const hash = crypto.createHash("sha256").update(svg).digest("hex").slice(0, 16);
  return `<?xml version="1.0" encoding="utf-8"?>\n` +
    `<!-- GENERATED from ${source}${onDark ? " (on-dark outline)" : ""} by tools/generate_icon_vectors.cjs (sha256 ${hash}); edit the SVG, then regenerate. -->\n` +
    `<vector xmlns:android="http://schemas.android.com/apk/res/android"\n` +
    `    android:width="24dp" android:height="24dp" android:viewportWidth="64" android:viewportHeight="64">\n` +
    out.join("\n") + `\n</vector>\n`;
}

const check = process.argv.includes("--check");
let stale = 0;
for (const [name, entry] of Object.entries(ICONS)) {
  const { source, onDark = false } = typeof entry === "string" ? { source: entry } : entry;
  const xml = build(fs.readFileSync(path.join(root, source), "utf8"), source, onDark);
  const target = path.join(root, "app/src/main/res/drawable", name + ".xml");
  const current = fs.existsSync(target) ? fs.readFileSync(target, "utf8") : null;
  if (current === xml) continue;
  if (check) { console.error("stale: " + target); stale++; } else fs.writeFileSync(target, xml);
}
if (check && stale) { console.error("Run node tools/generate_icon_vectors.cjs"); process.exit(1); }
console.log(check ? "icon vectors up to date" : "icon vectors generated");
