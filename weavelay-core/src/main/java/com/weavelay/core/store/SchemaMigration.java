package com.weavelay.core.store;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 单次 schema / 数据迁移. 版本号单调递增, 禁止改已有迁移内容或调序.
 */
interface SchemaMigration {

    int version();

    String name();

    void apply(Connection connection) throws SQLException;
}
