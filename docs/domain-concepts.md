# WeaveLay2 领域概念说明

## 概述

WeaveLay2 是一个工业规程文档（PDF）的 OCR 提取与结构化输出工具。本文档定义系统中所有核心领域概念及其相互关系。

---

## 1. 文件类型（DocumentFamily）

**定义**：文档的业务类别，不是文件格式（PDF/图片）的区分。

**代码位置**：`weavelay-core/.../page/DocumentFamily.java`

**当前定义**：

| 代码 | 名称 | 说明 |
|------|------|------|
| `process_spec` | 工艺规程 | 工艺规程类 PDF 文档 |

**约束**：
- 系统只接受 PDF 作为输入（`BatchWeaveService` 硬编码校验 `.pdf` 后缀）
- 用户通过 UI 下拉框选择文件类型
- 导出文件名格式：`{文件类型名称}-{PDF文件名}.txt`，如 `工艺规程-abc.txt`

**扩展点**：`DocumentFamily` 可新增枚举值来支持其他文档类别（如检验报告、装配工艺等）。

---

## 2. 页面类型（PageKind）

**定义**：PDF 内每一页的角色分类。一个 PDF 的不同页可能属于不同类型。

**代码位置**：`weavelay-core/.../page/PageKind.java`（枚举），`PageKindDefinition.java`（完整定义）

**七种页面类型**：

| 枚举值 | 中文名 | 说明 |
|--------|--------|------|
| `COVER` | 封面 | 文档封面页 |
| `PROCESS_CATALOG` | 工序目录 | 工序列表/目录页 |
| `PROCESS_DRAWING` | 工序图 | 工序示意图 |
| `MACHINING_CARD` | 机加工序卡 | 机械加工工序卡片 |
| `INSPECTION_CARD` | 检验工序卡 | 检验工序卡片 |
| `AUXILIARY_CARD` | 辅助工序卡 | 辅助工序卡片 |
| `UNKNOWN` | 未识别 | 无法分类的页面 |

**分类机制**：规则匹配，四种锚点语法，所有锚点 AND 逻辑，按优先级依次尝试：

| 锚点语法 | 含义 | 示例 |
|----------|------|------|
| `#N` | 第 N 行排序数据精确匹配页型名称（去空白后比较） | `#1` |
| `~文本` | 任意排序数据行包含该文本 | `~机加工序卡` |
| `&文本` | 任意 OCR 原始数据行包含该文本 | `&工序号` |
| `@N` | 页码定位，正数从前往后，负数从后往前（-1=最后一页） | `@1`、`@-1` |

**PageKindDefinition** 是页面类型的完整配置，捆绑了：
- `PageKind` 枚举值
- 分类锚点列表
- `PageRuleConfig`（合并参数：yThreshold、xThreshold、mergeSep、PairMode、容差）
- `SlotDefinition` 列表（该页要提取的字段定义）

---

## 3. OCR原始数据（OcrRow）

**定义**：OCR 引擎（RapidOCR / PP-OCRv6）直接从页面图片识别出的散碎文字片段，未经任何合并或排序处理。每个片段包含文字内容及其在页面上的像素坐标。

**代码位置**：`weavelay-core/.../ocr/OcrRow.java`

**数据结构**：
```
文字内容 (feature)
像素坐标 (startX, startY, endX, endY)
元数据标记 (meta)
```

**特点**：
- 一个字或几个字一个片段，OCR 引擎输出的最小单位
- 位置精确但阅读顺序是乱的
- 是所有后续处理的唯一数据来源
- 不带行号、不带页号

**在管道中的位置**：PDF → 渲染 PNG → RapidOcrService → **OCR原始数据**

---

## 4. 排序数据（WeaveRow）

**定义**：OCR原始数据按阅读顺序合并、排序后形成的一行文字。每行有行号、文字内容和来源标识。

**代码位置**：`weavelay-core/.../model/WeaveRow.java`

**数据结构**：
```
streamName  — 来源标识（对应 SlotDefinition 的 slotCode）
feature     — 该行的文字内容
seq         — 行号（在页面内的序号）
```

**产生方式**：`RowMerger` 将 OCR原始数据按 Y 坐标分组、按 X 坐标排序，合并相邻片段为行，赋予行号。

**与OCR原始数据的关系**：
- OCR原始数据是零散的碎片 → 排序数据是整理后的行
- 一个排序数据行通常由多个 OCR原始数据片段合并而成
- 排序数据是字段提取的直接输入（通过 `#N` 引用第 N 行）

**在管道中的位置**：OCR原始数据 → RowMerger → **排序数据** → PageExtractor → 最终展示数据

---

## 5. 物理单元格（CellRect）

**定义**：通过 OpenCV 形态学操作检测表格线、切割出的实际像素级表格单元格。这是系统中真正的"单元格"概念，对应图片上的物理格子，而非逻辑字段。

**代码位置**：`weavelay-core/.../layout/CellRect.java`（数据模型），`TableBorderDetector.java`（生产者）

**数据结构**：
```
row, col        — 网格位置（0-based）
x, y            — 格子在图片上的像素坐标（左上角）
width, height   — 格的像素尺寸
rowspan, colspan— 跨度（默认 1,1）
text            — 格内的文字内容（由 OCR原始数据 按重叠面积填进来）
```

**切割流程**（`TableBorderDetector.detectCells()`）：

```
裁剪的表格图片 (PNG bytes)
  → 转灰度 Mat
  → 二值化（OTSU 自适应阈值，反转）
  → 竖线检测：
      - 1×7 kernel 膨胀（强调纵向结构）
      - 1×(rows/10) kernel 开运算（去横向噪声）
      - 逐列统计白像素 → 超过阈值则为竖线位置 → 合并邻近线
  → 横线检测：
      - 7×1 kernel 膨胀（强调横向结构）
      - (cols/10)×1 kernel 开运算（去纵向噪声）
      - 逐行统计白像素 → 超过阈值则为横线位置 → 合并邻近线
  → 插入图片四边边界
  → 线数不足（hLines<2 或 vLines<2）→ 回退到纯文字推断模式
  → buildCellGrid()：
      - 遍历每个网格单元（hLines[r], hLines[r+1], vLines[c], vLines[c+1]）
      - 跳过宽或高 < 8px 的格子
      - 按最大重叠面积将 OCR原始数据 的文字填入对应格子
      - 所有格子 rowspan=1, colspan=1（OpenCV 模式下不做合并）
  → 返回 List<CellRect>
```

**回退路径**：当 OpenCV 检测不到足够表格线时，回退到纯文字推断——解析列头 → 等分列宽 → 按 Y 坐标分行 → 按最近列中心分配。

**特点**：
- **独立管道**：物理单元格不经过「OCR原始数据 → 排序数据 → 最终展示数据」这条通用管道，而是直接从像素层切出来，单独走 Markdown 生成路径
- **中间产物**：不存入最终展示数据，不转为排序数据，只在生成表格输出时使用
- **不合并单元格**：OpenCV 路径下 rowspan/colspan 恒为 1

**消费路径**：

| 场景 | 消费方式 |
|------|----------|
| 交互式验证（用户框选表格区域） | `TablePopupManager.show()` 弹出窗口展示 HTML 渲染表格 |
| 批量导出 | `TablePopupManager.buildTableMarkdown()` 转为 Markdown 字符串写入文件 |

---

## 6. 最终展示数据（SlotValue）

**定义**：UI 界面 TreeTableView 中展示的字段节点，也是最终导出时的数据载体。每个节点是一个提取完成的字段，包含字段名和值。

**代码位置**：`weavelay-core/.../model/SlotValue.java`

**数据结构**：
```
slotCode    — 内部代码（如 "material"、"#1"）
slotLabel   — 显示标签（如 "材料"、"硬度"）
value       — 提取出的值（OCR 文字、LaTeX 字符串，或子字段列表）
labelRow    — 标签对应的 OCR原始数据（用于 UI 高亮定位）
valueRow    — 值对应的 OCR原始数据（用于 UI 高亮定位）
```

**与 SlotDefinition 的区别**：
- `SlotDefinition`：定义**要提取什么**——字段名、类型（text/table）、**值所在像素区域**（模板框选保存）、公式标记
- `SlotValue`：承载**实际提取出的值**

**在 UI 中的组织**：`TreeTableView`
- 普通字段：平铺叶子节点
- 表格字段：父节点 = 表名，子节点 = 各列的最终展示数据

**数据来源**：按字段类型分两条路径汇入：
- **普通字段（主路径）**：模板里为该字段保存的 **value 区域坐标** → 裁图（或整页 OCR 后按区域取字）→ 写入最终展示数据。不是靠「在页面上找标签再旁边捞值」的几何 KV 配对。应用模式可临时再框一版区域纠偏（不写库覆盖）。
- **表格字段**：OpenCV 表格线切物理单元格 → OCR 文字填格 → `buildTableMarkdown()` → 最终展示数据

---

## 7. 公式标识（`$` 前缀）

**定义**：用户在字段标签前加 `$` 来标记该字段的值区域内**含有公式**（不是整个区域都是公式）。系统会先用版式检测定位公式子区域，公式部分送 PP-FormulaNet 识别为 LaTeX，文字部分保留 OCR 结果，最后按阅读顺序拼接。

**代码位置**：`weavelay-core/.../page/SlotDefinition.java`

**三种标记方式**：

| 场景 | 用户写法 | 存储形式（fieldMeta JSON） |
|------|----------|---------------------------|
| 普通字段标记含公式 | `$硬度值` | `{"formula": true}` |
| 表格列标记含公式 | `*参数表:$硬度,$温度,单位` | `{"columns":["硬度","温度","单位"], "formulaColumns":["硬度","温度"]}` |
| 旧数据兼容 | columns 中列名带 `$` | 自动迁移到 `formulaColumns` |

**显示规则**：`$` 在 UI 中被自动吃掉，用户看到的标签是不带 `$` 的（如 `硬度值`）。

**运行时行为**：
1. `BatchWeaveService` 遍历当前页所有 SlotDefinition
2. 检测 `isFormulaField()` 或 `isFormulaColumn()` 为 true 的字段/列
3. 从页面 PNG 中裁剪对应坐标区域的图片
4. **先做版式检测**（PP-DocLayout ONNX，本地模型），检测 `display_formula` / `inline_formula` 子区域
5. 如检测到公式子区域（有效 bbox）：逐个裁剪子区域 → multipart HTTP → Python PP-FormulaNet → LaTeX
6. 将公式区域涂白后，对剩余图做 OCR，得到公式前后的文字片段
7. 文字 OCR 与公式 LaTeX 按阅读顺序（Y 分行、行内按 X）拼接
8. 如未检测到公式坐标：跳过公式识别，保留 OCR
9. 最终结果写入最终展示数据

**版式检测模型**：`PP-DocLayoutV3_ir8.onnx`，DETR 架构，输入 800×800，25 类标签，部署在 `weavelay-ocr/src/main/resources/model/layout/`。
3. 从页面 PNG 中裁剪对应坐标区域的图片
4. 按公式子区域 bbox 裁图，multipart `files` 上传到 `POST /api/formula/recognize`（Python FastAPI，默认 `:8000`）；无公式坐标则跳过识别
5. 返回的 LaTeX 字符串替换 OCR 文字，写入最终展示数据

**触发条件**：
- SlotDefinition 必须显式标记公式字段
- 版式检测模型（PP-DocLayout ONNX）由 Java 本地加载，无需额外服务
- 公式识别（PP-FormulaNet）需要 Python 服务（默认 `http://127.0.0.1:8000`）

---

## 8. 普通输出（fieldType = "text"）

**定义**：字段类型为 `"text"`（默认值）的输出，即简单的键值对展示（字段名 → 值）。

**代码位置**：`weavelay-core/.../page/SlotDefinition.java`

**特征**：
- `fieldType = "text"`（默认值）
- 导出格式：`字段名: 值`
- **取值方式（主路径）**：模板模式框选并保存该字段的 **value 像素区域**（`regionX/Y/W/H`）。应用时按区域裁图/取字 OCR，填入 `SlotValue`。  
  「Key」是槽位名（用户定义的字段），「Value」是该区域里认出的字——**对应关系在模板里定死**，不是运行时用 Label 几何去配对。
- 应用模式可对本页临时重框区域再识别（纠偏）；持久规则仍以模板保存的坐标为准。

**用户定义格式**（`SlotDefinitionFormat`）：
```
字段名=#N      # 绑定到排序数据第 N 行（少用）
字段名          # 待绑定占位；随后在模板模式框选 value 区域并保存规则
key=label      # 旧版键值格式
```

**公式标记的普通字段**：仍然是 `fieldType = "text"`，但 `fieldMeta = {"formula": true}`。区别仅在于值的来源——OCR 文字 vs LaTeX 识别结果。导出格式不变（`字段名: LaTeX字符串`）。

**勿混淆**：代码里仍存在 `LabelValuePairer` 等「按标签位置附近捞值」的旧/批处理提取路径（见 `architecture.md`），**不是**当前桌面「应用」主流程。主流程是模板区域裁图 OCR。

---

## 9. 表格输出（fieldType = "table"）

**定义**：字段类型为 `"table"` 的输出，以 Markdown 表格形式导出。数据来源是物理单元格。

**用户定义方式**：
```
*表格名:列1,列2,列3
*参数表:$硬度,$温度,单位        # 列名前的 $ 标记该列走公式识别
```

**导出格式**：
```
**表格名**

| 列1 | 列2 | 列3 |
| --- | --- | --- |
| 值1 | 值2 | 值3 |
```

**与物理单元格的关系**：物理单元格是数据来源（OpenCV 切出来的格子），表格输出是最终呈现（Markdown 字符串）。两者是上下游关系。

**表格识别的两条路径**：

| | TableRecognizer（纯文字） | TableBorderDetector（OpenCV） |
|---|---|---|
| **策略** | OCR原始数据 按 Y 分行 + 按 X 对齐列头 | 形态学检测物理表格线 → 切格网 |
| **依赖** | 仅 OCR原始数据 | OCR原始数据 + 裁剪图片 |
| **何时使用** | 物理单元格为空/无效时的回退；无列头定义时的自动检测 | 所有 `fieldType="table"` 字段的默认路径 |
| **产物** | 直接生成表格文字数组 | 物理单元格格网 |
| **单元格合并** | 合并连续单列行为多行文字 | 不做合并（rowspan/colspan 恒为 1） |

---

## 10. 输出

**定义**：系统最终产物的格式和结构。

**产物格式**：结构化纯文本 `.txt` 文件（另有 JSON 导出用于程序消费）。

**文件名**：`{文件类型名称}-{PDF文件名}.txt`

**文件结构**：
```
=== 第 N 页 ===

页型: <页面类型中文名>

# 普通字段：
字段名: 值

# 表格字段：
**表格名**

| 列1 | 列2 | 列3 |
| --- | --- | --- |
| 值1 | 值2 | 值3 |

# 特殊标记：
# 页型未识别       （PageKind 分类失败）
# OCR无结果        （该页 OCR 未检出任何文字）
```

**JSON 导出**：`BatchWeaveService.exportJson()` 导出 `[streamName, feature, seq]` 三元组数组。

---

## 概念关系总图

```
文件类型（DocumentFamily）
  │  当前唯一：工艺规程
  │
  └── PageKindDefinition（每种页面类型的完整定义）
        │
        ├── 页面类型（PageKind 枚举）
        │     COVER / PROCESS_CATALOG / PROCESS_DRAWING /
        │     MACHINING_CARD / INSPECTION_CARD / AUXILIARY_CARD / UNKNOWN
        │
        ├── 分类锚点（#N / ~文本 / &文本 / @N）
        │
        ├── PageRuleConfig（合并参数）
        │
        └── SlotDefinition[]（字段定义）
              │
              ├── fieldType = "text"
              │     ├── 无 $ → 普通输出（label: value）
              │     └── 有 $ → 公式识别 → LaTeX 替换 OCR 文字
              │               → 输出格式仍为 label: value
              │
              └── fieldType = "table"
                    └── 数据来源：物理单元格（OpenCV 表格线切割）
                          │
                          ├── 有 $ 标记的列 → 公式识别 → LaTeX
                          └── → buildTableMarkdown() → Markdown 表格输出
```

## 数据处理管道

```
PDF 文件
  │
  ├─→ PDFBox 渲染为 PNG（每页一张）
  │
  ├─→ RapidOcrService（Java 本地 ONNX，PP-OCRv6）
  │      └─→ OCR原始数据（OcrRow）
  │            散碎文字片段 + 像素坐标
  │
  ├─→ RowMerger
  │      └─→ 排序数据（WeaveRow）
  │            按阅读顺序合并为行，赋予行号
  │            【旧/批处理与部分展示用；桌面应用主路径不靠此做字段取值】
  │
  ├─→ [桌面主路径] 应用 / 模板区域识别
  │      └─→ 页型由用户手动选择
  │      └─→ 加载该页型 SlotDefinition（含模板保存的 value 区域）
  │      └─→ 普通字段：按区域取字/裁图 OCR → SlotValue
  │      └─→ 表格字段：区域图 → OpenCV 线框切格 → OCR 填格 → Markdown → SlotValue
  │      └─→ 应用模式可临时重框区域纠偏（不依赖「标签旁捞值」）
  │
  ├─→ [旧/批处理路径] PageExtractor（Cover / ProcessCatalog / …）
  │      └─→ 仍可能使用 LabelValuePairer 等几何配对（非桌面应用主路径）
  │
  ├─→ [条件触发] 公式识别（$ 标记字段）
  │      └─→ 裁剪字段区域图片
  │      └─→ PP-DocLayout ONNX 版式检测（本地模型）
  │      └─→ 检测到公式子区域 → 裁剪 → HTTP → Python PP-FormulaNet → LaTeX
  │      └─→ 文字部分保留 OCR，公式部分替换为 LaTeX，按阅读顺序拼接
  │
  ├─→ [表格字段专属] TableBorderDetector.detectCells()
  │      └─→ OpenCV 形态学 → 检测横竖线 → 切割
  │      └─→ 物理单元格（CellRect）格网
  │             【独立管道：不经过排序数据，直接从像素层切出】
  │      └─→ buildTableMarkdown() → Markdown 字符串
  │
  └─→ 导出
       ├─→ .txt（结构化文本：label: value + Markdown 表格）
       └─→ .json（[streamName, feature, seq] 三元组）
```

## 数据层概念对照表

| 中文名 | 代码类 | 层级 | 说明 |
|--------|--------|------|------|
| OCR原始数据 | `OcrRow` | 原始层 | OCR 引擎直接输出，散碎文字片段+坐标，未做任何加工 |
| 排序数据 | `WeaveRow` | 整理层 | OCR原始数据按阅读顺序合并排序后的行，有行号、有顺序 |
| 物理单元格 | `CellRect` | 物理层 | OpenCV 检测表格线后切割出的像素级格网，表格字段专属 |
| 最终展示数据 | `SlotValue` | 成品层 | UI 展示和文件导出的最终数据，所有管道最终汇入此处 |
