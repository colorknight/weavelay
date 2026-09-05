# WeaveLay 架构说明

## 1. 概念层级

```
文件族 (Document Family)
 └── 页型 (Page Kind)
       ├── 识别规则 (Anchors)
       ├── 合并/配对参数 (PageRuleConfig)
       └── 输出槽位 (SlotDefinition[])
              ├── 普通槽位: slotCode 不以 tbl_ 开头
              └── 表格列槽位: slotCode 以 tbl_ 开头 → 使用几何定位提取
```

### 1.1 文件族 (Document Family)

代表一类 PDF 文档。例如 `process_spec`（工艺规程）。同一个文件族下的所有页面共用一套页型定义和识别规则。

核心类：
- `DocumentFamily` — 枚举，目前只有 `PROCESS_SPEC`
- `RuleCatalog` — 接口，为指定文件族提供页型定义查询
- `SqliteRuleCatalog` — SQLite 实现，从 `page_kind`/`page_anchor`/`slot_definition` 表加载

### 1.2 页型 (Page Kind)

一个文件族内，不同页面因内容不同而定义不同的"页型"。每种页型绑定：

- **分类锚点 (anchors)**：用于自动识别"这一页是什么页型"
- **合并参数 (PageRuleConfig)**：控制 OCR 行合并和 label-value 配对行为
- **输出槽位 (slots)**：定义从该页提取哪些 key-value

核心类：
- `PageKind` — 枚举，包含 `COVER`、`PROCESS_CATALOG`、`PROCESS_DRAWING`、`MACHINING_CARD`、`INSPECTION_CARD`、`AUXILIARY_CARD`、`UNKNOWN`
- `PageKindDefinition` — 完整定义：code、displayName、classifyPriority、anchors、rule、slots
- `SlotDefinition` — 单个槽位定义：slotCode、slotLabel、sortOrder

### 1.3 分类锚点规则

定义在 `PageClassifier` 中，每条 anchor 是以下格式之一：

| 格式 | 含义 | 示例 |
|------|------|------|
| `#N` | merge 后第 N 行精确等于页型名 | `#2` |
| `~文本` | 任意 merge 行与"文本"精确匹配 | `~工序目录` |
| `&文本` | OCR 原始行中包含该文本 | `&Table` |
| `@N` | 页号规则：@1 首页，@-1 末页 | `@1` |

同一页型的多条 anchor 全部满足才命中。按 `classifyPriority` 升序遍历，取第一个命中的页型。都不命中返回 `UNKNOWN`。

### 1.4 输出槽位 (SlotDefinition)

每个槽位有三个属性：

- **slotCode**：槽位编码。以 `tbl_` 开头的是表格列槽位，其他是普通槽位。以 `#` 开头的是行引用槽位（直接取 merge 后第 N 行作为值）
- **slotLabel**：显示名/OCR 匹配用标签
- **sortOrder**：排序序号

---

## 2. 页面处理流程

```
PDF 文件
  → RapidOcrService: OCR 识别 → List<OcrRow> (每行: 文字 + bbox 坐标)
  → PageClassifier.classifyCode(): 页型分类
  → PageExtractService.processPage(): 按页型路由提取
       ├─ CoverPageExtractor: 封面页提取
       ├─ ProcessCatalogPageExtractor: 工序目录页提取
       └─ MergeFallbackExtractor: 兜底提取
  → 输出 List<SlotValue>
```

### 2.1 页型分类 (`PageClassifier.classifyCode`)

1. 用 `RowMerger.merge()` 把 OCR 行合并为 weave 行（按 Y 阈值合并同一行的文字，按阅读顺序排序）
2. 按优先级遍历 catalog 中所有页型（跳过 UNKNOWN）
3. 对每个页型，检查其所有 anchor 是否都在页面上匹配
4. 返回第一个全部匹配的页型 code，都不匹配返回 `"unknown"`

### 2.2 工序目录页提取 (`ProcessCatalogPageExtractor`)

> **注意（桌面主路径）**：当前织版桌面「应用」对普通字段的主方式是  
> **模板已保存的 value 像素区域 → 裁图/取字 OCR**（见 `WeaveLayApp.applyCurrentPage`），  
> **不是**运行时找「材料」标签再旁边配对。页型由用户手动选择。  
> 下列 `LabelValuePairer` / 批处理提取器描述的是仓库里仍存在的旧/批处理路径，勿与模板区域主路径混淆。

这是批处理侧较复杂的提取器之一，处理包含表格的工序目录页：

1. **圈号**：从 OCR 行中找纯数字文本
2. **普通槽位**：遍历定义中的非 `tbl_` 槽位（如 product_code、material），通过 label-value 配对提取值
   - `material` 槽位特殊处理：使用 `OcrCellValueCollector.collectBesideLabel()`，在 label 右侧较大范围查找
   - 其他槽位：使用 `LabelValuePairer.pairVertical()`，在 label 下方垂直方向查找
3. **表格列槽位**：`extractTableColumns()` → `TableColumnValueCollector.collectColumns()`（见第 3 节）

### 2.3 封面页提取 (`CoverPageExtractor`)

（同属旧/批处理路径；桌面主路径仍以模板区域为准。）

1. **工序名称**：跳过 anchor 和 slot label 命中的行、纯数字行，取 Y > 700 的第一个 OCR 文字
2. **槽位提取**：对所有 slot 使用 `LabelValuePairer.pairHorizontal()` 做水平 label-value 配对

---

## 3. 表格列槽位提取（tbl_* 的核心逻辑）

这是 Cursor 改动最多的部分。调用链：

```
ProcessCatalogPageExtractor.extractTableColumns()
  → TableColumnValueCollector.collectColumns(pageRows, labels, rowTolerance)
       ├─ findHeaderCells: 在 OCR 行中找 label 匹配 → HeaderCell 列表
       ├─ pickBestBand: 按 Y 重叠分组，取最大的 band
       ├─ 对 band 中每个 header:
       │    ├─ columnLeft/columnRight: 按 header 的 X 位置推算列左右边界
       │    └─ collectInColumn: 在列边界 × 数据带内收集 OCR 文字
       └─ 返回 Map<String, PairMatch>
  → 为每个 SlotDefinition 构建 SlotValue
```

### 3.1 数据带 (Data Band)

列头行下方的一段高度区域，用于查找该列的数据值。计算方式：
- `dataTop = band.maxEndY + 8px`
- `dataBottom = dataTop + max(rowTolerance, headerHeight × 1.8)`

### 3.2 空单元格处理

`TableColumnValueCollector` 对每个检测到的列头，即使数据带内没有任何 OCR 文字，也会通过 `TableColumnCellRegion.create()` 创建一个合成 cell 区域（一个带 bbox 但 feature 为 `tbl_cell` 的 OcrRow 对象），作为 `valueRow`。

这样：
- PDF 预览（面板 2）可以画蓝色虚线框标记空 cell 位置
- `displayValue()` 对空值返回 `"—"` 占位符

### 3.3 列定义来源

表格列的定义来自 `PageKindDefinition.getSlots()` 中 `slotCode` 以 `tbl_` 开头的那些。这些定义存储在 SQLite 数据库的 `slot_definition` 表中，通过数据库迁移（v8）初始化：

```
slot_code              slot_label            sort_order
tbl_supply_status      供应状态               5
tbl_blank_type         毛坯种类               6
tbl_blank_size         毛坯尺寸               7
tbl_blank_weight       毛坯重量kg             8
tbl_parts_per_blank    每一毛坯可制零件数      9
tbl_part_weight        零件重量kg             10
tbl_parts_per_set      每套产品零件数          11
```

---

## 4. UI 面板布局

```
┌─────────────────────────────────────────┬──────────────┐
│  PDF 预览 (72%)                         │ 页内行 (45%)  │
│  - 红框: OCR 文字行                     │ #1 xxx       │
│  - 蓝虚线框: 表格 cell 区域 (可为空)     │ #2 ↳ xxx  —  │
│                                         │ #3 yyy       │
│  ← PagePreviewPane + OcrBoxOverlay      ├──────────────┤
│                                         │ 本页输出 (55%)│
│                                         │ code = value │
│                                         │ tbl_xxx = —  │
│                                         │ ← outputTable│
└─────────────────────────────────────────┴──────────────┘
```

### 4.1 三个面板的数据来源

**面板 1 "页内行" (`slotTable`)**：
- 数据：`composePageLines(ocrRows, tblSlots)`
- 逻辑：OCR 行顺序编号 `#1 #2 #3...`，OCR 文字匹配到 `tblSlots` 中 label 的行下面插入数据行 `↳ 列头  值`
- 空值显示 `—`

**面板 2 "PDF 预览" (`weavePreview`)**：
- 红框：OCR 原始文字行（`page.getOcrRows()`）
- 蓝虚线框：`virtualCellRows(tblSlots)` — 从 tblSlots 中提取合成 cell 区域

**面板 3 "本页输出" (`outputTable`)**：
- 来源 1：`resolveOutputSlots(page)` → `PageExtractService.processPage()` 返回的槽位值（非 tbl_）
- 来源 2：`tblSlots` 经过 `displayOutputSlot()` 转换（空值显示 `—`）

### 4.2 composePageLines 详解（编号展示的核心）

```java
composePageLines(ocrRows, tblSlots)
  │
  ├─ 1. 从 tblSlots 提取 knownLabels (列头 label 的 normalized 集合)
  │
  ├─ 2. shouldInterleaveTableColumns(ocrRows, knownLabels)
  │      = countKnownTableHeaders >= 3
  │      统计 OCR 行中匹配 knownLabels 的唯一 header 数量
  │
  ├─ 3a. 不满足 → composeOcrOnly: 纯 OCR 行顺序编号
  │
  └─ 3b. 满足 → 交错编号:
         ├─ indexTblSlots: normalized label → SlotValue 映射
         ├─ fillMissingDefinitions: OCR 中出现的列头但 tblByLabel 中没有的, 用 template 补上
         └─ 遍历 OCR 行:
              ├─ 每行输出 #N + OCR 文字
              └─ 若匹配 knownLabels → 插入 #N+1 "↳ label  value"
                   (value 通过 displayValue() 获取, 空值 → "—")
```

### 4.3 关键方法说明

| 方法 | 位置 | 作用 |
|------|------|------|
| `resolveTblColumnSlots` | WeaveLayApp | 从页型的 PageKindDefinition 获取 tbl_* 列定义, 调用 resolveTblSlots |
| `resolveTblSlots` | ProcessCatalogLineComposer | 查定义中的 tbl_* 列 → 计数 OCR 匹配 → 调用 TableColumnSlotResolver.resolve |
| `tableColumnDefinitions` | TableColumnSlotResolver | 从 PageKindDefinition.getSlots() 中筛出 slotCode 以 tbl_ 开头的 |
| `resolve` | TableColumnSlotResolver | 对每列调用 TableColumnValueCollector 做几何提取 |
| `collectColumns` | TableColumnValueCollector | 列头匹配 + band 分组 + 列边界计算 + 数据带内取值 |
| `displayValue` | ProcessCatalogLineComposer | tbl_ 槽位空值返回 "—", 否则返回原值 |
| `virtualCellRows` | ProcessCatalogLineComposer | 从 tblSlots 中提取合成 cell 区域 (给 PDF 预览画蓝框) |
| `isTableColumnCode` | ProcessCatalogTableColumns | 判断 slotCode 是否以 `tbl_` 开头 |

---

## 5. Cursor 改动说明

Cursor 被要求实现"OCR 识别到空 cell 时添加占位"。其改动：

### 5.1 新增的方法/类
- `ProcessCatalogLineComposer.fillMissingDefinitions` — 确保列头即使不在 tblSlots 中也能生成数据行
- `ProcessCatalogLineComposer.displayValue` — 空值返回 "—"
- `ProcessCatalogLineComposer.EMPTY_DISPLAY` — 常量 `"—"`
- `PageKind.getCode()` — 页型 code 字符串
- `composePageLines` 三参数重载（加 pageKindCode）

### 5.2 在 `ProcessCatalogTableColumns` 中硬编码了 7 个列定义
这是本次需要清理的部分。这 7 个列定义同时存在于：
1. `ProcessCatalogTableColumns.DEFINITIONS`（Java 硬编码）
2. `WeaveDatabase.seedProcessSpec()`（新数据库初始化）
3. `DatabaseMigrator.Migration008`（已有数据库迁移）

Java 硬编码版本用于 `isKnownTableHeader` 判断和 `fillMissingDefinitions` 的兜底填充。

### 5.3 编译错误原因
测试文件 `ProcessCatalogLineComposerTest` 引用了 `PageKind.UNKNOWN.getCode()` 但没有 import `PageKind`。

---

## 6. 当前清理状态

已移除 Java 硬编码的 7 个列定义：
- `ProcessCatalogTableColumns.DEFINITIONS` 和 `definitions()` 方法已删除
- `isKnownTableHeader` 改为从 `tblSlots` 的 label 集合判断
- `shouldInterleaveTableColumns` 改为接收 `Set<String> knownLabels` 参数
- `fillMissingDefinitions` 改为用 `tblSlots` 作为补充模板
- `TableColumnSlotResolver.tableColumnDefinitions` 移除了硬编码兜底
- `fallbackProcessCatalogDefinition` 方法已删除
- `resolveTblSlots` 不再兜底，无定义时返回空列表
- `TableColumnSlotResolver.resolve` 只返回 OCR 中实际检测到的列

表格列定义现在完全由数据库中的 `slot_definition` 表提供（通过 `PageKindDefinition`）。
