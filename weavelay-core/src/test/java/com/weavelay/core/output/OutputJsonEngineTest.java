package com.weavelay.core.output;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputJsonEngineTest {

    @Test
    void fillsScalarsTablesAndLinkSet() throws Exception {
        String def = """
                {
                  "version": 2,
                  "零部件名称": "{{零部件名称}}",
                  "工序目录":
                  [
                    {
                      "工序号": "{{工序目录表.工序号}}",
                      "工序名称": "{{工序目录表.工序名称}}",
                      "注": ""
                    }
                  ],
                  "机械加工":
                  [
                    {
                      "工序号": "{{机械加工表.工序号}}",
                      "工序名称": "{{机械加工表.工序名称}}",
                      "技术要求": "{{机械加工表.技术要求}}"
                    }
                  ],
                  "link":
                  [
                    {
                      "from": "工序目录.工序号",
                      "to": "机械加工.工序号",
                      "set": {
                        "工序目录.注": "机械加工.技术要求"
                      }
                    }
                  ]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);

        OutputJsonEngine.PageValueBag catalog = new OutputJsonEngine.PageValueBag();
        catalog.putScalar("零部件名称", "齿轮");
        List<Map<String, String>> catRows = new ArrayList<>();
        catRows.add(row("工序号", "10", "工序名称", "车"));
        catRows.add(row("工序号", "20", "工序名称", "铣"));
        catalog.putTable("工序目录表", catRows);
        engine.applyPage(catalog);

        OutputJsonEngine.PageValueBag mach = new OutputJsonEngine.PageValueBag();
        List<Map<String, String>> machRows = new ArrayList<>();
        machRows.add(row("工序号", "10", "工序名称", "车削", "技术要求", "去毛刺"));
        mach.putTable("机械加工表", machRows);
        engine.applyPage(mach);

        assertEquals("齿轮", engine.document().path("零部件名称").asText());
        assertEquals("去毛刺",
                engine.document().path("工序目录").get(0).path("注").asText());
        assertEquals(2, engine.document().path("工序目录").size());
        assertEquals(1, engine.document().path("机械加工").size());
    }

    @Test
    void groupsCarryForwardRowsIntoNestedEquipment() throws Exception {
        String def = """
                {
                  "工序目录":
                  [
                    {
                      "工序号": "{{工序目录表.工序号}}",
                      "工序名称": "{{工序目录表.工序名称}}",
                      "设备":
                      [
                        {
                          "名称": "{{工序目录表.设备.名称}}",
                          "型号": "{{工序目录表.设备.型号}}"
                        }
                      ],
                      "页码": "{{工序目录表.页码}}"
                    }
                  ]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);

        OutputJsonEngine.PageValueBag bag = new OutputJsonEngine.PageValueBag();
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row(
                "工序号", "1",
                "工序名称", "车一端内形及外径",
                "设备.名称", "普通车床",
                "设备.型号", "CW3333",
                "页码", "3"));
        rows.add(row(
                "工序号", "",
                "工序名称", "",
                "设备.名称", "数控车床",
                "设备.型号", "CK6155",
                "页码", ""));
        rows.add(row(
                "设备.名称", "数控车床",
                "设备.型号", "GS-260"));
        rows.add(row(
                "工序号", "1Y",
                "工序名称", "检验",
                "设备.名称", "工作台",
                "设备.型号", "",
                "页码", "4"));
        bag.putTable("工序目录表", rows);
        engine.applyPage(bag);

        assertEquals(2, engine.document().path("工序目录").size());
        var step1 = engine.document().path("工序目录").get(0);
        assertEquals("车一端内形及外径", step1.path("工序名称").asText());
        assertEquals("3", step1.path("页码").asText());
        assertEquals(3, step1.path("设备").size());
        assertEquals("普通车床", step1.path("设备").get(0).path("名称").asText());
        assertEquals("CW3333", step1.path("设备").get(0).path("型号").asText());
        assertEquals("数控车床", step1.path("设备").get(1).path("名称").asText());
        assertEquals("CK6155", step1.path("设备").get(1).path("型号").asText());
        assertEquals("GS-260", step1.path("设备").get(2).path("型号").asText());

        var step2 = engine.document().path("工序目录").get(1);
        assertEquals("1Y", step2.path("工序号").asText());
        assertEquals(1, step2.path("设备").size());
        assertEquals("工作台", step2.path("设备").get(0).path("名称").asText());
    }

    @Test
    void fillsNestedEquipmentWhenTemplateArrayEmpty() throws Exception {
        String def = """
                {
                  "工序目录":
                  [
                    {
                      "工序名称": "{{工序目录表.工序名称}}",
                      "设备": []
                    }
                  ]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);
        OutputJsonEngine.PageValueBag bag = new OutputJsonEngine.PageValueBag();
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("工序名称", "车", "设备.名称", "A", "设备.型号", "1"));
        rows.add(row("设备.名称", "B", "设备.型号", "2"));
        bag.putTable("工序目录表", rows);
        engine.applyPage(bag);

        assertEquals(1, engine.document().path("工序目录").size());
        assertEquals(2, engine.document().path("工序目录").get(0).path("设备").size());
        assertEquals("B", engine.document().path("工序目录").get(0).path("设备").get(1).path("名称").asText());
    }

    @Test
    void blankPrimaryKeyStaysBlank_noTableWideBackfill() throws Exception {
        String def = """
                {
                  "工序目录":
                  [
                    {
                      "工序号": "{{工序目录表.工序号}}",
                      "工序名称": "{{工序目录表.工序名称}}"
                    }
                  ],
                  "group":
                  [
                    { "collection": "工序目录", "key": "工序号" }
                  ]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);
        OutputJsonEngine.PageValueBag bag = new OutputJsonEngine.PageValueBag();
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("工序号", "", "工序名称", "来料图"));
        rows.add(row("工序号", "1", "工序名称", "车一端内形及外径"));
        rows.add(row("工序号", "1Y", "工序名称", "检验"));
        rows.add(row("工序号", "2", "工序名称", "车另一端内形"));
        bag.putTable("工序目录表", rows);
        engine.applyPage(bag);

        assertEquals(4, engine.document().path("工序目录").size());
        assertEquals("", engine.document().path("工序目录").get(0).path("工序号").asText());
        assertEquals("来料图", engine.document().path("工序目录").get(0).path("工序名称").asText());
        assertEquals("1", engine.document().path("工序目录").get(1).path("工序号").asText());
        assertEquals("1Y", engine.document().path("工序目录").get(2).path("工序号").asText());
        assertEquals("2", engine.document().path("工序目录").get(3).path("工序号").asText());
    }

    @Test
    void groupKeyMergesOnlyNestedRows_notBlankPkWithName() throws Exception {
        String def = """
                {
                  "工序目录":
                  [
                    {
                      "工序号": "{{工序目录表.工序号}}",
                      "工序名称": "{{工序目录表.工序名称}}",
                      "设备":
                      [
                        {
                          "名称": "{{工序目录表.设备.名称}}",
                          "型号": "{{工序目录表.设备.型号}}"
                        }
                      ]
                    }
                  ],
                  "group":
                  [
                    { "collection": "工序目录", "key": "工序号" }
                  ]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);
        OutputJsonEngine.PageValueBag bag = new OutputJsonEngine.PageValueBag();
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("工序号", "1", "工序名称", "车", "设备.名称", "A", "设备.型号", "1"));
        rows.add(row("工序号", "", "设备.名称", "B", "设备.型号", "2"));
        rows.add(row("工序号", "", "工序名称", "来料图"));
        bag.putTable("工序目录表", rows);
        engine.applyPage(bag);

        assertEquals(2, engine.document().path("工序目录").size());
        var step1 = engine.document().path("工序目录").get(0);
        assertEquals("1", step1.path("工序号").asText());
        assertEquals(2, step1.path("设备").size());
        var step2 = engine.document().path("工序目录").get(1);
        assertEquals("", step2.path("工序号").asText());
        assertEquals("来料图", step2.path("工序名称").asText());
    }

    /** 新工序号行不得继承上一道工序的名称（否则会出现 1→转热处理）。 */
    @Test
    void newProcessNumberMustNotInheritPreviousName() throws Exception {
        String def = """
                {
                  "工序目录":
                  [
                    {
                      "工序号": "{{工序目录表.工序号}}",
                      "工序名称": "{{工序目录表.工序名称}}",
                      "设备":
                      [
                        {
                          "名称": "{{工序目录表.设备.名称}}",
                          "型号": "{{工序目录表.设备.型号}}"
                        }
                      ]
                    }
                  ],
                  "group":
                  [
                    { "collection": "工序目录", "key": "工序号" }
                  ]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);
        OutputJsonEngine.PageValueBag bag = new OutputJsonEngine.PageValueBag();
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("工序号", "", "工序名称", "来料图"));
        rows.add(row("工序号", "1", "工序名称", "车一端内形及外径", "设备.名称", "车床", "设备.型号", "C1"));
        rows.add(row("工序号", "", "设备.名称", "数控", "设备.型号", "N1"));
        rows.add(row("工序号", "1Y", "工序名称", "检验"));
        rows.add(row("工序号", "2", "工序名称", "车另一端及内形"));
        rows.add(row("工序号", "2Y", "工序名称", "检验"));
        rows.add(row("工序号", "3", "工序名称", "去毛刺"));
        rows.add(row("工序号", "F1", "工序名称", "包装"));
        rows.add(row("工序号", "", "工序名称", "加工后成品图"));
        rows.add(row("工序号", "", "工序名称", "转热处理"));
        bag.putTable("工序目录表", rows);
        engine.applyPage(bag);

        var cat = engine.document().path("工序目录");
        assertEquals(9, cat.size());
        assertEquals("来料图", cat.get(0).path("工序名称").asText());
        assertEquals("", cat.get(0).path("工序号").asText());
        assertEquals("1", cat.get(1).path("工序号").asText());
        assertEquals("车一端内形及外径", cat.get(1).path("工序名称").asText());
        assertEquals(2, cat.get(1).path("设备").size());
        assertEquals("1Y", cat.get(2).path("工序号").asText());
        assertEquals("检验", cat.get(2).path("工序名称").asText());
        assertEquals("2", cat.get(3).path("工序号").asText());
        assertEquals("车另一端及内形", cat.get(3).path("工序名称").asText());
        assertEquals("F1", cat.get(6).path("工序号").asText());
        assertEquals("包装", cat.get(6).path("工序名称").asText());
        assertEquals("加工后成品图", cat.get(7).path("工序名称").asText());
        assertEquals("", cat.get(7).path("工序号").asText());
        assertEquals("转热处理", cat.get(8).path("工序名称").asText());
        assertEquals("", cat.get(8).path("工序号").asText());
    }

    @Test
    void routesByFieldRegexToTargetCollections() throws Exception {
        String def = """
                {
                  "工序目录":
                  [
                    {
                      "工序号": "{{工序目录表.工序号}}",
                      "工序名称": "{{工序目录表.工序名称}}"
                    }
                  ],
                  "机械加工":
                  [
                    {
                      "工序号": "",
                      "工序名称": ""
                    }
                  ],
                  "检验":
                  [
                    {
                      "工序号": "",
                      "工序名称": ""
                    }
                  ],
                  "group":
                  [
                    { "collection": "工序目录", "key": "工序号" },
                    { "collection": "机械加工", "key": "工序号" },
                    { "collection": "检验", "key": "工序号" }
                  ],
                  "route":
                  [
                    { "from": "工序目录", "field": "工序号", "match": "^\\\\d+$", "to": "机械加工" },
                    { "from": "工序目录", "field": "工序号", "match": "^\\\\d+Y$", "to": "检验" }
                  ]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);
        OutputJsonEngine.PageValueBag bag = new OutputJsonEngine.PageValueBag();
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("工序号", "", "工序名称", "来料图"));
        rows.add(row("工序号", "1", "工序名称", "车"));
        rows.add(row("工序号", "1Y", "工序名称", "检验"));
        rows.add(row("工序号", "2", "工序名称", "铣"));
        bag.putTable("工序目录表", rows);
        engine.applyPage(bag);

        assertEquals(4, engine.document().path("工序目录").size());
        assertEquals(2, engine.document().path("机械加工").size());
        assertEquals(1, engine.document().path("检验").size());
        assertEquals("1", engine.document().path("机械加工").get(0).path("工序号").asText());
        assertEquals("2", engine.document().path("机械加工").get(1).path("工序号").asText());
        assertEquals("1Y", engine.document().path("检验").get(0).path("工序号").asText());
    }

    /** 目录误灌进机械加工后，route 应清掉 2Y，只留纯数字；Y 号进检验。 */
    @Test
    void routePrunesMismatchedKeysFromTargetCollections() throws Exception {
        String def = """
                {
                  "工序目录":
                  [
                    {
                      "工序号": "{{工序目录表.工序号}}",
                      "工序名称": "{{工序目录表.工序名称}}"
                    }
                  ],
                  "机械加工":
                  [
                    {
                      "工序号": "{{工序目录表.工序号}}",
                      "工序名称": "{{工序目录表.工序名称}}",
                      "工步": []
                    }
                  ],
                  "检验":
                  [
                    {
                      "工序号": "",
                      "工序名称": ""
                    }
                  ],
                  "group":
                  [
                    { "collection": "工序目录", "key": "工序号" },
                    { "collection": "机械加工", "key": "工序号" },
                    { "collection": "检验", "key": "工序号" }
                  ],
                  "route":
                  [
                    { "from": "工序目录", "field": "工序号", "match": "^\\\\d+$", "to": "机械加工" },
                    { "from": "工序目录", "field": "工序号", "match": "^\\\\d+Y$", "to": "检验" }
                  ]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);
        OutputJsonEngine.PageValueBag bag = new OutputJsonEngine.PageValueBag();
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("工序号", "1", "工序名称", "车一端内形及外径"));
        rows.add(row("工序号", "2Y", "工序名称", "检验"));
        bag.putTable("工序目录表", rows);
        engine.applyPage(bag);

        var mach = engine.document().path("机械加工");
        var insp = engine.document().path("检验");
        assertEquals(1, mach.size(), "2Y 不应留在机械加工");
        assertEquals("1", mach.get(0).path("工序号").asText());
        assertEquals(1, insp.size());
        assertEquals("2Y", insp.get(0).path("工序号").asText());
        assertEquals("检验", insp.get(0).path("工序名称").asText());
    }

    @Test
    void laterPageMergesIntoRoutedShellByGroupKey() throws Exception {
        String def = """
                {
                  "工序目录":
                  [
                    {
                      "工序号": "{{工序目录表.工序号}}",
                      "工序名称": "{{工序目录表.工序名称}}",
                      "设备":
                      [
                        {
                          "名称": "{{工序目录表.设备.名称}}",
                          "型号": "{{工序目录表.设备.型号}}"
                        }
                      ]
                    }
                  ],
                  "机械加工":
                  [
                    {
                      "工序号": "{{机械加工表.工序号}}",
                      "工序名称": "{{机械加工表.工序名称}}",
                      "设备": [],
                      "技术要求": "{{机械加工表.技术要求}}"
                    }
                  ],
                  "group":
                  [
                    { "collection": "工序目录", "key": "工序号" },
                    { "collection": "机械加工", "key": "工序号" }
                  ],
                  "route":
                  [
                    { "from": "工序目录", "field": "工序号", "match": "^\\\\d+$", "to": "机械加工" }
                  ],
                  "link":
                  [
                    {
                      "from": "工序目录.工序号",
                      "to": "机械加工.工序号",
                      "set": {
                        "工序目录.注": "机械加工.技术要求"
                      }
                    }
                  ]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);

        OutputJsonEngine.PageValueBag catalog = new OutputJsonEngine.PageValueBag();
        List<Map<String, String>> catRows = new ArrayList<>();
        catRows.add(row(
                "工序号", "1",
                "工序名称", "车一端",
                "设备.名称", "普通车床",
                "设备.型号", "CW3333"));
        catRows.add(row("工序号", "2", "工序名称", "铣", "设备.名称", "铣床", "设备.型号", "X1"));
        catalog.putTable("工序目录表", catRows);
        engine.applyPage(catalog);

        assertEquals(2, engine.document().path("机械加工").size());
        assertEquals("车一端", engine.document().path("机械加工").get(0).path("工序名称").asText());
        assertEquals(1, engine.document().path("机械加工").get(0).path("设备").size());
        assertEquals("", engine.document().path("机械加工").get(0).path("技术要求").asText(""));

        OutputJsonEngine.PageValueBag mach = new OutputJsonEngine.PageValueBag();
        List<Map<String, String>> machRows = new ArrayList<>();
        machRows.add(row("工序号", "1", "工序名称", "", "技术要求", "去毛刺"));
        mach.putTable("机械加工表", machRows);
        engine.applyPage(mach);

        // 按工序号合并，不整段重写、不复制第二份
        assertEquals(2, engine.document().path("机械加工").size());
        assertEquals("车一端", engine.document().path("机械加工").get(0).path("工序名称").asText());
        assertEquals("去毛刺", engine.document().path("机械加工").get(0).path("技术要求").asText());
        assertEquals(1, engine.document().path("机械加工").get(0).path("设备").size());
        assertEquals("", engine.document().path("机械加工").get(1).path("技术要求").asText(""));
        assertEquals("去毛刺", engine.document().path("工序目录").get(0).path("注").asText());
    }

    @Test
    void fillsSiblingNestedTablesIntoExistingShell() throws Exception {
        String def = """
                {
                  "机械加工":
                  [
                    {
                      "工序号": "{{工序号}}",
                      "工序名称": "{{工序名称}}",
                      "辅助材料":
                      [
                        {
                          "名称": "{{辅助材料.名称}}",
                          "规格": "{{辅助材料.规格}}"
                        }
                      ],
                      "工步":
                      [
                        {
                          "序号": "{{工步内容.序号}}",
                          "内容": "{{工步内容.内容}}"
                        }
                      ]
                    }
                  ],
                  "group":
                  [
                    { "collection": "机械加工", "key": "工序号" }
                  ]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);

        // 目录 route 留下的壳
        var shell = engine.document().putArray("机械加工").addObject();
        shell.put("工序号", "1");
        shell.put("工序名称", "车一端内形及外径");
        shell.putArray("辅助材料");
        shell.putArray("工步");

        OutputJsonEngine.PageValueBag bag = new OutputJsonEngine.PageValueBag();
        bag.putScalar("工序号", "1");
        bag.putScalar("工序名称", "车一端内形及外径");
        List<Map<String, String>> aux = new ArrayList<>();
        aux.add(row("名称", "切削液", "规格", "RS-300"));
        bag.putTable("辅助材料", aux);
        List<Map<String, String>> steps = new ArrayList<>();
        steps.add(row("序号", "1", "内容", "将工件上料装夹"));
        steps.add(row("序号", "2", "内容", "车端面"));
        bag.putTable("工步内容", steps);
        engine.applyPage(bag);

        assertEquals(1, engine.document().path("机械加工").size());
        var item = engine.document().path("机械加工").get(0);
        assertEquals("1", item.path("工序号").asText());
        assertEquals(1, item.path("辅助材料").size());
        assertEquals("切削液", item.path("辅助材料").get(0).path("名称").asText());
        assertEquals("RS-300", item.path("辅助材料").get(0).path("规格").asText());
        assertEquals(2, item.path("工步").size());
        assertEquals("将工件上料装夹", item.path("工步").get(0).path("内容").asText());
        assertEquals("车端面", item.path("工步").get(1).path("内容").asText());
    }

    @Test
    void emptyProcessNoTemplateStillMergesByBagScalar() throws Exception {
        // 用户模板：工序号写成 ""，工步绑机械加工表，辅助材料独立表
        String def = """
                {
                  "机械加工":
                  [
                    {
                      "工序号": "",
                      "工序名称": "",
                      "辅助材料":
                      [
                        {
                          "名称": "{{辅助材料.名称}}",
                          "规格牌号": "{{辅助材料.规格牌号}}"
                        }
                      ],
                      "工步":
                      [
                        {
                          "工步号": "{{机械加工表.工步号}}",
                          "工步内容": "{{机械加工表.工步内容}}",
                          "刀具":
                          [
                            {
                              "名称": "{{机械加工表.刀具.名称}}",
                              "代号": "{{机械加工表.刀具.代号}}"
                            }
                          ]
                        }
                      ]
                    }
                  ],
                  "group":
                  [
                    { "collection": "机械加工", "key": "工序号" }
                  ],
                  "link":
                  [
                    {
                      "from": "工序目录.工序号",
                      "to": "机械加工.工序号",
                      "set": {}
                    }
                  ]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);
        var shell = engine.document().putArray("机械加工").addObject();
        shell.put("工序号", "1");
        shell.put("工序名称", "车一端内形及外径");
        shell.putArray("辅助材料");
        shell.putArray("工步");

        OutputJsonEngine.PageValueBag bag = new OutputJsonEngine.PageValueBag();
        bag.putScalar("工序号", "1");
        bag.putScalar("工序名称", "车一端内形及外径");
        bag.putTable("辅助材料", List.of(row("名称", "切削液", "规格牌号", "RS-300")));
        List<Map<String, String>> steps = new ArrayList<>();
        steps.add(row("工步号", "1", "工步内容", "上料", "刀具.名称", "车刀", "刀具.代号", "T1"));
        steps.add(row("工步号", "", "工步内容", "", "刀具.名称", "钻头", "刀具.代号", "T2"));
        bag.putTable("机械加工表", steps);
        engine.applyPage(bag);

        assertEquals(1, engine.document().path("机械加工").size());
        var item = engine.document().path("机械加工").get(0);
        assertEquals("1", item.path("工序号").asText());
        assertEquals(1, item.path("辅助材料").size());
        assertEquals("切削液", item.path("辅助材料").get(0).path("名称").asText());
        assertEquals(1, item.path("工步").size());
        assertEquals("上料", item.path("工步").get(0).path("工步内容").asText());
        assertEquals(2, item.path("工步").get(0).path("刀具").size());
        assertEquals("钻头", item.path("工步").get(0).path("刀具").get(1).path("名称").asText());
    }

    @Test
    void stepNoEmptyMergesToolsEvenIfContentRepeated() throws Exception {
        // 工步号空 = 续行；即使 OCR 把工步内容又填了一遍也不拆行
        String def = """
                {
                  "机械加工":
                  [
                    {
                      "工序号": "{{工序号}}",
                      "工步":
                      [
                        {
                          "工步号": "{{机械加工表.工步号}}",
                          "工步内容": "{{机械加工表.工步内容}}",
                          "刀具":
                          [
                            {
                              "名称": "{{机械加工表.刀具.名称}}",
                              "代号": "{{机械加工表.刀具.代号}}"
                            }
                          ],
                          "量具":
                          [
                            {
                              "名称": "{{机械加工表.量具.名称}}",
                              "代号": "{{机械加工表.量具.代号}}"
                            }
                          ]
                        }
                      ]
                    }
                  ],
                  "group": [{ "collection": "机械加工", "key": "工序号" }]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);
        OutputJsonEngine.PageValueBag bag = new OutputJsonEngine.PageValueBag();
        bag.putScalar("工序号", "1");
        List<Map<String, String>> steps = new ArrayList<>();
        steps.add(row(
                "工步号", "2",
                "工步内容", "车外圆",
                "刀具.名称", "端面车刀",
                "刀具.代号", "外购",
                "量具.名称", "卡尺",
                "量具.代号", "0~200"));
        steps.add(row(
                "工步号", "",
                "工步内容", "车外圆",
                "刀具.名称", "外圆车刀",
                "刀具.代号", "外购"));
        steps.add(row(
                "步号", "3",
                "工步内容", "下料",
                "量具.名称", "深度卡尺",
                "量具.代号", "0~200"));
        bag.putTable("机械加工表", steps);
        engine.applyPage(bag);

        var stepsOut = engine.document().path("机械加工").get(0).path("工步");
        assertEquals(2, stepsOut.size());
        assertEquals("2", stepsOut.get(0).path("工步号").asText());
        assertEquals(2, stepsOut.get(0).path("刀具").size());
        assertEquals("外圆车刀", stepsOut.get(0).path("刀具").get(1).path("名称").asText());
        assertEquals("3", stepsOut.get(1).path("工步号").asText());
        assertEquals("下料", stepsOut.get(1).path("工步内容").asText());
    }

    @Test
    void coalesceGaugeSpecIntoPreviousItem() throws Exception {
        String def = """
                {
                  "机械加工":
                  [
                    {
                      "工序号": "{{工序号}}",
                      "工步":
                      [
                        {
                          "工步号": "{{机械加工表.工步号}}",
                          "工步内容": "{{机械加工表.工步内容}}",
                          "量具":
                          [
                            {
                              "名称": "{{机械加工表.量具.名称}}",
                              "代号": "{{机械加工表.量具.代号}}"
                            }
                          ]
                        }
                      ]
                    }
                  ],
                  "group": [{ "collection": "机械加工", "key": "工序号" }]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);
        OutputJsonEngine.PageValueBag bag = new OutputJsonEngine.PageValueBag();
        bag.putScalar("工序号", "1");
        List<Map<String, String>> steps = new ArrayList<>();
        steps.add(row(
                "工步号", "1",
                "工步内容", "上料",
                "量具.名称", "百分表",
                "量具.代号", "外购"));
        steps.add(row(
                "工步号", "",
                "量具.名称", "0mm~3mm",
                "量具.代号", ""));
        bag.putTable("机械加工表", steps);
        engine.applyPage(bag);

        var gauges = engine.document().path("机械加工").get(0).path("工步").get(0).path("量具");
        assertEquals(1, gauges.size());
        assertEquals("百分表0mm~3mm", gauges.get(0).path("名称").asText());
        assertEquals("外购", gauges.get(0).path("代号").asText());
        assertEquals("", gauges.get(0).path("规格").asText(""));
    }

    /** 图2整段：有代号的并规格；代号也空的深度卡尺仍是新对象，其下规格再并入。 */
    @Test
    void coalesceGaugeSpecWhenPreviousCodeAlsoEmpty() throws Exception {
        String def = """
                {
                  "机械加工":
                  [
                    {
                      "工序号": "{{工序号}}",
                      "工步":
                      [
                        {
                          "工步号": "{{机械加工表.工步号}}",
                          "量具":
                          [
                            {
                              "名称": "{{机械加工表.量具.名称}}",
                              "代号": "{{机械加工表.量具.代号}}"
                            }
                          ]
                        }
                      ]
                    }
                  ],
                  "group": [{ "collection": "机械加工", "key": "工序号" }]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);
        OutputJsonEngine.PageValueBag bag = new OutputJsonEngine.PageValueBag();
        bag.putScalar("工序号", "1");
        List<Map<String, String>> steps = new ArrayList<>();
        steps.add(row("工步号", "3", "量具.名称", "百分表", "量具.代号", "外购"));
        steps.add(row("工步号", "", "量具.名称", "0mm~3mm", "量具.代号", ""));
        steps.add(row("工步号", "", "量具.名称", "卡尺", "量具.代号", "外购"));
        steps.add(row("工步号", "", "量具.名称", "0mm~200mm", "量具.代号", ""));
        steps.add(row("工步号", "", "量具.名称", "深度卡尺", "量具.代号", ""));
        steps.add(row("工步号", "", "量具.名称", "0mm~200mm", "量具.代号", ""));
        bag.putTable("机械加工表", steps);
        engine.applyPage(bag);

        var gauges = engine.document().path("机械加工").get(0).path("工步").get(0).path("量具");
        assertEquals(3, gauges.size());
        assertEquals("百分表0mm~3mm", gauges.get(0).path("名称").asText());
        assertEquals("外购", gauges.get(0).path("代号").asText());
        assertEquals("卡尺0mm~200mm", gauges.get(1).path("名称").asText());
        assertEquals("外购", gauges.get(1).path("代号").asText());
        assertEquals("深度卡尺0mm~200mm", gauges.get(2).path("名称").asText());
        assertEquals("", gauges.get(2).path("代号").asText());
    }

    @Test
    void doesNotCoalesceTwoToolNamesBothWithoutCode() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var arr = mapper.createArrayNode();
        arr.addObject().put("名称", "深度卡尺").put("代号", "");
        arr.addObject().put("名称", "千分尺").put("代号", "");
        OutputJsonEngine.coalesceSparseSubsetRows(arr);
        assertEquals(2, arr.size());
        assertEquals("深度卡尺", arr.get(0).path("名称").asText());
        assertEquals("千分尺", arr.get(1).path("名称").asText());
    }

    @Test
    void mainTableMergesContentWhenPkEmpty_ignoresSubtable() throws Exception {
        String def = """
                {
                  "机械加工":
                  [
                    {
                      "工序号": "{{工序号}}",
                      "工步":
                      [
                        {
                          "工步号": "{{机械加工表.工步号}}",
                          "工步内容": "{{机械加工表.工步内容}}",
                          "量具":
                          [
                            {
                              "名称": "{{机械加工表.量具.名称}}",
                              "代号": "{{机械加工表.量具.代号}}"
                            }
                          ]
                        }
                      ]
                    }
                  ],
                  "group": [{ "collection": "机械加工", "key": "工序号" }]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);
        OutputJsonEngine.PageValueBag bag = new OutputJsonEngine.PageValueBag();
        bag.putScalar("工序号", "1");
        List<Map<String, String>> steps = new ArrayList<>();
        steps.add(row(
                "工步号", "2",
                "工步内容", "车ø148至77处,",
                "量具.名称", "卡尺",
                "量具.代号", "外购"));
        steps.add(row(
                "工步号", "",
                "工步内容", "完成R2,全长≥57。",
                "量具.名称", "0mm~200mm",
                "量具.代号", ""));
        bag.putTable("机械加工表", steps);
        engine.applyPage(bag);

        var step = engine.document().path("机械加工").get(0).path("工步").get(0);
        assertEquals("2", step.path("工步号").asText());
        assertEquals("车ø148至77处,完成R2,全长≥57。", step.path("工步内容").asText());
        assertEquals(1, step.path("量具").size());
        assertEquals("卡尺0mm~200mm", step.path("量具").get(0).path("名称").asText());
    }

    /**
     * 检验卡片：扁平多行带序号。不能因 link 工序号把整表 upsert 成一条
     * （否则序号/检验率只剩最后一行，且常对不上）。
     */
    @Test
    void inspectionFlatRowsUpsertByXuhaoNotGongxuhao() throws Exception {
        String def = """
                {
                  "检验":
                  [
                    {
                      "工序号": "",
                      "序号": "{{检验表.序号}}",
                      "检验内容": "{{检验表.检验内容}}",
                      "设备或工艺装备":
                      {
                        "名称": "{{检验表.设备或工艺装备.名称}}",
                        "代号": "{{检验表.设备或工艺装备.代号}}"
                      },
                      "检测率%": "{{检验表.检测率%}}",
                      "检验方法及操作要求": "{{检验表.检验方法及操作要求}}"
                    }
                  ],
                  "link":
                  [
                    { "from": "工序目录.工序号", "to": "检验.工序号" }
                  ],
                  "group": [{ "collection": "工序目录", "key": "工序号" }]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);
        OutputJsonEngine.PageValueBag bag = new OutputJsonEngine.PageValueBag();
        bag.putScalar("工序号", "1Y");
        List<Map<String, String>> rows = new ArrayList<>();
        // OCR 表头是「设备或工艺设备.*」（设备），定义常写「装备」
        rows.add(row(
                "序号", "1",
                "检验内容", "检验ø18。",
                "设备或工艺设备.名称", "卡尺",
                "设备或工艺设备.代号", "外购",
                "检验率%", "20",
                "检验方法及操作要求", "卡尺测量"));
        rows.add(row(
                "序号", "",
                "检验内容", "",
                "设备或工艺设备.名称", "0mm~200mm",
                "设备或工艺设备.代号", "",
                "检验率%", "",
                "检验方法及操作要求", ""));
        rows.add(row(
                "序号", "6",
                "检验内容", "检验外观。",
                "设备或工艺设备.名称", "",
                "设备或工艺设备.代号", "",
                "检验率%", "100",
                "检验方法及操作要求", "目视"));
        bag.putTable("检验表", rows);
        engine.applyPage(bag);

        var arr = engine.document().path("检验");
        assertEquals(2, arr.size(), "应按序号拆成两条，不能压成工序号一条");
        assertEquals("1", arr.get(0).path("序号").asText());
        assertEquals("卡尺0mm~200mm", arr.get(0).path("设备或工艺装备").path("名称").asText());
        assertEquals("外购", arr.get(0).path("设备或工艺装备").path("代号").asText());
        assertEquals("20", arr.get(0).path("检测率%").asText());
        assertEquals("1Y", arr.get(0).path("工序号").asText());
        assertEquals("6", arr.get(1).path("序号").asText());
        assertEquals("检验外观。", arr.get(1).path("检验内容").asText());
        assertEquals("100", arr.get(1).path("检测率%").asText());
        assertEquals("目视", arr.get(1).path("检验方法及操作要求").asText());
        assertEquals("1Y", arr.get(1).path("工序号").asText());
    }

    /** 用户模板：检验 → 工步 → 设备或工艺装备[]；列名与定义一致时子表不得为空。 */
    @Test
    void inspectionStepNestedEquipmentArrayFilledWhenKeysMatch() throws Exception {
        String def = """
                {
                  "检验":
                  [
                    {
                      "工序号": "{{工序号}}",
                      "工序名称": "{{工序名称}}",
                      "技术要求": "{{技术要求}}",
                      "工步":
                      [
                        {
                          "序号": "{{检验表.序号}}",
                          "检验内容": "{{检验表.检验内容}}",
                          "设备或工艺装备":
                          [
                            {
                              "名称": "{{检验表.设备或工艺装备.名称}}",
                              "代号": "{{检验表.设备或工艺装备.代号}}"
                            }
                          ],
                          "检测率%": "{{检验表.检测率%}}",
                          "辅助材料": "{{检验表.辅助材料}}",
                          "检验方法及操作要求": "{{检验表.检验方法及操作要求}}"
                        }
                      ]
                    }
                  ],
                  "group": [{ "collection": "检验", "key": "工序号" }]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);
        OutputJsonEngine.PageValueBag bag = new OutputJsonEngine.PageValueBag();
        bag.putScalar("工序号", "1Y");
        bag.putScalar("工序名称", "检验");
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row(
                "序号", "1",
                "检验内容", "检验Ø18。",
                "设备或工艺装备.名称", "卡尺",
                "设备或工艺装备.代号", "外购",
                "检测率%", "20",
                "辅助材料", "",
                "检验方法及操作要求", "主尺读数合格。"));
        rows.add(row(
                "序号", "",
                "检验内容", "",
                "设备或工艺装备.名称", "0mm~200mm",
                "设备或工艺装备.代号", "",
                "检测率%", "",
                "辅助材料", "",
                "检验方法及操作要求", ""));
        bag.putTable("检验表", rows);
        engine.applyPage(bag);

        var step = engine.document().path("检验").get(0).path("工步").get(0);
        assertEquals("1", step.path("序号").asText());
        assertEquals("20", step.path("检测率%").asText());
        var equip = step.path("设备或工艺装备");
        assertTrue(equip.isArray(), "设备或工艺装备 应为数组");
        assertEquals(1, equip.size(), "子表应有一条量具（含规格续行合并）");
        assertEquals("卡尺0mm~200mm", equip.get(0).path("名称").asText());
        assertEquals("外购", equip.get(0).path("代号").asText());
    }

    /** 页级标量技术要求（不在检验表/机加表里）确认后应进集合，并由 link 回填目录.技术要求。 */
    @Test
    void pageLevelTechRequirementLinksIntoCatalog() throws Exception {
        String def = """
                {
                  "工序目录":
                  [
                    {
                      "工序号": "{{工序目录表.工序号}}",
                      "工序名称": "{{工序目录表.工序名称}}",
                      "注": "{{工序目录表.注}}"
                    }
                  ],
                  "机械加工":
                  [
                    {
                      "工序号": "{{工序号}}",
                      "工序名称": "{{工序名称}}",
                      "技术要求": "{{技术要求}}",
                      "工步":
                      [
                        {
                          "工步号": "{{机械加工表.工步号}}",
                          "工步内容": "{{机械加工表.工步内容}}"
                        }
                      ]
                    }
                  ],
                  "检验":
                  [
                    {
                      "工序号": "{{工序号}}",
                      "工序名称": "{{工序名称}}",
                      "技术要求": "{{技术要求}}",
                      "工步":
                      [
                        {
                          "序号": "{{检验表.序号}}",
                          "检验内容": "{{检验表.检验内容}}"
                        }
                      ]
                    }
                  ],
                  "group":
                  [
                    { "collection": "工序目录", "key": "工序号" },
                    { "collection": "机械加工", "key": "工序号" },
                    { "collection": "检验", "key": "工序号" }
                  ],
                  "route":
                  [
                    { "from": "工序目录", "field": "工序号", "match": "^\\\\d+$", "to": "机械加工" },
                    { "from": "工序目录", "field": "工序号", "match": "^\\\\d+Y$", "to": "检验" }
                  ],
                  "link":
                  [
                    {
                      "from": "工序目录.工序号",
                      "to": "机械加工.工序号",
                      "set": { "工序目录.技术要求": "机械加工.技术要求" }
                    },
                    {
                      "from": "工序目录.工序号",
                      "to": "检验.工序号",
                      "set": { "工序目录.技术要求": "检验.技术要求" }
                    }
                  ]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);

        OutputJsonEngine.PageValueBag catalog = new OutputJsonEngine.PageValueBag();
        List<Map<String, String>> catRows = new ArrayList<>();
        catRows.add(row("工序号", "1", "工序名称", "车", "注", ""));
        catRows.add(row("工序号", "1Y", "工序名称", "检", "注", "目录OCR注"));
        catalog.putTable("工序目录表", catRows);
        engine.applyPage(catalog);

        OutputJsonEngine.PageValueBag insp = new OutputJsonEngine.PageValueBag();
        insp.putScalar("工序号", "1Y");
        insp.putScalar("工序名称", "检验");
        insp.putScalar("技术要求", "检验页技术要求");
        insp.putTable("检验表", List.of(row("序号", "1", "检验内容", "外观")));
        engine.applyPage(insp);

        assertEquals("检验页技术要求", engine.document().path("检验").get(0).path("技术要求").asText());
        assertEquals("检验页技术要求", engine.document().path("工序目录").get(1).path("技术要求").asText());
        assertEquals("目录OCR注", engine.document().path("工序目录").get(1).path("注").asText());

        OutputJsonEngine.PageValueBag mach = new OutputJsonEngine.PageValueBag();
        mach.putScalar("工序号", "1");
        mach.putScalar("工序名称", "车");
        mach.putScalar("技术要求", "机加页技术要求");
        mach.putTable("机械加工表", List.of(row("工步号", "1", "工步内容", "车外圆")));
        engine.applyPage(mach);

        assertEquals("机加页技术要求", engine.document().path("机械加工").get(0).path("技术要求").asText());
        assertEquals("机加页技术要求", engine.document().path("工序目录").get(0).path("技术要求").asText());
    }

    /** 复现：目录建壳后，机加页有独立「设备」表 +「机械加工表」+ 标量技术要求时，技术要求必须合并进壳。 */
    @Test
    void machiningPageScalarTechRequirementMergesAfterRouteWithDeviceTable() throws Exception {
        String def = """
                {
                  "工序目录":
                  [
                    {
                      "工序号": "{{工序目录表.工序号}}",
                      "工序名称": "{{工序目录表.工序名称}}",
                      "设备":
                      [
                        {
                          "名称": "{{工序目录表.设备.名称}}",
                          "型号": "{{工序目录表.设备.型号}}"
                        }
                      ],
                      "注": "{{工序目录表.注}}"
                    }
                  ],
                  "机械加工":
                  [
                    {
                      "工序号": "{{工序号}}",
                      "工序名称": "{{工序名称}}",
                      "技术要求": "{{技术要求}}",
                      "设备":
                      [
                        {
                          "名称": "{{设备.名称}}",
                          "型号": "{{设备.型号}}",
                          "切削液": "{{设备.切削液}}"
                        }
                      ],
                      "辅助材料":
                      [
                        {
                          "名称": "{{辅助材料.名称}}",
                          "规格牌号": "{{辅助材料.规格牌号}}"
                        }
                      ],
                      "工步":
                      [
                        {
                          "工步号": "{{机械加工表.工步号}}",
                          "工步内容": "{{机械加工表.工步内容}}"
                        }
                      ]
                    }
                  ],
                  "group":
                  [
                    { "collection": "工序目录", "key": "工序号" },
                    { "collection": "机械加工", "key": "工序号" }
                  ],
                  "route":
                  [
                    { "from": "工序目录", "field": "工序号", "match": "^\\\\d+$", "to": "机械加工" }
                  ],
                  "link":
                  [
                    {
                      "from": "工序目录.工序号",
                      "to": "机械加工.工序号",
                      "set": { "工序目录.技术要求": "机械加工.技术要求" }
                    }
                  ]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);

        OutputJsonEngine.PageValueBag catalog = new OutputJsonEngine.PageValueBag();
        catalog.putTable("工序目录表", List.of(
                row("工序号", "1", "工序名称", "车一端内形及外径",
                        "设备.名称", "普通车床", "设备.型号", "CW3333", "注", ""),
                row("设备.名称", "数控车床", "设备.型号", "CK6155"),
                row("设备.名称", "数控车床", "设备.型号", "GS-260"),
                row("工序号", "2", "工序名称", "车另一端及内形",
                        "设备.名称", "数控车床", "设备.型号", "CK6155", "注", "")));
        engine.applyPage(catalog);

        assertEquals("", engine.document().path("机械加工").get(0).path("技术要求").asText(""));
        assertEquals(3, engine.document().path("机械加工").get(0).path("设备").size());

        OutputJsonEngine.PageValueBag mach = new OutputJsonEngine.PageValueBag();
        mach.putScalar("工序号", "1");
        mach.putScalar("工序名称", "车一端内形及外径");
        mach.putScalar("技术要求", "1.严禁磕碰及划伤零件。2.未注尺寸公差按GB/T1804-m级执行。");
        mach.putTable("设备", List.of(
                row("名称", "普通车床", "型号", "CW3333", "切削液", "乳化液"),
                row("名称", "数控车床", "型号", "CK6155", "切削液", ""),
                row("名称", "数控车床", "型号", "GS-260", "切削液", "")));
        mach.putTable("辅助材料", List.of(row("名称", "棉纱", "规格牌号", "")));
        mach.putTable("机械加工表", List.of(row("工步号", "1", "工步内容", "车外圆")));
        engine.applyPage(mach);

        assertEquals("1.严禁磕碰及划伤零件。2.未注尺寸公差按GB/T1804-m级执行。",
                engine.document().path("机械加工").get(0).path("技术要求").asText());
        assertEquals("1.严禁磕碰及划伤零件。2.未注尺寸公差按GB/T1804-m级执行。",
                engine.document().path("工序目录").get(0).path("技术要求").asText());
    }

    /** 表行带空「技术要求」列时，页级标量 {{技术要求}} 仍应写入。 */
    @Test
    void emptyTechRequirementOnTableRowMustNotBlockPageScalar() throws Exception {
        String def = """
                {
                  "机械加工":
                  [
                    {
                      "工序号": "{{机械加工表.工序号}}",
                      "技术要求": "{{技术要求}}",
                      "工步内容": "{{机械加工表.工步内容}}"
                    }
                  ],
                  "group": [{ "collection": "机械加工", "key": "工序号" }]
                }
                """;
        OutputJsonEngine engine = new OutputJsonEngine();
        engine.reset(def);
        OutputJsonEngine.PageValueBag bag = new OutputJsonEngine.PageValueBag();
        bag.putScalar("技术要求", "页级技术要求全文");
        bag.putTable("机械加工表", List.of(
                row("工序号", "1", "工步内容", "车", "技术要求", "")));
        engine.applyPage(bag);
        assertEquals("页级技术要求全文",
                engine.document().path("机械加工").get(0).path("技术要求").asText());
    }

    private static Map<String, String> row(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
