"""Offline research probe; synthetic fixtures, NOT production behavior/tests.

Run with Python 3.10+. Optional rendering needs Pillow and Windows system fonts.
No network, user library, source extension, or model service is accessed.
"""

import argparse
import hashlib
import json
import platform
import re
import unicodedata
from pathlib import Path


# Ground truth refers to the imagined body, not the visible title's script.
# Deliberately mislabeled titles prove the limit of metadata-only inference.
CASES = [
    ("explicit_en_tag", "A Journey [Chinese]", True, []),
    ("casefold", "A Journey [cHiNeSe]", True, []),
    ("hans", "A Journey [简体中文]", True, []),
    ("hant", "A Journey [繁體中文]", True, []),
    ("zh_code", "A Journey [zh]", True, []),
    ("hans_code", "A Journey [zh-Hans]", True, []),
    ("hant_code", "A Journey [zh-Hant]", True, []),
    ("fullwidth", "旅程 ［Ｃｈｉｎｅｓｅ］", True, []),
    ("cjk_brackets", "旅程【中文】", True, []),
    ("china_translation", "旅の記録 [中国翻訳]", True, []),
    ("chinese_translation", "旅の記録 [中国翻译]", True, []),
    ("group_hans", "旅の記録 [青叶汉化组]", True, ["青叶汉化组"]),
    ("group_hant", "旅の記録 [青葉漢化組]", True, ["青葉漢化組"]),
    ("group_no_suffix", "旅の記録 [星空☆漢化]", True, ["星空☆漢化"]),
    ("group_prefix", "[白云汉化组] 旅の記録", True, ["白云汉化组"]),
    ("two_groups", "旅程 [青叶汉化组] [白云汉化组]", True, ["青叶汉化组", "白云汉化组"]),
    ("joint_groups", "旅程 [青叶汉化组×白云汉化组]", True, ["青叶汉化组", "白云汉化组"]),
    ("joint_ambiguous", "旅程 [青叶×白云汉化组]", True, ["青叶×白云汉化组"]),
    ("generic_hanhua", "旅程 [汉化]", True, []),
    ("machine", "旅程 [中文机翻]", True, []),
    ("revised", "旅程 [青叶汉化组] [修正版]", True, ["青叶汉化组"]),
    ("creator_and_group", "[星舟社 (青岚)] 旅程 [青叶汉化组]", True, ["青叶汉化组"]),
    ("bilingual_title", "旅の記録｜我们的旅行日记", True, []),
    ("bilingual_with_group", "旅の記録｜我们的旅行日记 [青叶汉化组]", True, ["青叶汉化组"]),
    ("translated_title_only", "我们一起去看海", True, []),
    ("traditional_title_only", "我們一起去看海", True, []),
    ("han_ambiguous_zh", "東京旅行", True, []),
    ("han_ambiguous_ja", "東京旅行", False, []),
    ("ja_title", "明日は図書館へ行こう", False, []),
    ("ja_creator", "[山田太郎] A Journey [English]", False, []),
    ("ja_chinese_place", "中国旅行 [Japanese]", False, []),
    ("han_creator", "[青岚] A Journey", False, []),
    ("circle_not_translator", "[青叶组 (青岚)] 旅の記録", False, []),
    ("english", "A Journey [English] [Azure Scans]", False, []),
    ("japanese", "旅の記録 [Japanese]", False, []),
    ("korean", "여행 일기 [Korean]", False, []),
    ("spanish", "Un viaje [Spanish]", False, []),
    ("no_hanhua", "旅の記録 [未汉化]", False, []),
    ("not_hanhua", "旅の記録 [非汉化]", False, []),
    ("wanted", "旅の記録 [求汉化]", False, []),
    ("recruit", "旅の記録 [汉化组招募]", False, []),
    ("not_chinese", "A Journey [Not Chinese]", False, []),
    ("description_quote", "关于汉化组的故事 [Japanese]", False, []),
    ("raw", "旅の記録 [RAW]", False, []),
    ("english_scans_no_tag", "A Journey [Azure Scans]", False, []),
    ("ambiguous_cn", "A Journey [CN]", False, []),
    ("truncated", "旅程 [青叶汉化组…", True, []),
    ("group_false_friend", "[中国研究会] 旅の記録", False, []),
    ("language_conflict", "旅程 [Chinese] [Japanese]", True, []),
    ("group_language_conflict", "旅程 [青叶汉化组] [English]", False, ["青叶汉化组"]),
    ("explicit_bilingual", "Journey [中英双语]", True, []),
    ("credit_no_lang", "Journey [翻译：Azure Team]", False, ["Azure Team"]),
    ("official_zh", "Journey [官方中文]", True, []),
    ("unknown_group", "Journey [晨光]", True, []),
    ("wrong_site_tag", "Journey [Chinese]", False, []),
    ("credit_only_chinese", "Journey [青叶汉化组]", False, ["青叶汉化组"]),
    ("quoted_language", "The word Chinese", False, []),
    ("zh_romanization", "Wo men yi qi qu kan hai", True, []),
    ("uploader", "Journey [上传者：青叶汉化组] [English]", False, []),
    ("unbalanced_nested", "Journey [[Chinese]", False, []),
]

LANGUAGES = {
    "chinese": "zh", "中文": "zh", "汉化": "zh", "漢化": "zh",
    "中国翻译": "zh", "中国翻訳": "zh", "官方中文": "zh", "中文机翻": "zh",
    "简体中文": "zh-Hans", "繁體中文": "zh-Hant", "zh": "zh",
    "zh-hans": "zh-Hans", "zh-hant": "zh-Hant",
    "english": "en", "japanese": "ja", "korean": "ko", "spanish": "es",
}
HAN = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")
GROUP = re.compile(r"^.{1,48}?(?:汉化|漢化)(?:组|組)?$")
EXCLUDE = re.compile(r"未汉化|未漢化|非汉化|非漢化|求汉化|求漢化|招募|上传者|上傳者|招人")


def bracket_tokens(title):
    """Conservative balanced scanner; normalized view never changes identity."""
    view = unicodedata.normalize("NFKC", title).replace("【", "[").replace("】", "]")
    tokens, depth, begin, malformed = [], 0, 0, False
    for pos, char in enumerate(view):
        if char == "[":
            depth += 1
            if depth == 1:
                begin = pos + 1
            else:
                malformed = True
        elif char == "]":
            if depth != 1:
                malformed = True
            if depth == 1:
                tokens.append(view[begin:pos].strip())
            depth = max(0, depth - 1)
    return [] if malformed or depth else tokens


def infer(title):
    languages, groups, reasons = set(), [], []
    bilingual = False
    for token in bracket_tokens(title[:4096]):
        if EXCLUDE.search(token):
            continue
        code = LANGUAGES.get(token.casefold())
        if code:
            languages.add(code)
            reasons.append("explicit-language")
        elif token == "中英双语":
            languages.update(("zh", "en"))
            bilingual = True
            reasons.append("explicit-multilingual")
        elif token.startswith("翻译:"):
            groups.append(token.partition(":")[2].strip())
            reasons.append("translation-credit-without-language")
        elif GROUP.fullmatch(token):
            parts = re.split("[×+]", token)
            # Split only when every part independently supplies its role.
            groups.extend(parts if all(GROUP.fullmatch(x) for x in parts) else [token])
            languages.add("zh")
            reasons.append("translation-credit")
    bases = {x.split("-")[0] for x in languages}
    if len(bases) > 1 and not bilingual:
        state = "conflict"
    elif bases:
        state = "supported"
    else:
        state = "unknown"
    return {"state": state, "languages": sorted(languages), "groups": sorted(set(groups)),
            "evidence": sorted(set(reasons)), "strict_zh": state == "supported" and "zh" in bases}


def metrics(rows, key):
    tp = sum(r[key] and r["truth_zh"] for r in rows)
    fp = sum(r[key] and not r["truth_zh"] for r in rows)
    fn = sum(not r[key] and r["truth_zh"] for r in rows)
    tn = len(rows) - tp - fp - fn
    return {"tp": tp, "fp": fp, "fn": fn, "tn": tn,
            "precision": round(tp / (tp + fp), 4) if tp + fp else None,
            "recall": round(tp / (tp + fn), 4) if tp + fn else None}


OCR_PAGES = {
    "hans_dialogue": ["今天我们去图书馆吧。", "我想借一本介绍海洋的书。", "太好了，我们下午一起出发。"],
    "hant_dialogue": ["今天我們去圖書館吧。", "我想借一本介紹海洋的書。", "太好了，我們下午一起出發。"],
    "ja_dialogue": ["今日は図書館に行こう。", "海の本を借りたいです。", "午後にいっしょに出かけよう。"],
    "en_dialogue": ["Let us go to the library today.", "I want a book about the ocean.", "We can leave this afternoon."],
    "mixed_dialogue": ["今天我们去图书馆吧。", "今日は図書館に行こう。", "我们下午一起出发。", "午後にいっしょに出かけよう。"],
    "han_only_cover": ["東京旅行", "山田太郎"],
    "en_body_zh_credit": ["Let us go to the library today.", "We can leave this afternoon.", "制作鸣谢：青叶汉化组"],
}


def render_pages(destination):
    from PIL import Image, ImageDraw, ImageFont
    destination.mkdir(parents=True, exist_ok=True)
    fonts = {"ja": "C:/Windows/Fonts/msgothic.ttc", "zh": "C:/Windows/Fonts/msyh.ttc"}
    for name, lines in OCR_PAGES.items():
        canvas = Image.new("RGB", (1200, 680), "white")
        draw = ImageDraw.Draw(canvas)
        for i, line in enumerate(lines):
            font = ImageFont.truetype(fonts["ja" if re.search(r"[\u3040-\u30ff]", line) else "zh"], 38)
            y = 25 + i * 150
            draw.rounded_rectangle((15, y, 1185, y + 120), radius=28, outline="black", width=2)
            draw.text((45, y + 30), line, font=font, fill="black")
        canvas.save(destination / f"{name}.png")
    (destination / "ground-truth.json").write_text(json.dumps(OCR_PAGES, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def ocr_metrics(path):
    """CER ignores whitespace only; punctuation and recognition errors remain."""
    report = json.loads(path.read_text(encoding="utf-8"))
    rows = []
    for result in report["results"]:
        expected = "".join("".join(OCR_PAGES[result["sample"]]).split())
        observed = "".join(result["text"].split())
        previous = list(range(len(observed) + 1))
        for i, left in enumerate(expected, 1):
            current = [i]
            for j, right in enumerate(observed, 1):
                current.append(min(current[-1] + 1, previous[j] + 1, previous[j - 1] + (left != right)))
            previous = current
        rows.append({"sample": result["sample"], "pack": result["language"],
                     "edits": previous[-1], "characters": len(expected),
                     "cer": round(previous[-1] / len(expected), 4)})
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    parser.add_argument("--render-ocr-dir", type=Path)
    parser.add_argument("--ocr-results", type=Path)
    args = parser.parse_args()
    rows = []
    for ident, title, truth, expected_groups in CASES:
        result = infer(title)
        rows.append({"id": ident, "title": title, "truth_zh": truth,
                     "expected_groups": sorted(expected_groups), "han_baseline": bool(HAN.search(title)),
                     "marker_baseline": bool(re.search("chinese|中文|汉化|漢化|中国翻", title, re.I)), **result})
    summary = {"scope": "Synthetic adversarial cases, not a representative accuracy estimate or production test",
               "python": platform.python_version(), "case_count": len(rows),
               "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
               "han_baseline": metrics(rows, "han_baseline"),
               "marker_baseline": metrics(rows, "marker_baseline"),
               "evidence_rule": metrics(rows, "strict_zh"),
               "group_exact_matches": sum(r["groups"] == r["expected_groups"] for r in rows),
               "abstentions": sum(r["state"] in ("unknown", "conflict") for r in rows)}
    output = {"summary": summary, "cases": rows}
    if args.ocr_results:
        output["ocr_cer"] = ocr_metrics(args.ocr_results)
    if args.output:
        args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.render_ocr_dir:
        render_pages(args.render_ocr_dir)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
