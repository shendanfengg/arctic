/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.table;

import com.netease.arctic.AmsClient;
import com.netease.arctic.ams.api.TableMeta;
import com.netease.arctic.io.ArcticFileIO;
import com.netease.arctic.op.KeyedPartitionRewrite;
import com.netease.arctic.op.KeyedSchemaUpdate;
import com.netease.arctic.op.OverwriteBaseFiles;
import com.netease.arctic.op.RewritePartitions;
import com.netease.arctic.op.UpdateKeyedTableProperties;
import com.netease.arctic.scan.BaseKeyedTableScan;
import com.netease.arctic.scan.KeyedTableScan;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.UpdateProperties;
import org.apache.iceberg.UpdateSchema;
import org.apache.thrift.TException;

import java.util.Map;

/**
 * Base implementation of {@link KeyedTable}, wrapping a {@link BaseTable} and a {@link ChangeTable}.
 */
public class BaseKeyedTable implements KeyedTable {
  private final String tableLocation;
  private final PrimaryKeySpec primaryKeySpec;
  protected final AmsClient client;

  protected final BaseTable baseTable;
  protected final ChangeTable changeTable;
  protected TableMeta tableMeta;

  public BaseKeyedTable(
      TableMeta tableMeta, String tableLocation,
      PrimaryKeySpec primaryKeySpec, AmsClient client, BaseTable baseTable, ChangeTable changeTable) {
    this.tableMeta = tableMeta;
    this.tableLocation = tableLocation;
    this.primaryKeySpec = primaryKeySpec;
    this.client = client;
    this.baseTable = baseTable;
    this.changeTable = changeTable;
  }

  @Override
  public Schema schema() {
    KeyedSchemaUpdate.syncSchema(this);
    return baseTable.schema();
  }

  @Override
  public PartitionSpec spec() {
    return baseTable.spec();
  }

  @Override
  public TableIdentifier id() {
    return TableIdentifier.of(tableMeta.getTableIdentifier());
  }

  @Override
  public PrimaryKeySpec primaryKeySpec() {
    return primaryKeySpec;
  }

  @Override
  public String location() {
    return tableLocation;
  }

  @Override
  public String baseLocation() {
    return baseTable.location();
  }

  @Override
  public String changeLocation() {
    return changeTable.location();
  }

  @Override
  public Map<String, String> properties() {
    return baseTable.properties();
  }

  @Override
  public ArcticFileIO io() {
    return baseTable.io();
  }

  @Override
  public void refresh() {
    try {
      this.tableMeta = client.getTable(this.tableMeta.getTableIdentifier());
    } catch (TException e) {
      throw new IllegalStateException("failed refresh table from ams", e);
    }

    baseTable.refresh();
    if (primaryKeySpec().primaryKeyExisted()) {
      changeTable.refresh();
    }
  }

  @Override
  public BaseTable baseTable() {
    return baseTable;
  }

  @Override
  public ChangeTable changeTable() {
    return changeTable;
  }

  @Override
  public KeyedTableScan newScan() {
    return new BaseKeyedTableScan(this);
  }

  @Override
  public UpdateSchema updateSchema() {
    if (PrimaryKeySpec.noPrimaryKey().equals(primaryKeySpec())) {
      return baseTable().updateSchema();
    }
    return new KeyedSchemaUpdate(this);
  }

  @Override
  public UpdateProperties updateProperties() {
    return new UpdateKeyedTableProperties(this, tableMeta);
  }

  @Override
  public long beginTransaction(String signature) {
    try {
      return client.allocateTransactionId(this.tableMeta.getTableIdentifier(), signature);
    } catch (TException e) {
      throw new IllegalStateException("failed begin transaction", e);
    }
  }

  @Override
  public String toString() {
    return name();
  }

  @Override
  public OverwriteBaseFiles newOverwriteBaseFiles() {
    return new OverwriteBaseFiles(this);
  }

  @Override
  public RewritePartitions newRewritePartitions() {
    return new KeyedPartitionRewrite(this);
  }

  public static class BaseInternalTable extends BaseUnkeyedTable implements BaseTable {

    public BaseInternalTable(
        TableIdentifier tableIdentifier, Table baseIcebergTable, ArcticFileIO arcticFileIO,
        AmsClient client, Map<String, String> catalogProperties) {
      super(tableIdentifier, baseIcebergTable, arcticFileIO, client, catalogProperties);
    }
  }

  public static class ChangeInternalTable extends BaseUnkeyedTable implements ChangeTable {

    public ChangeInternalTable(
        TableIdentifier tableIdentifier, Table changeIcebergTable, ArcticFileIO arcticFileIO,
        AmsClient client, Map<String, String> catalogProperties) {
      super(tableIdentifier, changeIcebergTable, arcticFileIO, client, catalogProperties);
    }
  }
}