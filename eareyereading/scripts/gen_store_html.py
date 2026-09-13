#!/usr/bin/env python3
"""生成上架合规文档 HTML：docs/store/*.md → docs/store/*.html + app assets。

同一份 Markdown 源输出到两处，从根上避免"商店托管版"与"App 内置版"内容漂移：
- docs/store/*.html      —— 托管到公共仓库（jsDelivr CDN 可访问），供商店填"隐私政策链接"
- app/src/main/assets/   —— App 内 WebView 离线查看（审核常做断网测试，全文必须内置）

用法：python3 scripts/gen_store_html.py
"""

import os
import markdown

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DOCS = [
    ("privacy-policy", "隐私政策"),
    ("user-agreement", "用户服务协议"),
]

# 深浅色自适应：商店审核与 App 内 WebView 均可直接渲染
CSS = """
:root { color-scheme: light dark; }
body {
  font-family: -apple-system, "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif;
  max-width: 720px; margin: 0 auto; padding: 24px 20px 64px;
  line-height: 1.75; font-size: 16px;
  background: #ffffff; color: #1c1c1e;
}
h1 { font-size: 24px; line-height: 1.4; }
h2 { font-size: 19px; margin-top: 2em; border-bottom: 1px solid #e5e5ea; padding-bottom: 6px; }
h3 { font-size: 17px; margin-top: 1.6em; }
table { border-collapse: collapse; width: 100%; font-size: 14px; margin: 12px 0; display: block; overflow-x: auto; }
th, td { border: 1px solid #d1d1d6; padding: 8px 10px; text-align: left; vertical-align: top; }
th { background: #f2f2f7; }
blockquote { border-left: 4px solid #3478f6; margin: 12px 0; padding: 4px 14px; background: #f2f6ff; }
code { background: #f2f2f7; padding: 1px 5px; border-radius: 4px; font-size: 90%; }
a { color: #3478f6; }
@media (prefers-color-scheme: dark) {
  body { background: #1c1c1e; color: #e5e5ea; }
  h2 { border-bottom-color: #38383a; }
  th, td { border-color: #48484a; }
  th { background: #2c2c2e; }
  blockquote { background: #1e2a3a; }
  code { background: #2c2c2e; }
}
"""

HTML_TMPL = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} - 听阅</title>
<style>{css}</style>
</head>
<body>
{body}
</body>
</html>
"""


def main() -> None:
    docs_dir = os.path.join(ROOT, "docs", "store")
    assets_dir = os.path.join(ROOT, "app", "src", "main", "assets")
    os.makedirs(assets_dir, exist_ok=True)

    for slug, title in DOCS:
        md_path = os.path.join(docs_dir, f"{slug}.md")
        with open(md_path, encoding="utf-8") as f:
            body = markdown.markdown(
                f.read(),
                extensions=["tables", "sane_lists"],
                output_format="html",
            )
        html = HTML_TMPL.format(title=title, css=CSS, body=body)
        for out_dir in (docs_dir, assets_dir):
            with open(os.path.join(out_dir, f"{slug}.html"), "w", encoding="utf-8") as f:
                f.write(html)
        print(f"generated: docs/store/{slug}.html + app/src/main/assets/{slug}.html")


if __name__ == "__main__":
    main()
