# 织版 WeaveLay

织版 **桌面工具** (JavaFX): PDF 按页 OCR + 左右预览 + 框合并.

> Web 实验代码在 `E:\weavelayUI` / `weavelay-service`，当前主线仍是桌面端.

## 模块

| 模块 | 说明 |
|------|------|
| `weavelay-core` | 合并引擎、页型常量、OcrRow / WeaveRow |
| `weavelay-ocr` | PDF 按页渲染 (PDFBox)、RapidOCR ONNX |
| `weavelay-app` | **JavaFX 桌面入口** (主线) |
| `weavelay-service` | 可选 Web API (实验) |

## 桌面运行 (主线)

```bash
cd E:/WeaveLay
build.bat
run.bat
```

IDEA: 运行配置 **WeaveLay Desktop**（不要用 `javafx:run` 或默认 Run main）.

`run.bat` 内部: JavaFX → `javafx-lib` module-path；rapidocr → `target/lib` classpath.

## 界面


- **选择 PDF / 文件夹** → **OCR + 织版**
- 上方左右对比: **原图** | **OCR 红框标注**
- **上一页 / 下一页** 翻页; 第一页 OCR 完即显示 (不必等全部结束)
- 下方表格: 合并后的 `streamName / seq / feature`

## 与 yansee 的关系

- OCR: `rapidocr` + `rapidocr-onnx-platform` (PP-OCRv4/v5 ONNX)
- PDF 按页渲染: 与 yansee `PdfPngRenderer` 相同流程 (全页、300 DPI), 本工程用 PDFBox 实现

## 环境

- JDK **17** + **JavaFX**（与 `QRStream` 相同；IDEA 里 Project SDK 选 17）
- Maven 3.9+

## 开发运行

**必须从父工程根目录构建**（不要单独对 `weavelay-ocr` / `weavelay-app` 点 package，否则会去私服找 `com.weavelay:*`）:

```bash
cd E:/WeaveLay
build.bat
```

或手动:

```bash
rmdir /s /q %USERPROFILE%\.m2\repository\com\weavelay
mvn clean install -DskipTests -U
```

IDEA: 打开 **E:\\WeaveLay\\pom.xml** 根项目 → Maven 工具窗口选 **weavelay (root)** → **install**。

启动桌面:

```bash
run.bat
```

**不要**用 `mvn javafx:run`（会把 OCR jar 放进 module-path，触发 LoadException）.

IDEA: **WeaveLay Desktop** 运行配置.

若曾报 `was not found in ... maven-public` 且被缓存, 务必加 `-U` 或先执行上面的 `clean install`.

## 输出格式

合并后每行 3 列:

```json
["工艺规程.pdf#p0003", "产品代号ABC-1.2A", 3]
```

- `streamName`: PDF 文件名 + 页码 (`#p0001` 起)
- `feature`: 合并后文本
- `seq`: 该页内从 1 开始的阅读顺序

## 打包 (客户便携版)

在工程根目录执行：

```bat
package-portable.bat
```

或：

```powershell
.\package-portable.ps1
```

产出：`dist\WeaveLay\`（约 600MB+，含精简 JRE、依赖、OCR 模型）。

- 客户解压后双击 `WeaveLay.bat`
- **不含** `weavelay-tools`、私钥、签发脚本
- 授权仍走：设置 → Licence

## 目录

```
E:/WeaveLay/
  weavelay-core/
  weavelay-ocr/
  weavelay-app/
  rules/          规则 JSON (后续)
  samples/        样例 PDF
```
