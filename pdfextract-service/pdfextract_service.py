"""
PDF-Extract-Kit HTTP 服务: 布局检测 + OCR + 公式 + 表格.

启动:
    python pdfextract_service.py --port 8765

API:
    GET  /health          → {"status":"ready"}
    POST /api/extract     → {"page_info":{...},"layout_dets":[...]}
"""

import sys
import json
import base64
import io
import argparse
import logging
from pathlib import Path

import flask
from flask import Flask, request, jsonify

app = Flask(__name__)
logger = logging.getLogger("pdfextract")

# ── 全局 pipeline 引用 ──
_pipeline = None
_formula_service = None  # PP-FormulaNet + MFD


# ====================================================================
# Pipeline 封装 (PDF-Extract-Kit)
# ====================================================================

class PdfExtractPipeline:
    """按需加载 PDF-Extract-Kit 各模块, 对单张 PNG 执行完整管线."""

    def __init__(self, config_dir: str = None):
        self.config_dir = Path(config_dir) if config_dir else Path(__file__).parent / "configs"
        self._layout_model = None
        self._ocr = None
        self._formula_detector = None
        self._formula_recognizer = None
        self._table_parser = None
        self._ready = False

    def load(self):
        """加载所有模型 (耗时 30s-2min)."""
        logger.info("加载 PDF-Extract-Kit 模型...")

        try:
            from pdf_extract_kit.pipeline import Pipeline
            self._pipeline = Pipeline()
            self._ready = True
            logger.info("PDF-Extract-Kit Pipeline 就绪")
        except ImportError:
            logger.warning("pdf_extract_kit 未安装, 使用兜底模式 (仅 OCR)")
            self._init_fallback()
            self._ready = True

    def _init_fallback(self):
        """兜底: 用 PaddleOCR 做基础文本提取, 无布局/公式/表格."""
        try:
            from paddleocr import PaddleOCR
            self._ocr = PaddleOCR(lang='ch', use_angle_cls=False, show_log=False)
            logger.info("PaddleOCR 兜底就绪")
        except ImportError:
            logger.error("PaddleOCR 也未安装, 服务将无法工作")
            self._ocr = None

    @property
    def ready(self) -> bool:
        return self._ready

    def extract(self, image_bytes: bytes, page_no: int = 0) -> dict:
        """
        对单张图片执行完整提取管线.

        Returns:
            dict  {"page_info": {...}, "layout_dets": [{...}, ...]}
        """
        from PIL import Image
        img = Image.open(io.BytesIO(image_bytes))
        w, h = img.size

        layout_dets = []

        if hasattr(self, '_pipeline') and self._pipeline is not None:
            # ── PDF-Extract-Kit 完整管线 ──
            import tempfile, os
            with tempfile.NamedTemporaryFile(suffix='.png', delete=False) as f:
                f.write(image_bytes)
                tmp_path = f.name

            try:
                result = self._pipeline.run(tmp_path)
                # result 格式取决于 pdf_extract_kit 版本, 这里做适配
                layout_dets = self._normalize_result(result, w, h)
            finally:
                os.unlink(tmp_path)

        elif self._ocr is not None:
            # ── PaddleOCR 兜底 ──
            import numpy as np
            img_array = np.array(img)
            ocr_result = self._ocr.ocr(img_array)
            if ocr_result and ocr_result[0]:
                for line in ocr_result[0]:
                    box, (text, score) = line
                    # box: [[x1,y1],[x2,y2],[x3,y3],[x4,y4]]
                    poly = [
                        box[0][0], box[0][1],
                        box[1][0], box[1][1],
                        box[2][0], box[2][1],
                        box[3][0], box[3][1],
                    ]
                    layout_dets.append({
                        "category_type": "text",
                        "poly": poly,
                        "score": float(score),
                        "text": text,
                    })

        return {
            "pageInfo": {
                "pageNo": page_no,
                "width": w,
                "height": h,
            },
            "layoutDets": layout_dets,
        }

    def _normalize_result(self, raw, img_w, img_h) -> list:
        """将 PDF-Extract-Kit 的原始输出标准化为 layout_dets."""
        dets = []

        # 尝试多种可能的输出格式
        items = None
        if isinstance(raw, list):
            items = raw
        elif isinstance(raw, dict):
            items = raw.get("layout_dets") or raw.get("blocks") or raw.get("elements") or []

        if not items:
            return dets

        for item in items:
            if not isinstance(item, dict):
                continue

            category = item.get("category_type") or item.get("type") or item.get("label") or "text"
            poly = item.get("poly") or item.get("bbox") or item.get("box")
            score = item.get("score") or item.get("confidence") or 1.0
            text = item.get("text") or item.get("content") or item.get("latex") or ""

            # bbox → poly 转换
            if poly and isinstance(poly, (list, tuple)) and len(poly) == 4 and isinstance(poly[0], (int, float)):
                x, y, w_box, h_box = poly
                poly = [x, y, x + w_box, y, x + w_box, y + h_box, x, y + h_box]

            if poly is None:
                poly = [0, 0, float(img_w), 0, float(img_w), float(img_h), 0, float(img_h)]

            dets.append({
                "category_type": str(category),
                "poly": [float(v) for v in poly],
                "score": float(score) if score else 1.0,
                "text": str(text) if text else "",
            })

        return dets


# ====================================================================
# Formula 识别 (PP-FormulaNet ONNX + MFD YOLO)
# ====================================================================

class FormulaRecognitionService:
    """PP-FormulaNet（识别）+ 可选 MFD YOLO（仅 /api/formula/extract 需要）."""

    # 桌面端版式检测已在 JVM 完成，recognize 路径不加载 YOLO，省内存。
    LOAD_MFD = False

    def __init__(self):
        self._mfd = None
        self._backbone = None
        self._head = None
        self._pp_ops = None
        self._pp_post = None
        self._pp_config = None
        self._ready = False

    def load(self):
        logger.info("加载公式识别模型...")
        try:
            import os, sys, yaml
            import numpy as np
            import onnxruntime as ort

            PP_FORMULA_DIR = r"E:\IdeaProjects\pp_formula_to_onnx"
            sys.path.insert(0, PP_FORMULA_DIR)

            # MFD YOLO —— 仅 extract 端点需要；WeaveLay 桌面走 recognize，默认不加载
            # from ultralytics import YOLO
            # mfd_path = r"E:\IdeaProjects\PDF-Extract-Kit\models\MFD\YOLO\yolo_v8_ft.pt"
            # self._mfd = YOLO(mfd_path)
            # logger.info(f"MFD loaded: {self._mfd.names}")
            if self.LOAD_MFD:
                from ultralytics import YOLO
                mfd_path = r"E:\IdeaProjects\PDF-Extract-Kit\models\MFD\YOLO\yolo_v8_ft.pt"
                self._mfd = YOLO(mfd_path)
                logger.info(f"MFD loaded: {self._mfd.names}")
            else:
                logger.info("已跳过 MFD YOLO（LOAD_MFD=False）")

            # PP-FormulaNet ONNX — must chdir for relative paths
            _cwd = os.getcwd()
            os.chdir(PP_FORMULA_DIR)
            try:
                config_path = os.path.join(PP_FORMULA_DIR, "configs", "rec", "PP-FormuaNet",
                                           "PP-FormulaNet_plus-M_ONNX.yaml")
                with open(config_path, 'r') as f:
                    config = yaml.safe_load(f)
                self._pp_config = config["Global"]

                ort.set_default_logger_severity(4)
                backbone_path = os.path.join(PP_FORMULA_DIR, "output", "onnx_models", "backbone.onnx")
                head_path = os.path.join(PP_FORMULA_DIR, "output", "onnx_models", "head_fixed.onnx")
                self._backbone = ort.InferenceSession(backbone_path)
                self._head = ort.InferenceSession(head_path)

                from ppocr.postprocess import build_post_process
                self._pp_post = build_post_process(config["PostProcess"], self._pp_config)

                from ppocr.data import create_operators
                transforms = []
                for op in config["Eval"]["dataset"]["transforms"]:
                    op_name = list(op)[0]
                    if "Label" in op_name:
                        continue
                    elif op_name == "KeepKeys":
                        op[op_name]["keep_keys"] = ["image"]
                    transforms.append(op)
                self._pp_ops = create_operators(transforms, self._pp_config)
            finally:
                os.chdir(_cwd)

            self._ready = True
            logger.info("公式识别模型就绪")
        except Exception as e:
            logger.error(f"公式识别模型加载失败: {e}")
            self._ready = False

    @property
    def ready(self):
        return self._ready

    def recognize(self, img_bytes: bytes) -> str:
        """单张公式图片 → LaTeX."""
        import tempfile, os
        import numpy as np, paddle
        from ppocr.data import transform as pp_transform

        with tempfile.NamedTemporaryFile(suffix='.png', delete=False) as tmp:
            tmp.write(img_bytes)
            tmp_path = tmp.name

        try:
            data = {"image": img_bytes, "filename": tmp_path}
            batch = pp_transform(data, self._pp_ops)
            images = np.expand_dims(batch[0], axis=0).astype(np.float32)
            if images.shape[1] == 1:
                images = np.repeat(images, 3, axis=1)

            enc = self._backbone.run(None, {self._backbone.get_inputs()[0].name: images})[0]
            gen = np.array([[0]], dtype=np.int64)
            max_len = self._pp_config.get("max_new_tokens", 1536)

            for _ in range(max_len):
                feed = {}
                for inp in self._head.get_inputs():
                    if 'decoder' in inp.name.lower() or 'input' in inp.name.lower():
                        feed[inp.name] = gen.astype(np.int64)
                    else:
                        feed[inp.name] = enc
                logits = self._head.run(None, feed)[0]
                nt = (np.argmax(logits, axis=-1) if logits.ndim == 2
                      else np.argmax(logits[:, -1:, :], axis=-1))
                nt = nt.reshape(-1, 1)
                gen = np.concatenate([gen, nt], axis=-1)
                if np.all(nt.flatten() == 2):
                    break

            pred = paddle.to_tensor(gen)
            result = self._pp_post(pred)
            return result[0] if result else ""
        finally:
            os.unlink(tmp_path)

    def extract(self, img_bytes: bytes, conf_thres: float = 0.25) -> dict:
        """MFD 检测 + PP-FormulaNet 识别, 返回公式坐标和 LaTeX."""
        import io
        from PIL import Image

        img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
        w, h = img.size

        formulas = []
        # MFD 未加载时直接整图识别（桌面端默认）
        if self._mfd is not None:
            results = self._mfd.predict(
                img, imgsz=640, conf=conf_thres, iou=0.45, device="cpu", verbose=False
            )[0]
            boxes = results.boxes
            if boxes is not None and len(boxes) > 0:
                for i in range(len(boxes)):
                    cls = self._mfd.names[int(boxes.cls[i])]
                    score = float(boxes.conf[i])
                    x0, y0, x1, y1 = [int(v) for v in boxes.xyxy[i].tolist()]
                    x0, y0 = max(0, x0), max(0, y0)
                    x1, y1 = min(w, x1), min(h, y1)
                    crop = img.crop((x0, y0, x1, y1))
                    buf = io.BytesIO()
                    crop.save(buf, format="PNG")
                    latex = self.recognize(buf.getvalue())
                    formulas.append({
                        "x1": x0, "y1": y0, "x2": x1, "y2": y1,
                        "category": cls, "score": round(score, 4), "latex": latex,
                    })

        if not formulas:
            # MFD 没找到 / 未加载 → 整张图当公式
            latex = self.recognize(img_bytes)
            formulas = [{
                "x1": 0, "y1": 0, "x2": w, "y2": h,
                "category": "formula", "score": 1.0, "latex": latex,
            }]

        return {"width": w, "height": h, "formulas": formulas}


# ====================================================================
# Flask 路由
# ====================================================================

@app.route('/health', methods=['GET'])
def health():
    formula_ok = _formula_service is not None and _formula_service.ready
    pipeline_ok = _pipeline is not None and _pipeline.ready
    # 公式服务就绪即可；Kit 管线可选
    if not formula_ok and not pipeline_ok:
        return jsonify({"status": "loading"}), 503
    return jsonify({
        "status": "ready",
        "pipeline": pipeline_ok,
        "formula": formula_ok,
    })


@app.route('/api/extract', methods=['POST'])
def extract():
    """接收 {"image": "<base64>", "stream_name": "..."}, 返回 PDF-Extract-Kit 结果."""
    data = request.get_json(force=True)
    if not data or 'image' not in data:
        return jsonify({"error": "缺少 image 字段 (base64)"}), 400

    try:
        image_bytes = base64.b64decode(data['image'])
    except Exception as e:
        return jsonify({"error": f"base64 解码失败: {e}"}), 400

    stream_name = data.get('stream_name', 'page')
    page_no = 0
    try:
        page_no = int(data.get('page_no', 0))
    except (ValueError, TypeError):
        pass

    if _pipeline is None:
        return jsonify({"error": "Pipeline 未加载"}), 503

    try:
        result = _pipeline.extract(image_bytes, page_no=page_no)
        logger.info(f"[{stream_name}] 提取完成: {len(result.get('layoutDets', []))} 个布局块")
        return jsonify(result)
    except Exception as e:
        logger.exception(f"[{stream_name}] 提取失败")
        return jsonify({"error": str(e)}), 500


# ====================================================================
# Formula 路由
# ====================================================================

@app.route('/api/formula/recognize', methods=['POST'])
def formula_recognize():
    """识别单张公式图片 → LaTeX."""
    if _formula_service is None or not _formula_service.ready:
        return jsonify({"error": "公式识别服务未就绪"}), 503

    data = request.get_json(force=True)
    if not data or 'image' not in data:
        return jsonify({"error": "缺少 image 字段 (base64)"}), 400

    try:
        img_bytes = base64.b64decode(data['image'])
    except Exception as e:
        return jsonify({"error": f"base64 解码失败: {e}"}), 400

    try:
        latex = _formula_service.recognize(img_bytes)
        return jsonify({"latex": latex})
    except Exception as e:
        logger.exception("公式识别失败")
        return jsonify({"error": str(e)}), 500


@app.route('/api/formula/extract', methods=['POST'])
def formula_extract():
    """MFD 检测 + PP-FormulaNet 识别, 返回公式坐标和 LaTeX."""
    if _formula_service is None or not _formula_service.ready:
        return jsonify({"error": "公式识别服务未就绪"}), 503

    data = request.get_json(force=True)
    if not data or 'image' not in data:
        return jsonify({"error": "缺少 image 字段 (base64)"}), 400

    try:
        img_bytes = base64.b64decode(data['image'])
    except Exception as e:
        return jsonify({"error": f"base64 解码失败: {e}"}), 400

    conf_thres = float(data.get('conf_thres', 0.25))

    try:
        result = _formula_service.extract(img_bytes, conf_thres)
        return jsonify(result)
    except Exception as e:
        logger.exception("公式提取失败")
        return jsonify({"error": str(e)}), 500


# ====================================================================
# 启动
# ====================================================================

def main():
    parser = argparse.ArgumentParser(description="PDF-Extract-Kit HTTP Service")
    parser.add_argument('--port', type=int, default=8765, help='监听端口 (默认 8765)')
    parser.add_argument('--host', type=str, default='127.0.0.1', help='监听地址')
    parser.add_argument('--config', type=str, default=None, help='PDF-Extract-Kit 配置目录')
    parser.add_argument(
        '--preload', action='store_true',
        help='预加载 PDF-Extract-Kit/PaddleOCR（桌面公式用不到，默认不开）')
    parser.add_argument('--no-formula', action='store_true', help='跳过公式识别模型')
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format='[%(asctime)s] %(levelname)s %(message)s')

    global _pipeline, _formula_service

    # WeaveLay 桌面端公式链路只打 /api/formula/recognize，不走 /api/extract。
    # Kit/PaddleOCR 整管线默认不加载，省内存；要用提取时加 --preload。
    _pipeline = PdfExtractPipeline(args.config)
    if args.preload:
        logger.info("预加载 PDF-Extract-Kit / PaddleOCR（体积大）...")
        _pipeline.load()
        logger.info("预加载完成" if _pipeline.ready else "预加载失败, 将 lazy load")
    else:
        logger.info("已跳过 Kit/PaddleOCR 预加载（默认；需要时加 --preload）")
        _pipeline._ready = False

    if not args.no_formula:
        _formula_service = FormulaRecognitionService()
        _formula_service.load()

    # 打印就绪信号 (Java 侧解析)
    print(f"READY:{args.port}", flush=True)

    app.run(host=args.host, port=args.port, debug=False, threaded=True)


if __name__ == '__main__':
    main()
