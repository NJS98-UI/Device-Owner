// Merge multiple res/ dirs (lowest -> highest priority) into one output dir.
// values* dirs: parse top-level <resources> children, dedupe by tag+name, later wins.
// other dirs: file copy, later overwrites.
import fs from 'fs';
import path from 'path';

const args = process.argv.slice(2);
const sep = args.indexOf('--out');
const dirs = args.slice(0, sep).map(s => path.resolve(s));
const outRoot = path.resolve(args[sep + 1]);
fs.rmSync(outRoot, { recursive: true, force: true });

function parseTopLevel(xml) {
  xml = xml.replace(/<!--[\s\S]*?-->/g, '');
  const m = /<resources[^>]*>/.exec(xml);
  if (!m) return [];
  const body = xml.slice(m.index + m[0].length, xml.lastIndexOf('</resources>'));
  const out = [];
  let i = 0;
  while (i < body.length) {
    if (body[i] !== '<') { i++; continue; }
    const tm = /^<([A-Za-z0-9_]+)/.exec(body.slice(i));
    if (!tm) { i++; continue; }
    const tag = tm[1];
    // end of open tag (quote-aware)
    let k = i, close = -1;
    while (k < body.length) {
      const ch = body[k];
      if (ch === '"') { const q = body.indexOf('"', k + 1); if (q < 0) break; k = q + 1; continue; }
      if (ch === '>') { close = k; break; }
      k++;
    }
    if (close < 0) break;
    const head = body.slice(i, close + 1);
    if (head.endsWith('/>')) {
      out.push(body.slice(i, close + 1)); i = close + 1; continue;
    }
    const re = new RegExp('</' + tag + '\\s*>');
    const rest = body.slice(close + 1);
    const rm = re.exec(rest);
    if (!rm) break;
    out.push(body.slice(i, close + 1 + rm.index + rm[0].length));
    i = close + 1 + rm.index + rm[0].length;
  }
  return out.filter(e => e.trim().length > 0);
}

function keyOf(el) {
  const tag = /^<([A-Za-z0-9_]+)/.exec(el)[1];
  const nm = /\bname="([^"]*)"/.exec(el);
  return tag + ':' + (nm ? nm[1] : '');
}

// values* dirs -> merged element maps; other dirs -> copied in priority order
const valueDirs = new Map(); // dirName -> Map(key -> element)
const fileOps = [];          // [src, dst] in priority order (low first)

for (const dir of dirs) {
  if (!fs.existsSync(dir)) continue;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const sub = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name.startsWith('values')) {
        const map = valueDirs.get(entry.name) || new Map();
        valueDirs.set(entry.name, map);
        for (const f of fs.readdirSync(sub).sort()) {
          if (!f.endsWith('.xml')) continue;
          const xml = fs.readFileSync(path.join(sub, f), 'utf8');
          for (const el of parseTopLevel(xml)) map.set(keyOf(el), el);
        }
      } else {
        for (const f of walk(sub)) {
          const rel = path.relative(dir, f).replace(/\\/g, '/');
          fileOps.push([f, path.join(outRoot, rel)]);
        }
      }
    } else if (entry.name.endsWith('.xml')) {
      // res/*.xml (rare, e.g. res/values already covered); treat as values
    }
  }
}

function walk(d) {
  const out = [];
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) out.push(...walk(p)); else out.push(p);
  }
  return out;
}

fs.mkdirSync(outRoot, { recursive: true });
for (const [src, dst] of fileOps) {
  fs.mkdirSync(path.dirname(dst), { recursive: true });
  fs.copyFileSync(src, dst);
}
for (const [dirName, map] of valueDirs) {
  const d = path.join(outRoot, dirName);
  fs.mkdirSync(d, { recursive: true });
  const xml = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
    + [...map.values()].join('\n') + '\n</resources>\n';
  fs.writeFileSync(path.join(d, 'values.xml'), xml);
}
console.log('merged ' + dirs.length + ' res dirs -> ' + outRoot
  + ' (' + valueDirs.size + ' values dirs, ' + fileOps.length + ' files)');
