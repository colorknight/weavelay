package com.weavelay.app.ui;

import com.weavelay.core.model.SlotValue;

import java.util.ArrayList;
import java.util.List;

/**
 * TreeTableView 扁平索引操作工具。
 */
public final class SlotTableHelper {

    private SlotTableHelper() {}

    public static List<SlotValue> flatOutputItems(javafx.scene.control.TreeItem<SlotValue> root) {
        List<SlotValue> list = new ArrayList<>();
        for (javafx.scene.control.TreeItem<SlotValue> ti : root.getChildren()) {
            list.add(ti.getValue());
            for (javafx.scene.control.TreeItem<SlotValue> child : ti.getChildren()) {
                list.add(child.getValue());
            }
        }
        return list;
    }

    public static int flatIndexOf(javafx.scene.control.TreeItem<SlotValue> root, SlotValue slot) {
        return flatOutputItems(root).indexOf(slot);
    }

    public static void setFlatOutputItem(javafx.scene.control.TreeItem<SlotValue> root, int idx, SlotValue newVal) {
        int i = 0;
        for (javafx.scene.control.TreeItem<SlotValue> ti : root.getChildren()) {
            if (i == idx) {
                ti.setValue(newVal);
                return;
            }
            i++;
            for (javafx.scene.control.TreeItem<SlotValue> child : ti.getChildren()) {
                if (i == idx) {
                    child.setValue(newVal);
                    return;
                }
                i++;
            }
        }
    }

    public static SlotValue getFlatOutputItem(javafx.scene.control.TreeItem<SlotValue> root, int idx) {
        return flatOutputItems(root).get(idx);
    }

    public static javafx.scene.control.TreeItem<SlotValue> findTreeItemAtFlatIndex(
            javafx.scene.control.TreeItem<SlotValue> root, int idx) {
        int i = 0;
        for (javafx.scene.control.TreeItem<SlotValue> ti : root.getChildren()) {
            if (i == idx) return ti;
            i++;
            for (javafx.scene.control.TreeItem<SlotValue> child : ti.getChildren()) {
                if (i == idx) return child;
                i++;
            }
        }
        return null;
    }
}
