"""
Cell 混合内容识别测试 — MineU 风格的 pipeline:
  1. /api/formula/parse  → 检测公式位置 + 识别为 LaTeX
  2. 遮蔽公式区域，用 PaddleOCR 识别剩余文字
  3. 按阅读顺序拼接

用法:
  python devnotes/cell_mixed_test.py
  python devnotes/cell_mixed_test.py -i "D:/神机/加工文件/7.png"
"""

import argparse
import json
import io
import os
import sys
import time
from pathlib import Path

import cv2
import numpy as np
import requests
from PIL import Image, ImageDraw
from paddleocr import PaddleOCR

API_BASE = "http://localhost:8000"

# 测试图片
IMAGE_PATH = r"D:\神机\加工文件\4.png"


# ═══════════════════════════════════════════════════════════════
# 工具函数
# ═══════════════════════════════════════════════════════════════

def poly_to_bbox(poly):
    """将 polygon [x1,y1,x2,y2,...] → (x, y, w, h)."""
    if not poly or len(poly) < 4:
        return (0, 0, 0, 0)
    xs = [poly[i] for i in range(0, len(poly), 2)]
    ys = [poly[i] for i in range(1, len(poly), 2)]
    x, y = min(xs), min(ys)
    w, h = max(xs) - x, max(ys) - y
    return (int(x), int(y), int(w), int(h))


def imread_unicode(path: str) -> np.ndarray:
    """cv2.imread 不支持中文路径, 用 numpy 绕过去."""
    with open(path, "rb") as f:
        data = np.frombuffer(f.read(), dtype=np.uint8)
    img = cv2.imdecode(data, cv2.IMREAD_COLOR)
    return img


def save_debug(img, name, output_dir):
    """保存调试图片."""
    if isinstance(img, np.ndarray):
        img = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
    img.save(output_dir / name)


# ═══════════════════════════════════════════════════════════════
# Step 1: 公式检测 + 识别 (调用 /api/formula/parse)
# ═══════════════════════════════════════════════════════════════

def step1_formula_parse(image_path: str, output_dir: Path):
    """
    调用 /api/formula/parse 一站式获得公式位置和 LaTeX.
    Returns: list of {bbox, latex, category_type, score}
    """
    print("=" * 70)
    print("STEP 1: /api/formula/parse — 检测公式 + 识别 LaTeX")
    print("=" * 70)

    t0 = time.time()
    with open(image_path, "rb") as f:
        resp = requests.post(
            API_BASE + "/api/formula/parse",
            files={"file": (os.path.basename(image_path), f, "image/png")},
            timeout=120,
        )
    resp.raise_for_status()
    result = resp.json()
    elapsed = time.time() - t0

    formulas = result.get("formulas", [])
    w, h = result.get("width", 0), result.get("height", 0)
    print(f"  图片: {w}x{h}, 耗时: {elapsed:.1f}s, 公式数: {len(formulas)}")

    parsed = []
    for i, f in enumerate(formulas):
        bbox = poly_to_bbox(f.get("poly", []))
        latex = f.get("latex", "")
        cat = f.get("category_type", "?")
        score = f.get("score", 0)
        print(f"  [{i}] type={cat} score={score:.3f} bbox={bbox} latex={latex[:80]}")
        parsed.append({
            "bbox": bbox,
            "latex": latex,
            "category_type": cat,
            "score": score,
        })

    return parsed, (w, h)


# ═══════════════════════════════════════════════════════════════
# Step 2: 遮蔽公式区域 → PaddleOCR
# ═══════════════════════════════════════════════════════════════

def step2_ocr_text(image_path: str, formula_regions: list, img_size: tuple,
                   output_dir: Path):
    """
    把公式区域涂白, 对剩余图片做 OCR.
    Returns: list of {bbox, text, score}
    """
    print("=" * 70)
    print("STEP 2: 遮蔽公式 → PaddleOCR 识别剩余文字")
    print("=" * 70)

    img = imread_unicode(image_path)
    if img is None:
        print("  无法读取图片!")
        return []

    masked = img.copy()
    for fr in formula_regions:
        x, y, w_box, h_box = fr["bbox"]
        # 稍微扩展避免残留边角
        pad = 3
        x1 = max(0, x - pad)
        y1 = max(0, y - pad)
        x2 = min(masked.shape[1], x + w_box + pad)
        y2 = min(masked.shape[0], y + h_box + pad)
        cv2.rectangle(masked, (x1, y1), (x2, y2), (255, 255, 255), -1)

    save_debug(masked, "masked_for_ocr.png", output_dir)
    print(f"  已遮蔽 {len(formula_regions)} 个公式区域")

    # PaddleOCR
    t0 = time.time()
    ocr = PaddleOCR(lang="ch", use_angle_cls=False, show_log=False)
    result = ocr.ocr(masked, cls=False)
    elapsed = time.time() - t0
    print(f"  OCR 耗时: {elapsed:.1f}s")

    text_lines = []
    if result and result[0]:
        for line in result[0]:
            box, (text, score) = line
            # box: [[x1,y1],[x2,y2],[x3,y3],[x4,y4]]
            xs = [p[0] for p in box]
            ys = [p[1] for p in box]
            bbox = (int(min(xs)), int(min(ys)),
                    int(max(xs) - min(xs)), int(max(ys) - min(ys)))
            if text.strip() and score > 0.5:
                text_lines.append({"bbox": bbox, "text": text, "score": score})

    print(f"  有效文字行: {len(text_lines)}")
    for i, line in enumerate(text_lines[:20]):
        print(f"    [{i}] \"{line['text'][:60]}\" score={line['score']:.3f} "
              f"bbox={line['bbox']}")

    return text_lines


# ═══════════════════════════════════════════════════════════════
# Step 3: 按阅读顺序拼接
# ═══════════════════════════════════════════════════════════════

def step3_stitch(text_lines: list, formula_regions: list):
    """
    按 Y 坐标分组为"行", 行内按 X 排序, 文字和公式交替拼接.
    行间公式 (isolated) 独立成行, 行内公式 (inline) 插入文字中间.
    """
    print("=" * 70)
    print("STEP 3: 按阅读顺序拼接")
    print("=" * 70)

    # 收集所有元素
    elements = []

    for tl in text_lines:
        x, y, w, h = tl["bbox"]
        elements.append({
            "y_center": y + h / 2,
            "x": x,
            "type": "text",
            "content": tl["text"],
            "bbox": tl["bbox"],
        })

    for fr in formula_regions:
        x, y, w, h = fr["bbox"]
        display_mode = (fr.get("category_type") == "isolated")
        elements.append({
            "y_center": y + h / 2,
            "x": x,
            "type": "formula",
            "content": fr["latex"],
            "display": display_mode,  # True = 行间公式, False = 行内公式
            "bbox": fr["bbox"],
        })

    # 按 y 排序, 同 y 按 x
    elements.sort(key=lambda e: (e["y_center"], e["x"]))

    # 分组为行 (Y 中心差 < 15px 视为同行)
    rows = []
    current_row = []
    current_y = None

    for elem in elements:
        yc = elem["y_center"]
        if current_y is None or abs(yc - current_y) < 15:
            current_y = yc if current_y is None else (current_y + yc) / 2
            current_row.append(elem)
        else:
            rows.append(sorted(current_row, key=lambda e: e["x"]))
            current_row = [elem]
            current_y = yc
    if current_row:
        rows.append(sorted(current_row, key=lambda e: e["x"]))

    # 生成最终文本
    output_lines = []
    for row in rows:
        parts = []
        for elem in row:
            if elem["type"] == "formula":
                latex = elem["content"]
                if elem.get("display"):
                    # 行间公式: 独立一行
                    parts.append(f"$${latex}$$")
                else:
                    # 行内公式
                    parts.append(f"${latex}$")
            else:
                parts.append(elem["content"])
        line = "".join(parts) if parts else ""
        if line.strip():
            output_lines.append(line)

    print("\n  --- 拼接结果 ---")
    for i, line in enumerate(output_lines):
        print(f"  [{i}] {line[:150]}")

    # 保存原始数据
    print(f"\n  总行数: {len(output_lines)}")

    return output_lines


# ═══════════════════════════════════════════════════════════════
# Bonus: 也输出原始 OCR (全图, 不遮蔽) 做对比
# ═══════════════════════════════════════════════════════════════

def step_compare_raw_ocr(image_path: str):
    """对全图直接 OCR，作为对比基准."""
    print("=" * 70)
    print("BONUS: 全图直接 OCR (不遮蔽公式) — 作为对比")
    print("=" * 70)

    img = imread_unicode(image_path)
    ocr = PaddleOCR(lang="ch", use_angle_cls=False, show_log=False)
    t0 = time.time()
    result = ocr.ocr(img, cls=False)
    elapsed = time.time() - t0
    print(f"  OCR 耗时: {elapsed:.1f}s")

    if result and result[0]:
        for i, line in enumerate(result[0][:15]):
            box, (text, score) = line
            print(f"  [{i}] [{score:.3f}] {text[:80]}")

    return result


# ═══════════════════════════════════════════════════════════════
# Main
# ═══════════════════════════════════════════════════════════════

def main():
    parser = argparse.ArgumentParser(description="Cell 混合内容识别测试")
    parser.add_argument("-i", "--image", default=IMAGE_PATH, help="输入图片路径")
    parser.add_argument("--no-stitch", action="store_true", help="跳过拼接，只看各步骤结果")
    args = parser.parse_args()

    image_path = args.image
    if not os.path.exists(image_path):
        sys.exit(f"图片不存在: {image_path}")

    output_dir = Path(__file__).parent / "_cell_test_output"
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"图片: {image_path}")
    print(f"API:  {API_BASE}")

    # ── Step 1: 公式检测 + 识别 ──
    formulas, img_size = step1_formula_parse(image_path, output_dir)

    if not formulas:
        print("\n>>> 未检测到公式! 直接对整个图做 OCR:")
        step_compare_raw_ocr(image_path)
        print("\n结论: 这张图没有公式, 不需要混合 pipeline, 直接 OCR 就行。")
        return

    # ── Step 2: OCR 剩余文字 ──
    text_lines = step2_ocr_text(image_path, formulas, img_size, output_dir)

    # ── Step 3: 拼接 ──
    if not args.no_stitch:
        final_lines = step3_stitch(text_lines, formulas)

        # 保存
        result_file = output_dir / "final_result.txt"
        result_file.write_text("\n".join(final_lines), encoding="utf-8")
        print(f"\n最终结果: {result_file}")

    # ── 对比: 全图直接 OCR ──
    step_compare_raw_ocr(image_path)


if __name__ == "__main__":
    main()
