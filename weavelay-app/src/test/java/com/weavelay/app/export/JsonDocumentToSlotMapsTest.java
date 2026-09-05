package com.weavelay.app.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weavelay.app.export.TemplateExcelFiller.SlotMaps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonDocumentToSlotMapsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapsScalarsTablesAliasesAndDottedEquipment() throws Exception {
        String json = """
                {
                  "version": 2,
                  "产品代号": "WB001",
                  "零部件名称": "飞边",
                  "原料":
                  [
                    { "零件重量kg": "12.5", "供应状态": "退火" }
                  ],
                  "工序目录":
                  [
                    {
                      "工序号": "1",
                      "工序名称": "车",
                      "设备":
                      [
                        { "名称": "普通车床", "型号": "CW3333" },
                        { "名称": "数控车床", "型号": "CK6155" }
                      ]
                    },
                    {
                      "工序号": "1Y",
                      "工序名称": "检验",
                      "设备": [ { "名称": "工作台", "型号": "" } ]
                    }
                  ],
                  "route": []
                }
                """;
        SlotMaps maps = JsonDocumentToSlotMaps.fromDocument(MAPPER.readTree(json));
        assertEquals("WB001", maps.scalars.get("产品代号"));
        assertEquals("飞边", maps.scalars.get("零部件名称"));

        List<List<String>> material = maps.tables.get("材料表");
        assertNotNull(material, "原料应注册为材料表");
        assertEquals("12.5", cell(material, 1, "零件重量kg"));

        List<List<String>> catalog = maps.tables.get("工序目录表");
        assertNotNull(catalog, "工序目录应注册为工序目录表");
        assertEquals(maps.tables.get("工序目录"), catalog);
        assertEquals("1", cell(catalog, 1, "工序号"));
        assertEquals("普通车床；数控车床", cell(catalog, 1, "设备.名称"));
        assertEquals("CW3333；CK6155", cell(catalog, 1, "设备.型号"));
        assertEquals("工作台", cell(catalog, 2, "设备.名称"));
        assertTrue(maps.tables.containsKey("工序目录"));
    }

    private static String cell(List<List<String>> table, int dataRow, String col) {
        List<String> header = table.get(0);
        int idx = header.indexOf(col);
        assertTrue(idx >= 0, "缺列 " + col + " in " + header);
        return table.get(dataRow).get(idx);
    }
}
