
package org.apache.ddlutils.platform.gemfirexd;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Adapted from DerbyBuilder for GemFireXD distributed data platform.
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

import java.io.IOException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

import org.apache.ddlutils.DdlUtilsException;
import org.apache.ddlutils.Platform;
import org.apache.ddlutils.model.Column;
import org.apache.ddlutils.model.Index;
import org.apache.ddlutils.model.IndexColumn;
import org.apache.ddlutils.model.ModelException;
import org.apache.ddlutils.model.Table;
import org.apache.ddlutils.platform.derby.DerbyBuilder;

/**
 * The SQL Builder for GemFireXD.
 * 
 * @version $Revision: 279413 $
 */
public final class GemFireXDBuilder extends DerbyBuilder {

  final class ColumnSizeSpec {
    final int _size;
    final String _higherType;

    ColumnSizeSpec(final int size, final String higherType) {
      this._size = size;
      this._higherType = higherType;
    }
  }

  private final Map<Integer, ColumnSizeSpec> _maxSizes;

  /**
   * Creates a new builder instance.
   * 
   * @param platform
   *          The plaftform this builder belongs to
   */
  public GemFireXDBuilder(Platform platform) {
    super(platform);
    _maxSizes = new HashMap<Integer, ColumnSizeSpec>();
    _maxSizes.put(Types.VARCHAR, new ColumnSizeSpec(32672, "CLOB"));
    _maxSizes.put(Types.LONGVARCHAR, new ColumnSizeSpec(32700, "CLOB"));
    _maxSizes.put(Types.VARBINARY, new ColumnSizeSpec(32672, "BLOB"));
    _maxSizes.put(Types.LONGVARBINARY, new ColumnSizeSpec(32700, "BLOB"));
  }

  /**
   * Returns the database-native type for the given column.
   * 
   * @param column
   *          The column
   * @return The native type
   */
  @Override
  protected String getNativeType(Column column) {
    // if the size of types overflows, then try to go to the next larger
    // type (e.g. see #43238)
    final int columnSize = column.getSizeAsInt();
    final int columnType = column.getTypeCode();
    if (columnSize > 0) {
      final ColumnSizeSpec sizeSpec = this._maxSizes.get(columnType);
      if (sizeSpec != null && columnSize >= sizeSpec._size) {
        return sizeSpec._higherType;
      }
    }
    // boost TINYINT and SMALLINT autoincrement columns to INT (#43227)
    if (column.isAutoIncrement()) {
      switch (column.getTypeCode()) {
        case Types.TINYINT:
        case Types.SMALLINT:
          return "INTEGER";
      }
    }
    final String nativeType = getPlatformInfo().getNativeType(columnType);
    return nativeType != null ? nativeType : column.getType();
  }

  // change index name to also include table name (#43964)

  protected String getIndexName(Table table, Index index) {
    final String indexName;
    final String tablePrefix = table.getName() + '_';
    // avoid adding table prefix if index already has it
    if (index.getName().startsWith(tablePrefix)) {
      indexName = index.getName();
    }
    else {
      indexName = tablePrefix + index.getName();
    }
    return shortenName(indexName, getMaxConstraintNameLength());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void writeColumnAutoIncrementStmt(Table table, Column column)
      throws IOException {
    // currently GemFireXD lacks the ability to correctly handle
    // "GENERATED BY DEFAULT AS IDENTITY"
    print("GENERATED ALWAYS AS IDENTITY");
  }

  /**
   * Writes the given index for the table using an external index creation
   * statement. This override adds the table name to index name (#43964).
   * 
   * @param table
   *          The table
   * @param index
   *          The index
   */
  @Override
  public void createIndex(Table table, Index index) throws IOException {
    if (getPlatformInfo().isIndicesSupported()) {
      if (index.getName() == null) {
        _log.warn("Cannot write unnamed index " + index);
      }
      else {
        print("CREATE");
        if (index.isUnique()) {
          print(" UNIQUE");
        }
        print(" INDEX ");
        printIdentifier(getIndexName(table, index));
        print(" ON ");
        printIdentifier(getTableName(table));
        print(" (");

        for (int idx = 0; idx < index.getColumnCount(); idx++) {
          IndexColumn idxColumn = index.getColumn(idx);
          Column col = table.findColumn(idxColumn.getName());

          if (col == null) {
            // would get null pointer on next line anyway, so throw exception
            throw new ModelException("Invalid column '" + idxColumn.getName()
                + "' on index " + index.getName() + " for table "
                + table.getQualifiedName());
          }
          if (idx > 0) {
            print(", ");
          }
          printIdentifier(getColumnName(col));
        }

        print(")");
        printEndOfStatement();
      }
    }
    else {
      throw new DdlUtilsException("This platform does not support indexes");
    }
  }

  /**
   * Writes the given embedded index of the table.
   * 
   * @param table
   *          The table
   * @param index
   *          The index
   */
  @Override
  protected void writeEmbeddedIndexCreateStmt(Table table, Index index)
      throws IOException {
    if ((index.getName() != null) && (index.getName().length() > 0)) {
      print(" CONSTRAINT ");
      printIdentifier(getIndexName(table, index));
    }
    if (index.isUnique()) {
      print(" UNIQUE");
    }
    else {
      print(" INDEX ");
    }
    print(" (");

    for (int idx = 0; idx < index.getColumnCount(); idx++) {
      IndexColumn idxColumn = index.getColumn(idx);
      Column col = table.findColumn(idxColumn.getName());

      if (col == null) {
        // would get null pointer on next line anyway, so throw exception
        throw new ModelException("Invalid column '" + idxColumn.getName()
            + "' on index " + index.getName() + " for table " + table.getQualifiedName());
      }
      if (idx > 0) {
        print(", ");
      }
      printIdentifier(getColumnName(col));
    }

    print(")");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void dropTable(Table table) throws IOException {
    print("DROP TABLE IF EXISTS ");
    printIdentifier(getTableName(table));
    printEndOfStatement();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void dropIndex(Table table, Index index) throws IOException {
    // Index names in GemFireXD are unique to a schema and hence GemFireXD does not
    // use the ON <tablename> clause
    print("DROP INDEX ");
    printIdentifier(getIndexName(table, index));
    printEndOfStatement();
  }

  @Override
  public void writeAddIdentityColumnUsingAlterTable(Table table,
      Column column) throws IOException {
    writeTableAlterStmt(table);
    print("ALTER COLUMN ");
    print(getColumnName(column));
    print(" SET GENERATED ALWAYS AS IDENTITY");
    printEndOfStatement();
  }
}
