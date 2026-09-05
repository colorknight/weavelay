# Cursor 改动梳理

## 改动概述

用户要求：OCR 识别到表格有空 cell 时，给空 cell 也编上号并显示占位符 `—`。

Cursor 的实现方式是加了一套"工序目录表体列"的硬编码逻辑，把 7 个特定业务列头写死在 Java 代码里。

## 涉及文件

### 1. `ProcessCatalogTableColumns.java`

**原来**（Cursor 之前）：空类或不存在。

**Cursor 加了**：7 个硬编码列定义 + `definitions()` + `isTableColumnCode()`。

```java
// Cursor 加的
private static final List<SlotDefinition> DEFINITIONS = List.of(
    new SlotDefinition("tbl_supply_status", "供应状态", 5),
    new SlotDefinition("tbl_blank_type", "毛坯种类", 6),
    // ... 共7个
);
```

**问题**：把特定工厂的工序目录列头写死在通用工具里。

### 2. `ProcessCatalogLineComposer.java`

**Cursor 加的方法**：
- `fillMissingDefinitions(Map<String, SlotValue> tblByLabel)` — 把 7 个硬编码列头全部塞进查找表，确保任何 OCR 匹配到这些列头都能插入数据行
- `displayValue(SlotValue slot)` — 对 `tbl_` 开头的槽位，空值返回 `"—"`
- `isKnownTableHeader(String normalizedFeature)` — 判断 OCR 文字是否属于 7 个硬编码列头之一
- `composePageLines(rows, tblSlots, pageKindCode)` — 三参数重载

**Cursor 改的方法**：
- `composePageLines` — 加入 `fillMissingDefinitions` 调用
- `shouldInterleaveTableColumns` — 通过 `isKnownTableHeader` 判断（用硬编码 7 个 label）

### 3. `TableColumnSlotResolver.java`

**Cursor 改的**：
- `tableColumnDefinitions` — 当 catalog 定义没有 `tbl_*` 槽位且页型是 `PROCESS_CATALOG` 时，回退到 `ProcessCatalogTableColumns.definitions()`（硬编码 7 个）
- 新增 `fallbackProcessCatalogDefinition()` — 构造一个带 7 个硬编码列的兜底定义

### 4. `ProcessCatalogLineComposerTest.java`

**Cursor 加的新测试**：
- `interleavesWhenTableHeadersPresentEvenIfPageKindUnknown` — 用 `PageKind.UNKNOWN.getCode()` 调用三参数版本
- `skipsInterleaveOnNonProcessCatalogPages` — 同上

**编译错误**：缺少 `import com.weavelay.core.page.PageKind;`

### 5. `WeaveDatabase.java` / `DatabaseMigrator.java`

**Cursor 同时改了数据库初始化/迁移**：把同样的 7 个列插入 `slot_definition` 表，绑定到 `process_catalog` 页型。这部分是合理的——列定义本就该存在 DB 里。

### 6. `TableColumnValueCollector.java`

Cursor 可能新增/修改了这个类（实现了从 OCR 中找列头→划列边界→取值的完整几何提取逻辑）。

### 7. `TableColumnCellRegion.java`

Cursor 新增。用于创建合成 cell 区域（给空 cell 画蓝色虚线框用）。

## Cursor 设计的问题

1. **Java 代码里硬编码业务列头**：7 个中文列头（供应状态、毛坯种类……）只适用于某一个工厂的工艺规程 PDF，不是通用工具该有的
2. **DB 里也有一份**：导致同一份列定义出现在两个地方（Java + DB），不一致风险
3. **`fallbackProcessCatalogDefinition`**：在查不到 catalog 定义时用硬编码兜底，绕过了用户配置
4. **`fillMissingDefinitions` 无脑补全 7 个**：不管页面上实际有没有这些列头，全部塞进查找表
5. **`isKnownTableHeader` 依赖硬编码**：判断"什么是列头"的标准写死在代码里

## 清理后的状态

- `ProcessCatalogTableColumns` 只保留 `isTableColumnCode()`（`tbl_` 前缀判断），不含任何业务列头
- 列头定义唯一来源：数据库 `slot_definition` 表 → `PageKindDefinition.getSlots()`
- `isKnownTableHeader` 改为用传入的 label 集合判断，集合来源是 `PageKindDefinition` 的 `tbl_*` 槽位
- `fillMissingDefinitions` 改为以 `tblSlots` 为模板，只补 OCR 中实际出现的列头
- `TableColumnSlotResolver` 移除了 `fallbackProcessCatalogDefinition` 和硬编码回退
- `TableColumnSlotResolver.resolve` 只返回 OCR 实际检测到的列（`match.getLabelRow() != null`）
- 不再有无定义时的兜底行为——没配列的页型就没有表格列提取
