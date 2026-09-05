package com.weavelay.app.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.weavelay.core.model.SlotValue;

/**
 * 织版主视图右侧: 可编辑的本页输出 (字段名可双击编辑, 值由框选+数字键填充).
 */
public final class SlotTablePane extends BorderPane {

    public SlotTablePane(TreeTableView<SlotValue> outputTable, Runnable onRefresh) {
        setStyle("-fx-background-color: transparent;");

        VBox box = new VBox(2, outputTable);
        box.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(outputTable, Priority.ALWAYS);
        outputTable.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        box.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        box.setAlignment(Pos.TOP_LEFT);
        setCenter(box);
        // 随 SplitPane 比例伸缩；min=0 以免顶破父级 8.5:1.5
        setMinWidth(0);
        setPrefWidth(Region.USE_COMPUTED_SIZE);
        setMaxWidth(Double.MAX_VALUE);
        setPadding(new Insets(8, 8, 8, 4));
    }

    public void setPageContext(String pageKindName, boolean manual, boolean unknown) {
        // no-op
    }
}
