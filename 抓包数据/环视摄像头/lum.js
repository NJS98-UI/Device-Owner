// PNG mean-luminance probe: node lum.js <file.png> [x0 y0 x1 y1]
const fs = require('fs'), zlib = require('zlib');

function decode(buf) {
  let p = 8, w = 0, h = 0, depth = 0, ctype = 0, interlace = 0;
  const idat = [];
  while (p < buf.length) {
    const len = buf.readUInt32BE(p);
    const type = buf.toString('ascii', p + 4, p + 8);
    const data = buf.slice(p + 8, p + 8 + len);
    if (type === 'IHDR') {
      w = data.readUInt32BE(0); h = data.readUInt32BE(4);
      depth = data[8]; ctype = data[9]; interlace = data[12];
    } else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    p += 12 + len;
  }
  if (interlace !== 0) throw new Error('interlaced PNG unsupported');
  if (depth !== 8) throw new Error('bitDepth ' + depth + ' unsupported');
  const ch = ctype === 6 ? 4 : ctype === 2 ? 3 : ctype === 0 ? 1 : 0;
  if (!ch) throw new Error('colorType ' + ctype + ' unsupported');
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = w * ch;
  const out = Buffer.alloc(h * stride);
  const paeth = (a, b, c) => {
    const pp = a + b - c, da = Math.abs(pp - a), db = Math.abs(pp - b), dc = Math.abs(pp - c);
    return da <= db && da <= dc ? a : db <= dc ? b : c;
  };
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)];
    const src = y * (stride + 1) + 1, dst = y * stride, prev = dst - stride;
    for (let x = 0; x < stride; x++) {
      const cur = raw[src + x];
      const a = x >= ch ? out[dst + x - ch] : 0;
      const b = y > 0 ? out[prev + x] : 0;
      const c = x >= ch && y > 0 ? out[prev + x - ch] : 0;
      out[dst + x] = f === 0 ? cur
        : f === 1 ? (cur + a) & 255
        : f === 2 ? (cur + b) & 255
        : f === 3 ? (cur + ((a + b) >> 1)) & 255
        : (cur + paeth(a, b, c)) & 255;
    }
  }
  return { w, h, ch, px: out };
}

const img = decode(fs.readFileSync(process.argv[2]));
let [x0, y0, x1, y1] = process.argv.slice(3).map(Number);
if (!x1) { x0 = 0; y0 = 0; x1 = img.w; y1 = img.h; }
let sum = 0, n = 0;
for (let y = y0; y < y1; y += 3) {
  for (let x = x0; x < x1; x += 3) {
    const i = y * img.w * img.ch + x * img.ch;
    sum += img.px[i] + img.px[i + 1] + img.px[i + 2];
    n += 3;
  }
}
console.log(`${img.w}x${img.h} ch=${img.ch} rect=${x0},${y0},${x1},${y1} mean=${(sum / n).toFixed(2)}`);
