/* Chunks SVG layer composer. Independent from the approved Android launcher. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.ChunksSvgMixer = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const escape = value => String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;")
    .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  const rounded = value => Number(Number(value).toFixed(4)).toString();
  const between = (value, min, max) => Number.isFinite(Number(value))
    && Number(value) >= min && Number(value) <= max;
  const allowedNames = /^[A-Za-z0-9][A-Za-z0-9._/-]{0,190}$/;
  const sourceLimit = 1500000;
  function edit(input) {
    const a = input || {};
    const result = {
      x: Number(a.x ?? 0), y: Number(a.y ?? 0),
      scale: Number(a.scale ?? 100), rotate: Number(a.rotate ?? 0),
      opacity: Number(a.opacity ?? 100)
    };
    if (!between(result.x, -100, 100) || !between(result.y, -100, 100)
      || !between(result.scale, 20, 300) || !between(result.rotate, -180, 180)
      || !between(result.opacity, 0, 100)) {
      throw new Error("Layer edit outside supported limits");
    }
    return result;
  }
  function validateId(id) {
    if (typeof id !== "string" || !allowedNames.test(id)
        || id.includes("..") || id.startsWith("/")) throw new Error("Invalid asset ID");
    return id;
  }
  function normalizeRecipe(recipe, allowedIds) {
    if (!recipe || recipe.schema !== 1 || !Array.isArray(recipe.layers)
        || !recipe.layers.length || recipe.layers.length > 4) throw new Error("Invalid layer recipe");
    const ids = new Set();
    const approved = new Set(allowedIds);
    const layers = recipe.layers.map(layer => {
      const id = validateId(layer.id);
      if (!approved.has(id) || ids.has(id)) throw new Error("Unrecognized or duplicate variant ID: " + id);
      ids.add(id);
      return {id, ...edit(layer)};
    });
    const background = recipe.background ?? "transparent";
    if (background !== "transparent" && !/^#[0-9a-fA-F]{6}$/.test(background)) {
      throw new Error("Invalid background");
    }
    return {schema: 1, background, layers};
  }
  function renameReferences(inner, prefix) {
    const ids = new Map();
    let out = inner.replace(/\bid="([^"]+)"/g, (whole, key) => {
      if (!/^[A-Za-z_][\w:.-]*$/.test(key)) throw new Error("Unsafe SVG ID");
      ids.set(key, prefix + key);
      return 'id="' + prefix + key + '"';
    });
    for (const [key, value] of ids) {
      const safeKey = key.replace(/\./g, "\\.");
      out = out.replace(new RegExp('url\\(#' + safeKey + '\\)', 'g'), 'url(#' + value + ')');
      out = out.replace(new RegExp('((?:href|xlink:href)=")#' + safeKey + '(")', 'g'),
        '$1#' + value + '$2');
    }
    out = out.replace(/\baria-labelledby="([^"]+)"/g, (whole, text) =>
      'aria-labelledby="' + text.split(/\s+/).map(id => ids.get(id) || id).join(" ") + '"');
    return out;
  }
  function sourceSVG(content, index) {
    if (typeof content !== "string" || !content.length || content.length > sourceLimit) {
      throw new Error("Missing or excessive SVG source content");
    }
    if (/<\s*(?:script|image|foreignObject|iframe|object|embed)\b/i.test(content)
        || /\bon[a-z]+\s*=/i.test(content)
        || /<!DOCTYPE|<!ENTITY|<\?xml-stylesheet/i.test(content)
        || /\b(?:href|xlink:href)\s*=\s*["'](?:https?:|data:|\/\/)/i.test(content)
        || /url\(\s*["']?(?:https?:|data:|\/\/)/i.test(content)) {
      throw new Error("Unsafe or externally linked SVG content");
    }
    const opening = content.match(/<svg\b([^>]*)>/i);
    const end = content.lastIndexOf("</svg>");
    if (!opening || end < opening.index) throw new Error("SVG source lacks a root element");
    const view = opening[1].match(/\bviewBox\s*=\s*"([^"]+)"/);
    if (!view || !/^\s*-?[\d.]+\s+-?[\d.]+\s+[\d.]+\s+[\d.]+\s*$/.test(view[1])) {
      throw new Error("Invalid SVG viewBox");
    }
    const inner = content.slice(opening.index + opening[0].length, end);
    return {viewBox: view[1], inner: renameReferences(inner, "mix" + index + "-")};
  }
  function compose(layers, background = "transparent") {
    if (!Array.isArray(layers) || layers.length < 1 || layers.length > 4) {
      throw new Error("SVG export supports one through four layers");
    }
    const validated = normalizeRecipe({
      schema: 1, background, layers: layers.map(({id, ...rest}) => ({id, ...rest}))
    }, layers.map(l => l.id));
    let result = '<?xml version="1.0" encoding="UTF-8"?>\n';
    result += '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 1000" ';
    result += 'role="img" aria-labelledby="mix-title"><title id="mix-title">Chunks SVG layer composition</title>';
    result += '<desc>Experimental vector composition; source variants remain separate and unapproved.</desc>';
    // Keeping the validated layer recipe inside the exported SVG makes it
    // re-editable without a second file. This is inert XML metadata, never JS.
    result += '<metadata id="chunks-mix-recipe" data-version="1">'
      + escape(JSON.stringify(validated)) + '</metadata>';
    if (validated.background !== "transparent") {
      result += '<rect width="1000" height="1000" fill="' + escape(validated.background) + '"/>';
    }
    validated.layers.forEach((layer, i) => {
      const source = sourceSVG(layers[i].svg, i);
      const transform = 'translate(500 500) translate(' + rounded(layer.x * 10) + ' '
        + rounded(layer.y * 10) + ') rotate(' + rounded(layer.rotate)
        + ') scale(' + rounded(layer.scale / 100) + ') translate(-500 -500)';
      result += '<g id="mix-layer-' + i + '" opacity="' + rounded(layer.opacity / 100)
        + '" transform="' + transform + '" data-chunks-id="' + escape(layer.id) + '">';
      result += '<svg x="70" y="70" width="860" height="860" viewBox="'
        + escape(source.viewBox) + '" preserveAspectRatio="xMidYMid meet">'
        + source.inner + '</svg></g>';
    });
    return result + '</svg>\n';
  }
  function recipe(layers, background) {
    return normalizeRecipe({schema: 1, background, layers}, layers.map(x => x.id));
  }
  return {compose, recipe, normalizeRecipe, edit, sourceSVG};
});
