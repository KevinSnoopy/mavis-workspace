/**
 * 渲染 App Icon 并做小尺寸退化验证。
 * 输出：各密度 PNG + 多遮罩预览 + 退化测试条。
 */
const fs = require('fs');
const path = require('path');
const { Resvg } = require('@resvg/resvg-js');

const DIR = __dirname;
const SRC = path.join(DIR, 'app-icon.svg');
const OUT = path.join(DIR, 'png');
fs.mkdirSync(OUT, { recursive: true });

const svg = fs.readFileSync(SRC, 'utf8');

function render(svgStr, width, height, outFile) {
  const r = new Resvg(svgStr, {
    fitTo: height ? { mode: 'width', value: width } : { mode: 'width', value: width },
    font: { loadSystemFonts: true },
  });
  const png = r.render().asPng();
  fs.writeFileSync(path.join(OUT, outFile), png);
  return png.length;
}

// ---------- 1. 各密度 PNG ----------
const DENSITY = {
  'mdpi-48': 48, 'hdpi-72': 72, 'xhdpi-96': 96,
  'xxhdpi-144': 144, 'xxxhdpi-192': 192, 'playstore-512': 512,
};
console.log('=== 各密度图标 ===');
for (const [name, size] of Object.entries(DENSITY)) {
  const bytes = render(svg, size, size, `ic_launcher_${name}.png`);
  console.log(`  ${name.padEnd(16)} ${size}x${size}px  ${(bytes / 1024).toFixed(1)}KB`);
}

// ---------- 2. 退化测试条（核心验证）----------
// 把图标渲染到极小尺寸再放大观察，检验 48dp 下主轮廓是否成立
const DEGRADE = [16, 24, 32, 48, 64, 96, 144];
const CELL = 170, PAD = 26, LABEL = 30;
let strip =
  `<svg xmlns="http://www.w3.org/2000/svg" width="${DEGRADE.length * (CELL + PAD) + PAD}" ` +
  `height="${CELL + LABEL + PAD * 2}" viewBox="0 0 ${DEGRADE.length * (CELL + PAD) + PAD} ${CELL + LABEL + PAD * 2}">` +
  `<rect width="100%" height="100%" fill="#FBF8F3"/>`;

DEGRADE.forEach((size, i) => {
  // 以目标尺寸渲染，再用 nearest-neighbor 放大到 CELL —— 真实还原小屏观感
  const r = new Resvg(svg, { fitTo: { mode: 'width', value: size } });
  const b64 = r.render().asPng().toString('base64');
  const x = PAD + i * (CELL + PAD);
  strip +=
    `<image href="data:image/png;base64,${b64}" x="${x}" y="${PAD}" ` +
    `width="${CELL}" height="${CELL}" style="image-rendering:pixelated"/>`;
  strip +=
    `<text x="${x + CELL / 2}" y="${PAD + CELL + 21}" font-family="Helvetica,Arial" ` +
    `font-size="14" fill="#4F5442" text-anchor="middle">${size}px</text>`;
});
strip += '</svg>';
render(strip, 1400, null, 'degradation-test.png');
console.log('\n=== 退化测试条 ===');
console.log(`  已渲染 ${DEGRADE.join(' / ')} px → png/degradation-test.png`);

// ---------- 3. 多厂商遮罩预览 ----------
// 验证内容在中心 72dp 安全圆内，可承受任意形状裁切
const MASKS = [
  ['circle', 'M54,6a48,48 0 1,1 0,96a48,48 0 1,1 0,-96Z'],
  ['squircle', 'M54,6 C78,6 102,30 102,54 C102,78 78,102 54,102 C30,102 6,78 6,54 C6,30 30,6 54,6 Z'],
  ['rounded', 'M22,6 H86 A16,16 0 0,1 102,22 V86 A16,16 0 0,1 86,102 H22 A16,16 0 0,1 6,86 V22 A16,16 0 0,1 22,6 Z'],
  ['drop', 'M54,6 C54,6 102,44 102,68 A48,48 0 0,1 6,68 C6,44 54,6 54,6 Z'],
  ['petal', 'M54,6 C86,6 102,30 102,54 C102,86 78,102 54,102 C22,102 6,78 6,54 C6,22 30,6 54,6 Z'],
];
const M = 190, MP = 30, ML = 34;
let maskSvg =
  `<svg xmlns="http://www.w3.org/2000/svg" width="${MASKS.length * (M + MP) + MP}" ` +
  `height="${M + ML + MP * 2}" viewBox="0 0 ${MASKS.length * (M + MP) + MP} ${M + ML + MP * 2}">` +
  `<rect width="100%" height="100%" fill="#FBF8F3"/>`;

const iconB64 = new Resvg(svg, { fitTo: { mode: 'width', value: M } })
  .render().asPng().toString('base64');

MASKS.forEach(([name, d], i) => {
  const x = MP + i * (M + MP);
  maskSvg +=
    `<defs><clipPath id="c${i}"><path transform="translate(${x},${MP}) scale(${M / 108})" d="${d}"/></clipPath></defs>` +
    `<g clip-path="url(#c${i})">` +
    `<image href="data:image/png;base64,${iconB64}" x="${x}" y="${MP}" width="${M}" height="${M}"/>` +
    `</g>`;
  maskSvg +=
    `<text x="${x + M / 2}" y="${MP + M + 23}" font-family="Helvetica,Arial" font-size="14" ` +
    `fill="#4F5442" text-anchor="middle">${name}</text>`;
});
maskSvg += '</svg>';
render(maskSvg, 1200, null, 'mask-preview.png');
console.log('\n=== 遮罩裁切预览 ===');
console.log(`  ${MASKS.map(m => m[0]).join(' / ')} → png/mask-preview.png`);

console.log(`\n输出目录：${OUT}`);
