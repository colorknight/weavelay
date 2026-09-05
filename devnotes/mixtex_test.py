"""
MixTex ONNX 测试: encoder_model.onnx + decoder_model_merged.onnx
架构: Swin-Transformer encoder + RoBERTa decoder (w/ KV cache)

用法:
  1. pip install onnxruntime transformers pillow numpy
  2. python devnotes/mixtex_test.py <图片路径>
"""

import argparse
import os
import sys
import time
import numpy as np
from PIL import Image

import onnxruntime as ort

# === 模型参数 (来自 HuggingFace MixTex/ZhEn-Latex-OCR) ===
IMAGE_SIZE = (224, 224)  # ONNX 输出固定 49 patches = 7×7, 必须 224
BOS_TOKEN_ID = 0
EOS_TOKEN_ID = 25678  # 实际 tokenizer 的 eos_token_id, 不是 config 里的 2!
PAD_TOKEN_ID = 1
VOCAB_SIZE = 30002  # ONNX 实际输出维度
ENCODER_HIDDEN = 768
MAX_TOKENS = 296

MODEL_DIR = os.path.join(os.path.dirname(__file__), "..", "weavelay-ocr", "models", "mixtex")


def load_models():
    """加载 encoder 和 decoder ONNX 模型."""
    enc_path = os.path.join(MODEL_DIR, "encoder_model.onnx")
    dec_path = os.path.join(MODEL_DIR, "decoder_model_merged.onnx")

    # 关闭冗余日志
    ort.set_default_logger_severity(3)

    enc_sess = ort.InferenceSession(enc_path, providers=["CPUExecutionProvider"])
    dec_sess = ort.InferenceSession(dec_path, providers=["CPUExecutionProvider"])

    print(f"[MixTex] encoder 输入: {[i.name for i in enc_sess.get_inputs()]}")
    print(f"[MixTex] decoder 输入: {[i.name for i in dec_sess.get_inputs()]}")

    return enc_sess, dec_sess


def preprocess(image_path: str) -> np.ndarray:
    """预处理: 读图 → RGB → resize 224x224 → normalize (ImageNet)."""
    img = Image.open(image_path).convert("RGB")
    img = img.resize(IMAGE_SIZE, Image.BICUBIC)

    # 转 numpy, normalize: (pixel/255 - 0.5) / 0.5 → [-1, 1]
    arr = np.array(img).astype(np.float32) / 255.0
    arr = (arr - 0.5) / 0.5

    # HWC → CHW → BCHW
    arr = np.transpose(arr, (2, 0, 1))
    arr = np.expand_dims(arr, axis=0)
    return arr.astype(np.float32)


def load_tokenizer():
    """加载 MixTex tokenizer (RoBERTa byte-level BPE)."""
    try:
        from transformers import AutoTokenizer
        tok = AutoTokenizer.from_pretrained("MixTex/ZhEn-Latex-OCR")
        print(f"[MixTex] tokenizer 已加载 vocab={tok.vocab_size} "
              f"bos={tok.bos_token_id} eos={tok.eos_token_id}")
        return tok
    except Exception as e:
        print(f"[MixTex] 在线加载失败: {e}")
        print("[MixTex] 尝试从本地缓存加载...")
        try:
            # 尝试从默认缓存路径加载
            from transformers import AutoTokenizer
            tok = AutoTokenizer.from_pretrained("MixTex/ZhEn-Latex-OCR", local_files_only=True)
            print(f"[MixTex] tokenizer 从缓存加载成功")
            return tok
        except Exception:
            sys.exit("[MixTex] 无法加载 tokenizer, 请检查网络后重试")


def run_encoder(enc_sess, pixel_values):
    """Encoder: 图片 → last_hidden_state [1, 49, 768]."""
    out = enc_sess.run(None, {"pixel_values": pixel_values})
    return out[0]


def run_decoder_step(dec_sess, input_ids, encoder_hidden, past_kv=None):
    """Decoder 单步推理 (支持 KV cache). 首次调用不传 past_kv."""
    feed = {
        "input_ids": input_ids,
        "encoder_hidden_states": encoder_hidden,
        "use_cache_branch": np.array([True], dtype=bool),
    }
    if past_kv is not None:
        for i, (k, v) in enumerate(past_kv):
            feed[f"past_key_values.{i}.key"] = k
            feed[f"past_key_values.{i}.value"] = v
    else:
        # 首次调用: 传空 KV cache (past_sequence_length=0)
        empty_kv = np.zeros((1, 12, 0, 64), dtype=np.float32)
        for i in range(3):
            feed[f"past_key_values.{i}.key"] = empty_kv
            feed[f"past_key_values.{i}.value"] = empty_kv

    outputs = dec_sess.run(None, feed)

    # 输出: logits, present.0.key, present.0.value, ... present.2.key, present.2.value
    logits = outputs[0]
    new_kv = [(outputs[1], outputs[2]), (outputs[3], outputs[4]), (outputs[5], outputs[6])]

    return logits, new_kv


def generate(enc_sess, dec_sess, pixel_values, tokenizer):
    """自回归生成 LaTeX 文本."""
    # Encoder
    t0 = time.time()
    encoder_hidden = run_encoder(enc_sess, pixel_values)
    print(f"[MixTex] encoder 输出 shape={encoder_hidden.shape} 耗时 {time.time()-t0:.1f}s")

    # Decoder 自回归 (带 KV cache)
    generated_ids = []
    current_id = BOS_TOKEN_ID
    past_kv = None
    t1 = time.time()

    for step in range(MAX_TOKENS):
        input_ids = np.array([[current_id]], dtype=np.int64)
        logits, past_kv = run_decoder_step(
            dec_sess, input_ids, encoder_hidden, past_kv
        )
        # logits shape: [1, 1, 30002], 但 vocab 只有 25678, 截断避免选到 padding
        logits_valid = logits[0, -1, :tokenizer.vocab_size]
        next_id = int(np.argmax(logits_valid))
        generated_ids.append(current_id)
        if current_id == EOS_TOKEN_ID:
            break
        current_id = next_id

    elapsed = time.time() - t1
    print(f"[MixTex] 生成 {len(generated_ids)} tokens 耗时 {elapsed:.1f}s "
          f"({len(generated_ids)/elapsed:.1f} tok/s)")

    # 解码
    if hasattr(tokenizer, "decode"):
        text = tokenizer.decode(generated_ids, skip_special_tokens=True)
    else:
        text = tokenizer.decode(generated_ids)

    # 后处理: 替换 \[ \] 为 align* 环境
    text = text.replace("\\[", "\\begin{align*}").replace("\\]", "\\end{align*}")
    return text


def main():
    parser = argparse.ArgumentParser(description="MixTex ONNX 测试")
    parser.add_argument("image", help="图片路径")
    parser.add_argument("--output", "-o", default=None, help="保存结果到文件")
    args = parser.parse_args()

    if not os.path.exists(args.image):
        sys.exit(f"图片不存在: {args.image}")

    print(f"[MixTex] 加载模型...")
    enc_sess, dec_sess = load_models()
    tokenizer = load_tokenizer()

    print(f"[MixTex] 推理: {args.image}")
    pixel_values = preprocess(args.image)
    print(f"[MixTex] 图片预处理完成 shape={pixel_values.shape}")

    result = generate(enc_sess, dec_sess, pixel_values, tokenizer)

    print("=" * 60)
    print(result)
    print("=" * 60)

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(result)
        print(f"[MixTex] 结果已保存: {args.output}")


if __name__ == "__main__":
    main()
