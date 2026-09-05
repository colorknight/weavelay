package com.weavelay.app.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 输出配置内存缓存: familyCode → List<FieldDef>. */
public final class OutputConfigCache {
    private static final Map<String, List<OutputSettingsDialog.FieldDef>> store = new ConcurrentHashMap<>();

    public static List<OutputSettingsDialog.FieldDef> get(String familyCode) {
        return store.getOrDefault(familyCode, List.of());
    }

    public static void put(String familyCode, List<OutputSettingsDialog.FieldDef> fields) {
        store.put(familyCode, new ArrayList<>(fields));
    }
}
