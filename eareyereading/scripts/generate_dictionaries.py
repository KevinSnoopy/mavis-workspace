#!/usr/bin/env python3
"""
分级词典生成脚本
=================
从 ECDICT（开源英汉词典，77万条）中，按 tag 字段的考试标记拆分生成多个分级词典。
输出格式与 App 现有 dictionary.txt 兼容：每行 `word|translation`，`#` 开头为注释。
"""

import json
import os
import sqlite3
import sys
import urllib.request
import zipfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(SCRIPT_DIR, "out", "dictionaries")
TMP_DIR = os.path.join(SCRIPT_DIR, "out", ".tmp")

ECDICT_URL = "https://github.com/skywind3000/ECDICT/releases/download/1.0.28/ecdict-sqlite-28.zip"
ECDICT_DB_NAME = "stardict.db"

DICTIONARIES = {
    "cet4":   {"tag": "cet4",   "name": "四级核心词典",   "desc": "大学英语四级核心词汇"},
    "cet6":   {"tag": "cet6",   "name": "六级核心词典",   "desc": "大学英语六级核心词汇"},
    "kaoyan": {"tag": "ky",     "name": "考研词汇词典",   "desc": "全国硕士研究生入学考试词汇"},
    "toefl":  {"tag": "toefl",  "name": "托福词汇词典",   "desc": "TOEFL 托福核心词汇"},
    "gre":    {"tag": "gre",    "name": "GRE 词汇词典",   "desc": "GRE 研究生入学考试词汇"},
    "ielts":  {"tag": "ielts",  "name": "雅思词汇词典",   "desc": "IELTS 雅思核心词汇"},
}

DICT_ORDER = ["cet4", "cet6", "kaoyan", "toefl", "ielts", "gre"]

# jsDelivr CDN URL 前缀
CDN_PREFIX = "https://cdn.jsdelivr.net/gh/KevinSnoopy/mavis-workspace@eareyereading/eareyereading/scripts/out/dictionaries"


def download(url, dest):
    print(f"  下载: {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        total = int(resp.headers.get("Content-Length", 0))
        done = 0
        with open(dest, "wb") as f:
            while True:
                chunk = resp.read(262144)
                if not chunk:
                    break
                f.write(chunk)
                done += len(chunk)
                if total:
                    sys.stdout.write(f"\r  进度: {done*100//total}% ({done//1048576}MB/{total//1048576}MB)")
                    sys.stdout.flush()
        print()


def get_ecdict_db():
    os.makedirs(TMP_DIR, exist_ok=True)
    zip_path = os.path.join(TMP_DIR, "ecdict-sqlite.zip")
    db_path = os.path.join(TMP_DIR, ECDICT_DB_NAME)

    if os.path.exists(db_path):
        print(f"  使用缓存: {db_path}")
        return db_path

    if not os.path.exists(zip_path):
        download(ECDICT_URL, zip_path)

    print("  解压 ECDICT...")
    with zipfile.ZipFile(zip_path) as zf:
        # 显式查找 + 明确报错：next(...) 的裸 StopIteration 无法定位问题
        name = next((n for n in zf.namelist() if n.endswith(".db")), None)
        if name is None:
            sys.exit(f"错误：{zip_path} 中找不到 *.db（下载可能损坏）")
        # 流式拷贝：词典 DB 约 600MB，read() 一次性载入会把内存吃满
        with zf.open(name) as src, open(db_path, "wb") as dst:
            while True:
                chunk = src.read(1048576)
                if not chunk:
                    break
                dst.write(chunk)

    return db_path


def generate_dict_file(dict_id, tag, conn):
    info = DICTIONARIES[dict_id]
    cur = conn.cursor()

    cur.execute(
        "SELECT word, translation FROM stardict "
        "WHERE tag LIKE ? AND translation IS NOT NULL AND translation != '' "
        "ORDER BY frq DESC",
        (f"%{tag}%",),
    )
    rows = cur.fetchall()

    seen = set()
    entries = []
    for word, trans in rows:
        w = word.strip().lower()
        if not w or not w.isalpha() or w in seen:
            continue
        t = trans.strip().replace("\n", "；").replace("\\n", "；")
        if not t:
            continue
        seen.add(w)
        entries.append((w, t))

    out_path = os.path.join(OUT_DIR, f"{dict_id}.txt")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(f"# {info['name']}\n")
        f.write(f"# {info['desc']}\n")
        f.write(f"# 共 {len(entries)} 条\n")
        f.write(f"# 格式: word|translation\n\n")
        for word, trans in entries:
            f.write(f"{word}|{word} {trans}\n")

    size = os.path.getsize(out_path)
    print(f"  生成 {dict_id}.txt: {len(entries)} 条, {size // 1024}KB")
    return {
        "id": dict_id,
        "name": info["name"],
        "description": info["desc"],
        "entryCount": len(entries),
        "sizeBytes": size,
        "fileName": f"{dict_id}.txt",
        "downloadUrl": f"{CDN_PREFIX}/{dict_id}.txt",
    }


def main():
    print("=" * 60)
    print("分级词典生成脚本")
    print("=" * 60)

    os.makedirs(OUT_DIR, exist_ok=True)
    os.makedirs(TMP_DIR, exist_ok=True)

    print("\n[1/3] 获取 ECDICT SQLite（首次需下载 ~211MB）...")
    db_path = get_ecdict_db()

    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM stardict")
    print(f"  ECDICT 总词数: {cur.fetchone()[0]}")

    print("\n[2/3] 生成分级词典...")
    manifest_entries = []
    for dict_id in DICT_ORDER:
        print(f"\n  --- {dict_id} ({DICTIONARIES[dict_id]['name']}) ---")
        meta = generate_dict_file(dict_id, DICTIONARIES[dict_id]["tag"], conn)
        manifest_entries.append(meta)

    conn.close()

    print("\n[3/3] 生成 manifest.json...")
    manifest = {"version": 1, "dictionaries": manifest_entries}
    manifest_path = os.path.join(OUT_DIR, "manifest.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    print(f"  manifest.json 已生成: {manifest_path}")

    print("\n" + "=" * 60)
    print("完成！")
    print(f"输出目录: {OUT_DIR}")
    print("=" * 60)


if __name__ == "__main__":
    main()
