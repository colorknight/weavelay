package com.weavelay.core.extract;

/**
 * 表格列工具: 通过 slotCode 前缀识别列类型, 不写死业务列头.
 * 具体列定义由 PageKindDefinition (catalog/DB) 提供.
 */
public final class ProcessCatalogTableColumns {

    private ProcessCatalogTableColumns() {
    }

    public static boolean isTableColumnCode(String slotCode) {
        return slotCode != null && slotCode.startsWith("tbl_");
    }
}
