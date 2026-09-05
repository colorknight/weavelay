"""
GOT-OCR2.0 测试: 对比现有 PP-FormulaNet 方案, 测试混合公式+文字的识别效果.

用法:
  1. 装依赖: pip install torch transformers accelerate flash-attn --no-build-isolation
  2. 首次运行会自动从 HuggingFace 下载模型 (~2GB), 需要网络.
  3. python devnotes/got_ocr_test.py <图片路径>

示例:
  python devnotes/got_ocr_test.py docs/1.png
  python devnotes/got_ocr_test.py docs/1.png --ocr_type format
  python devnotes/got_ocr_test.py docs/1.png --output result.txt
"""

import argparse
import os
import sys
import time


def test_got_ocr(image_path: str, ocr_type: str = "ocr", output_file: str = None):
    """用 GOT-OCR2.0 识别图片, 输出文本/格式化内容."""

    print(f"[GOT] 加载模型...")
    t0 = time.time()

    from transformers import AutoModel, AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(
        "ucaslcl/GOT-OCR2_0", trust_remote_code=True
    )
    model = AutoModel.from_pretrained(
        "ucaslcl/GOT-OCR2_0",
        trust_remote_code=True,
        low_cpu_mem_usage=True,
        device_map="cuda" if _has_cuda() else "cpu",
        use_safetensors=True,
        pad_token_id=tokenizer.eos_token_id,
    )
    model = model.eval()
    if _has_cuda():
        model = model.cuda()

    print(f"[GOT] 模型加载完成, 耗时 {time.time() - t0:.1f}s")

    # 推理
    print(f"[GOT] 推理中 ocr_type={ocr_type} image={image_path}...")
    t1 = time.time()

    result = model.chat(tokenizer, image_path, ocr_type=ocr_type)

    elapsed = time.time() - t1
    print(f"[GOT] 推理完成, 耗时 {elapsed:.1f}s")
    print("=" * 60)
    print(result)
    print("=" * 60)

    if output_file:
        with open(output_file, "w", encoding="utf-8") as f:
            f.write(result)
        print(f"[GOT] 结果已保存: {output_file}")

    return result


def _has_cuda() -> bool:
    try:
        import torch
        return torch.cuda.is_available()
    except ImportError:
        return False


def main():
    parser = argparse.ArgumentParser(description="GOT-OCR2.0 测试")
    parser.add_argument("image", help="图片路径")
    parser.add_argument(
        "--ocr_type",
        default="format",
        choices=["ocr", "format"],
        help="ocr=纯文本, format=格式化输出(含LaTeX)",
    )
    parser.add_argument("--output", "-o", default=None, help="保存结果到文件")
    args = parser.parse_args()

    if not os.path.exists(args.image):
        sys.exit(f"图片不存在: {args.image}")

    test_got_ocr(args.image, args.ocr_type, args.output)


if __name__ == "__main__":
    main()
