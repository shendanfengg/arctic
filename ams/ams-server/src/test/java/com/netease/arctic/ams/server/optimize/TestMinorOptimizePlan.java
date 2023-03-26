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

package com.netease.arctic.ams.server.optimize;

import com.netease.arctic.ams.api.DataFileInfo;
import com.netease.arctic.ams.server.model.BaseOptimizeTask;
import com.netease.arctic.ams.server.model.TableOptimizeRuntime;
import com.netease.arctic.ams.server.util.DataFileInfoUtils;
import com.netease.arctic.data.ChangeAction;
import com.netease.arctic.data.DataTreeNode;
import com.netease.arctic.hive.io.writer.AdaptHiveGenericTaskWriterBuilder;
import com.netease.arctic.table.ArcticTable;
import com.netease.arctic.table.ChangeLocationKind;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.TaskWriter;
import org.apache.iceberg.io.WriteResult;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class TestMinorOptimizePlan extends TestBaseOptimizeBase {
  protected List<DataFileInfo> changeInsertFilesInfo = new ArrayList<>();
  protected List<DataFileInfo> changeDeleteFilesInfo = new ArrayList<>();

  @Test
  public void testMinorOptimize() throws IOException {
    List<DataFile> baseDataFiles = insertTableBaseDataFiles(testKeyedTable, 1L);
    baseDataFilesInfo.addAll(baseDataFiles.stream()
        .map(dataFile ->
            DataFileInfoUtils.convertToDatafileInfo(dataFile, System.currentTimeMillis(), testKeyedTable))
        .collect(Collectors.toList()));

    Set<DataTreeNode> targetNodes = baseDataFilesInfo.stream()
        .map(dataFileInfo -> DataTreeNode.of(dataFileInfo.getMask(), dataFileInfo.getIndex())).collect(Collectors.toSet());
    List<DeleteFile> deleteFiles = insertBasePosDeleteFiles(testKeyedTable, 2L, baseDataFiles, targetNodes);
    posDeleteFilesInfo.addAll(deleteFiles.stream()
        .map(deleteFile ->
            DataFileInfoUtils.convertToDatafileInfo(deleteFile, System.currentTimeMillis(), testKeyedTable.asKeyedTable()))
        .collect(Collectors.toList()));
    insertChangeDeleteFiles(testKeyedTable,3);
    insertChangeDataFiles(testKeyedTable,4);

    List<DataFileInfo> changeTableFilesInfo = new ArrayList<>(changeInsertFilesInfo);
    changeTableFilesInfo.addAll(changeDeleteFilesInfo);
    MinorOptimizePlan minorOptimizePlan = new MinorOptimizePlan(testKeyedTable,
        new TableOptimizeRuntime(testKeyedTable.id()), baseDataFilesInfo, changeTableFilesInfo, posDeleteFilesInfo,
        new HashMap<>(), 1, System.currentTimeMillis());
    List<BaseOptimizeTask> tasks = minorOptimizePlan.plan();
    Assert.assertEquals(4, tasks.size());
    Assert.assertEquals(10, tasks.get(0).getBaseFiles().size());
    Assert.assertEquals(1, tasks.get(0).getPosDeleteFiles().size());
    Assert.assertEquals(10, tasks.get(0).getInsertFileCnt());
    Assert.assertEquals(10, tasks.get(0).getDeleteFileCnt());
  }

  protected List<DataFile> insertChangeDeleteFiles(ArcticTable arcticTable, long transactionId) throws IOException {
    TaskWriter<Record> writer = AdaptHiveGenericTaskWriterBuilder.builderFor(arcticTable)
        .withChangeAction(ChangeAction.DELETE)
        .withTransactionId(transactionId)
        .buildWriter(ChangeLocationKind.INSTANT);

    List<DataFile> changeDeleteFiles = new ArrayList<>();
    // delete 1000 records in 1 partitions(2022-1-1)
    int length = 100;
    for (int i = 1; i < length * 10; i = i + length) {
      for (Record record : baseRecords(i, length, arcticTable.schema())) {
        writer.write(record);
      }
      WriteResult result = writer.complete();
      changeDeleteFiles.addAll(Arrays.asList(result.dataFiles()));
    }
    AppendFiles baseAppend = arcticTable.asKeyedTable().changeTable().newAppend();
    changeDeleteFiles.forEach(baseAppend::appendFile);
    baseAppend.commit();
    long commitTime = System.currentTimeMillis();

    changeDeleteFilesInfo = changeDeleteFiles.stream()
        .map(deleteFile -> DataFileInfoUtils.convertToDatafileInfo(deleteFile, commitTime, arcticTable))
        .collect(Collectors.toList());
    return changeDeleteFiles;
  }

  protected List<DataFile> insertChangeDataFiles(ArcticTable arcticTable, long transactionId) throws IOException {
    TaskWriter<Record> writer = AdaptHiveGenericTaskWriterBuilder.builderFor(arcticTable)
        .withChangeAction(ChangeAction.INSERT)
        .withTransactionId(transactionId)
        .buildWriter(ChangeLocationKind.INSTANT);

    List<DataFile> changeInsertFiles = new ArrayList<>();
    // write 1000 records to 1 partitions(2022-1-1)
    int length = 100;
    for (int i = 1; i < length * 10; i = i + length) {
      for (Record record : baseRecords(i, length, arcticTable.schema())) {
        writer.write(record);
      }
      WriteResult result = writer.complete();
      changeInsertFiles.addAll(Arrays.asList(result.dataFiles()));
    }
    AppendFiles baseAppend = arcticTable.asKeyedTable().changeTable().newAppend();
    changeInsertFiles.forEach(baseAppend::appendFile);
    baseAppend.commit();
    long commitTime = System.currentTimeMillis();

    changeInsertFilesInfo = changeInsertFiles.stream()
        .map(dataFile -> DataFileInfoUtils.convertToDatafileInfo(dataFile, commitTime, arcticTable))
        .collect(Collectors.toList());

    return changeInsertFiles;
  }
}
