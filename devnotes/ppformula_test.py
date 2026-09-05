"""
PP-Formula 测试：对比 PP-OCRv6 与 PP-Formula 对工业符号的识别效果。

用法:
  1. 装依赖：pip install paddlepaddle paddleocr
  2. 运行：python ppformula_test.py
"""
import cv2
import numpy as np
from paddleocr import PaddleOCR

IMAGE_PATH = "E:/WeaveLay2/docs/1.png"


def test_ppocr_v6(img: np.ndarray):
    """标准 PP-OCRv6 (det + rec)，输出纯文本"""
    ocr = PaddleOCR(lang="ch", use_angle_cls=True, ocr_version="PP-OCRv4")
    # 注意: paddleocr 的 PP-OCRv4 实际上就是最新版, 等价于 v6 的 rec 模型
    results = ocr.ocr(img, cls=True)
    print("=" * 60)
    print("PP-OCR 标准识别结果:")
    print("-" * 60)
    if results and results[0]:
        for line in results[0]:
            box, (text, score) = line
            print(f"  [{score:.3f}] {text}")
    else:
        print("  (无结果)")
    print()


def test_formula_recognition(img: np.ndarray):
    """PP-Formula: 对整图/区域做公式识别，输出 LaTeX"""
    from paddleocr.ppstructure.utility import parse_args
    from paddleocr.ppstructure.formula.controller import FormulaRecognitionController

    print("=" * 60)
    print("PP-Formula 识别结果 (LaTeX):")
    print("-" * 60)

    # 保存临时文件
    tmp_path = "/tmp/ppformula_test.png"
    cv2.imwrite(tmp_path, img)

    try:
        controller = FormulaRecognitionController()
        result = controller(tmp_path)
        if result:
            for item in result:
                print(f"  {item}")
        else:
            print("  (无结果)")
    except Exception as e:
        print(f"  PP-Formula 不可用: {e}")
        print("  尝试使用 PaddleOCR 表格+公式 pipeline...")
        test_structure_formula(img)
    print()


def test_structure_formula(img: np.ndarray):
    """PP-Structure 的公式识别模式"""
    ocr = PaddleOCR(
        lang="ch",
        use_angle_cls=True,
        ocr_version="PP-OCRv4",
        structure_version="PP-Structurev2",
    )
    tmp_path = "/tmp/ppformula_test.png"
    cv2.imwrite(tmp_path, img)

    try:
        results = ocr.ocr(tmp_path, cls=True, structure=True)
        if results:
            print("PP-Structure 结果:")
            for item in results:
                print(f"  {item}")
        else:
            print("  (PP-Structure 无结果)")
    except Exception as e:
        print(f"  PP-Structure 不可用: {e}")


def main():
    img = cv2.imread(IMAGE_PATH)
    if img is None:
        print(f"无法读取图片: {IMAGE_PATH}")
        return
    print(f"图片尺寸: {img.shape[1]}x{img.shape[0]}")
    print()

    test_ppocr_v6(img)
    test_formula_recognition(img)


if __name__ == "__main__":
    main()
