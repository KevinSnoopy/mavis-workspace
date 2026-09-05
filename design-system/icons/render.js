/**
 * 渲染品牌图标预览：8 枚图标 × 2 主题背景。
 * 先将每个 SVG 单独渲染为 PNG，再以 <image> 嵌入合成图（避免嵌套 SVG 兼容问题）。
 */
const fs = require('fs');
const path = require('path');
const { Resvg } = require('@resvg/resvg-js');

const ICON_DIR = __dirname;
const PREVIEW_DIR = path.join(ICON_DIR, 'preview');
fs.mkdirSync(PREVIEW_DIR, { recursive: true });

const ICONS = [
  ['ic_reader.svg',      '阅读',   'Reader'],
  ['ic_vocabulary.svg',  '生词',   'Vocabulary'],
  ['ic_review.svg',      '复习',   'Review'],
  ['ic_listen.svg',      '听读',   'Listen'],
  ['ic_speed_read.svg',  '速读',   'SpeedRead'],
  ['ic_cloze.svg',       '挖空',   'Cloze'],
  ['ic_frequency.svg',   '词频',   'Frequency'],
  ['ic_settings.svg',    '设置',   'Settings'],
];

const THEMES = [
  { bg: '#FBF8F3', fg: '#191A14', card: '#FFFFFF', border: '#D3D5CA',
    sub: '#545946', label: '亮色 · 暖白' },
  { bg: '#0F1A17', fg: '#F1FCFA', card: '#16241F', border: '#243029',
    sub: '#A2A793', label: '深色 · 墨绿' },
];

// 1. 把每个 SVG 单独渲染为 PNG（resvg 的 fitTo 自动按 viewBox 等比缩放）
function renderSVG(svgContent, fg, size = 96) {
  const replaced = svgContent.replace(/stroke="currentColor"/g, `stroke="${fg}"`);
  return new Resvg(replaced, { fitTo: { mode: 'width', value: size } }).render().asPng();
}

const size = 96, gap = 22, labelH = 56, head = 60, padX = 40;
const cardW = 280, cardH = size + labelH + 32;
const W = THEMES.length * (cardW + gap) + padX * 2;
const H = ICONS.length * (cardH + gap) + head + 80;

let svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">`;
svg += `<rect width="100%" height="100%" fill="#FBF8F3"/>`;

// 标题
svg += `<text x="${padX}" y="${36}" font-family="Inter,'Helvetica Neue',Arial" font-size="22" font-weight="600" fill="#191A14">品牌图标 · 8 枚核心功能</text>`;
svg += `<text x="${padX}" y="${56}" font-family="Inter,'Helvetica Neue',Arial" font-size="14" fill="#545946">听阅专属图标语言 — 24dp 网格 · 1.75dp 描边 · 圆角端点 · 与 AppIcon 几何呼应</text>`;

ICONS.forEach(([file, name, en], row) => {
  const y = head + row * (cardH + gap);

  const original = fs.readFileSync(path.join(ICON_DIR, file), 'utf8');

  THEMES.forEach((theme, col) => {
    const x = padX + col * (cardW + gap);

    // 卡片底
    svg += `<rect x="${x}" y="${y}" width="${cardW}" height="${cardH}" rx="20"
            fill="${theme.card}" stroke="${theme.border}" stroke-width="0.75"/>`;

    // 图标 PNG
    const png = renderSVG(original, theme.fg, size);
    const b64 = png.toString('base64');
    svg += `<image href="data:image/png;base64,${b64}" x="${x + (cardW - size) / 2}" y="${y + 24}" width="${size}" height="${size}"/>`;

    // 标签
    svg += `<text x="${x + cardW / 2}" y="${y + size + 52}" font-family="Inter,'Helvetica Neue',Arial" font-size="16" font-weight="500" fill="${theme.fg}" text-anchor="middle">${name}</text>`;
    svg += `<text x="${x + cardW / 2}" y="${y + size + 70}" font-family="Inter,'Helvetica Neue',Arial" font-size="12" fill="${theme.sub}" text-anchor="middle" opacity="0.85">${en}</text>`;
  });
});

// 主题标题
THEMES.forEach((theme, col) => {
  const x = padX + col * (cardW + gap) + cardW / 2;
  svg += `<text x="${x}" y="${H - 28}" font-family="Inter,'Helvetica Neue',Arial" font-size="14" font-weight="500" fill="#545946" text-anchor="middle">${theme.label}</text>`;
});

svg += '</svg>';

const r = new Resvg(svg, { fitTo: { mode: 'width', value: 1400 } });
const out = path.join(PREVIEW_DIR, 'icons-overview.png');
fs.writeFileSync(out, r.render().asPng());
console.log(`已输出 → ${out}  (${(fs.statSync(out).size / 1024).toFixed(1)}KB)`);