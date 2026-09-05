/**
 * 渲染 Splash 图标 + Themed Icon 单色预览
 */
const fs = require('fs');
const path = require('path');
const { Resvg } = require('@resvg/resvg-js');

const OUT = path.join(__dirname, 'png');
fs.mkdirSync(OUT, { recursive: true });

function renderFile(file, outName, size) {
  const svg = fs.readFileSync(file, 'utf8');
  const r = new Resvg(svg, { fitTo: { mode: 'width', value: size } });
  fs.writeFileSync(path.join(OUT, outName), r.render().asPng());
  return fs.statSync(path.join(OUT, outName)).size;
}

// 1. Splash 图标各密度
const SPLASH = {
  'splash-mdpi-160': 160, 'splash-hdpi-192': 192, 'splash-xhdpi-240': 240,
  'splash-xxhdpi-320': 320, 'splash-xxxhdpi-480': 480,
};
console.log('=== Splash 图标 ===');
for (const [name, size] of Object.entries(SPLASH)) {
  const cleanName = name.replace('splash-', '');
  const bytes = renderFile(path.join(__dirname, 'ic_splash.svg'),
                           `ic_splash_${cleanName}.png`, size);
  console.log(`  ${cleanName.padEnd(20)} ${size}px  ${(bytes / 1024).toFixed(1)}KB`);
}

// 2. Themed Icon (monochrome) — 分两行展示真实场景
//    上行：深色主题 → 系统用浅色渲染 monochrome
//    下行：浅色主题 → 系统用深色渲染 monochrome
const themes = [
  ['#1C1B1F', '深紫主题',   '#FFFFFF'],
  ['#0F1A17', '深墨绿主题', '#FFFFFF'],
  ['#F3E4C8', 'Sepia 主题', '#1E2216'],
  ['#FBF8F3', '亮暖白',     '#1E2216'],
  ['#FFFFFF', '纯白',       '#1E2216'],
];

let monoSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="800" height="280" viewBox="0 0 800 280">`;
themes.forEach(([bg, label, fg], i) => {
  const x = 40 + i * 150, y = 40;
  monoSvg +=
    `<rect x="${x}" y="${y}" width="120" height="120" rx="24" fill="${bg}"/>` +
    `<g transform="translate(${x + 6},${y + 6})">` +
    `<path fill="${fg}" fill-rule="evenodd" ` +
    `d="M54,31 C42,31 24,39.5 24,53 C24,66.5 42,75 54,75 L52.4,75 L52.4,31 Z ` +
    `M54,31 C66,31 84,39.5 84,53 C84,66.5 66,75 54,75 L55.6,75 L55.6,31 Z ` +
    `M48,53 A6,6 0 1,1 60,53 A6,6 0 1,1 48,53 Z"/>` +
    `</g>` +
    `<text x="${x + 60}" y="190" font-family="Helvetica,Arial" font-size="12" ` +
    `fill="#545946" text-anchor="middle">${label}</text>`;
});
// 图例
monoSvg +=
  `<text x="40" y="240" font-family="Helvetica,Arial" font-size="13" fill="#4F5442">` +
  `深色主题下系统用浅色渲染 monochrome；浅色主题下系统用深色渲染</text>`;
monoSvg += '</svg>';

const r = new Resvg(monoSvg, { fitTo: { mode: 'width', value: 800 } });
fs.writeFileSync(path.join(OUT, 'themed-icon-preview.png'), r.render().asPng());
console.log('\n=== Themed Icon（Android 13+ 单色主题适配）===');
console.log('  themed-icon-preview.png');

console.log('\n完成。');