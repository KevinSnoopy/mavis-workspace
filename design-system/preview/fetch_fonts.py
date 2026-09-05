# -*- coding: utf-8 -*-
"""下载 Inter / Literata 的 latin 子集并本地化 CSS。"""
import re, os, urllib.request

BASE = os.path.dirname(os.path.abspath(__file__))
CSS_IN = os.path.join(BASE, 'fonts.css')
FONT_DIR = os.path.join(BASE, 'fonts')
os.makedirs(FONT_DIR, exist_ok=True)
CSS_OUT = os.path.join(BASE, 'fonts-local.css')

UA = ('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/120.0 Safari/537.36')

LATIN_RANGE = 'U+0000-00FF'   # latin 子集起始标识，用于筛选

with open(CSS_IN, encoding='utf-8') as f:
    css = f.read()

blocks = re.findall(r'/\*\s*([a-z0-9\-\[\]]+)\s*\*/\s*(@font-face\s*\{.*?\})',
                    css, re.S)
print(f'共解析 {len(blocks)} 个 @font-face 块')

kept, downloaded = [], 0
for subset, block in blocks:
    if subset != 'latin':
        continue
    fam = re.search(r"font-family:\s*'([^']+)'", block).group(1)
    wt = re.search(r'font-weight:\s*(\d+)', block).group(1)
    url = re.search(r'url\((https://[^)]+\.woff2)\)', block).group(1)

    fname = f'{fam.lower().replace(" ", "-")}-{wt}.woff2'
    fpath = os.path.join(FONT_DIR, fname)

    if not os.path.exists(fpath):
        req = urllib.request.Request(url, headers={'User-Agent': UA})
        data = urllib.request.urlopen(req, timeout=30).read()
        with open(fpath, 'wb') as out:
            out.write(data)
        downloaded += 1

    size = os.path.getsize(fpath)
    block = block.replace(url, f'fonts/{fname}')
    kept.append(f'/* {fam} {wt} latin — {size//1024}KB */\n{block}')
    print(f'  ✓ {fname:<24} {size//1024:>3}KB')

with open(CSS_OUT, 'w', encoding='utf-8') as f:
    f.write('/* 本地化字体子集（latin）— 离线可用 */\n')
    f.write('/* 中文字形由系统字体栈提供：PingFang SC / Microsoft YaHei */\n\n')
    f.write('\n'.join(kept))

total = sum(os.path.getsize(os.path.join(FONT_DIR, x)) for x in os.listdir(FONT_DIR))
print(f'\n下载 {downloaded} 个文件，字体目录合计 {total//1024}KB')
print(f'输出 → {CSS_OUT}')
