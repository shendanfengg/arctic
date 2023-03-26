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

import com.netease.arctic.ams.api.OptimizeStatus;
import com.netease.arctic.ams.api.OptimizeType;
import com.netease.arctic.ams.api.TreeNode;
import com.netease.arctic.ams.server.model.BaseOptimizeTask;
import com.netease.arctic.ams.server.model.BaseOptimizeTaskRuntime;
import com.netease.arctic.ams.server.model.TableOptimizeRuntime;
import com.netease.arctic.ams.server.util.DataFileInfoUtils;
import com.netease.arctic.data.DataTreeNode;
import com.netease.arctic.data.DefaultKeyedFile;
import com.netease.arctic.hive.HiveTableProperties;
import com.netease.arctic.hive.table.SupportHive;
import com.netease.arctic.table.ArcticTable;
import com.netease.arctic.table.TableProperties;
import com.netease.arctic.utils.FileUtil;
import com.netease.arctic.utils.SerializationUtil;
import com.netease.arctic.utils.TablePropertyUtil;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class TestSupportHiveMajorOptimizeCommit extends TestSupportHiveBase {
  @Test
  public void testKeyedTableMajorOptimizeSupportHiveHasPosDeleteCommit() throws Exception {
    List<DataFile> baseDataFiles = insertTableBaseDataFiles(testKeyedHiveTable, 1L);
    baseDataFilesInfo.addAll(baseDataFiles.stream()
        .map(dataFile ->
            DataFileInfoUtils.convertToDatafileInfo(dataFile, System.currentTimeMillis(), testKeyedHiveTable))
        .collect(Collectors.toList()));

    Set<DataTreeNode> targetNodes = baseDataFilesInfo.stream()
        .map(dataFileInfo -> DataTreeNode.of(dataFileInfo.getMask(), dataFileInfo.getIndex())).collect(Collectors.toSet());
    List<DeleteFile> deleteFiles = insertBasePosDeleteFiles(testKeyedHiveTable, 2L, baseDataFiles, targetNodes);
    posDeleteFilesInfo.addAll(deleteFiles.stream()
        .map(deleteFile ->
            DataFileInfoUtils.convertToDatafileInfo(deleteFile, System.currentTimeMillis(), testKeyedHiveTable.asKeyedTable()))
        .collect(Collectors.toList()));

    Set<String> oldDataFilesPath = new HashSet<>();
    Set<String> oldDeleteFilesPath = new HashSet<>();
    testKeyedHiveTable.baseTable().newScan().planFiles()
        .forEach(fileScanTask -> {
          oldDataFilesPath.add((String) fileScanTask.file().path());
          fileScanTask.deletes().forEach(deleteFile -> oldDeleteFilesPath.add((String) deleteFile.path()));
        });

    TableOptimizeRuntime tableOptimizeRuntime = new TableOptimizeRuntime(testKeyedHiveTable.id());
    SupportHiveMajorOptimizePlan majorOptimizePlan = new SupportHiveMajorOptimizePlan(testKeyedHiveTable,
        tableOptimizeRuntime, baseDataFilesInfo, posDeleteFilesInfo,
        new HashMap<>(), 1, System.currentTimeMillis());
    List<BaseOptimizeTask> tasks = majorOptimizePlan.plan();
    Assert.assertEquals(OptimizeType.Major, tasks.get(0).getTaskId().getType());

    Map<TreeNode, List<DataFile>> resultFiles = generateTargetFiles(testKeyedHiveTable, tasks.get(0).getTaskId().getType());
    List<OptimizeTaskItem> taskItems = tasks.stream().map(task -> {
      BaseOptimizeTaskRuntime optimizeRuntime = new BaseOptimizeTaskRuntime(task.getTaskId());
      List<DataFile> targetFiles = resultFiles.get(task.getSourceNodes().get(0));
      optimizeRuntime.setPreparedTime(System.currentTimeMillis());
      optimizeRuntime.setStatus(OptimizeStatus.Prepared);
      optimizeRuntime.setReportTime(System.currentTimeMillis());
      optimizeRuntime.setNewFileCnt(targetFiles == null ? 0 : targetFiles.size());
      if (targetFiles != null) {
        optimizeRuntime.setNewFileSize(targetFiles.get(0).fileSizeInBytes());
        optimizeRuntime.setTargetFiles(targetFiles.stream().map(SerializationUtil::toByteBuffer).collect(Collectors.toList()));
      }
      // 1min
      optimizeRuntime.setCostTime(60 * 1000);
      return new OptimizeTaskItem(task, optimizeRuntime);
    }).collect(Collectors.toList());
    Map<String, List<OptimizeTaskItem>> partitionTasks = taskItems.stream()
        .collect(Collectors.groupingBy(taskItem -> taskItem.getOptimizeTask().getPartition()));

    ((SupportHive) testKeyedHiveTable.baseTable()).getHMSClient().run(client -> {
      List<Partition> partitions = client.listPartitions(testKeyedHiveTable.id().getDatabase(),
          testKeyedHiveTable.id().getTableName(), Short.MAX_VALUE);
      Assert.assertEquals(0, partitions.size());
      return null;
    });

    SupportHiveCommit optimizeCommit = new SupportHiveCommit(testKeyedHiveTable, partitionTasks, taskItem -> {
    });
    optimizeCommit.commit(testKeyedHiveTable.baseTable().currentSnapshot().snapshotId());

    Set<String> newDataFilesPath = new HashSet<>();
    Set<String> newDeleteFilesPath = new HashSet<>();
    testKeyedHiveTable.baseTable().newScan().planFiles()
        .forEach(fileScanTask -> {
          newDataFilesPath.add((String) fileScanTask.file().path());
          fileScanTask.deletes().forEach(deleteFile -> newDeleteFilesPath.add((String) deleteFile.path()));
        });
    for (String newFilePath : newDataFilesPath) {
      Assert.assertFalse(newFilePath.contains(testKeyedHiveTable.hiveLocation()));
    }
    Assert.assertNotEquals(oldDataFilesPath, newDataFilesPath);
    Assert.assertNotEquals(oldDeleteFilesPath, newDeleteFilesPath);

    ((SupportHive) testKeyedHiveTable.baseTable()).getHMSClient().run(client -> {
      List<Partition> partitions = client.listPartitions(testKeyedHiveTable.id().getDatabase(),
          testKeyedHiveTable.id().getTableName(), Short.MAX_VALUE);
      Assert.assertEquals(0, partitions.size());
      return null;
    });
  }

  @Test
  public void testKeyedTableMajorOptimizeSupportHiveNoPosDeleteCommit() throws Exception {
    List<DataFile> baseDataFiles = insertTableBaseDataFiles(testKeyedHiveTable, 2L);
    baseDataFilesInfo.addAll(baseDataFiles.stream()
        .map(dataFile -> DataFileInfoUtils.convertToDatafileInfo(dataFile, System.currentTimeMillis(), testKeyedHiveTable))
        .collect(Collectors.toList()));

    Set<String> oldDataFilesPath = new HashSet<>();
    testKeyedHiveTable.baseTable().newScan().planFiles()
        .forEach(fileScanTask -> oldDataFilesPath.add((String) fileScanTask.file().path()));

    TableOptimizeRuntime tableOptimizeRuntime = new TableOptimizeRuntime(testKeyedHiveTable.id());
    SupportHiveMajorOptimizePlan majorOptimizePlan = new SupportHiveMajorOptimizePlan(testKeyedHiveTable,
        tableOptimizeRuntime, baseDataFilesInfo, posDeleteFilesInfo,
        new HashMap<>(), 1, System.currentTimeMillis());
    List<BaseOptimizeTask> tasks = majorOptimizePlan.plan();
    Assert.assertEquals(OptimizeType.Major, tasks.get(0).getTaskId().getType());

    Map<TreeNode, List<DataFile>> resultFiles = generateTargetFiles(testKeyedHiveTable, tasks.get(0).getTaskId().getType());
    List<OptimizeTaskItem> taskItems = tasks.stream().map(task -> {
      BaseOptimizeTaskRuntime optimizeRuntime = new BaseOptimizeTaskRuntime(task.getTaskId());
      List<DataFile> targetFiles = resultFiles.get(task.getSourceNodes().get(0));
      optimizeRuntime.setPreparedTime(System.currentTimeMillis());
      optimizeRuntime.setStatus(OptimizeStatus.Prepared);
      optimizeRuntime.setReportTime(System.currentTimeMillis());
      optimizeRuntime.setNewFileCnt(targetFiles == null ? 0 : targetFiles.size());
      if (targetFiles != null) {
        optimizeRuntime.setNewFileSize(targetFiles.get(0).fileSizeInBytes());
        optimizeRuntime.setTargetFiles(targetFiles.stream().map(SerializationUtil::toByteBuffer).collect(Collectors.toList()));
      }
      // 1min
      optimizeRuntime.setCostTime(60 * 1000);
      return new OptimizeTaskItem(task, optimizeRuntime);
    }).collect(Collectors.toList());
    Map<String, List<OptimizeTaskItem>> partitionTasks = taskItems.stream()
        .collect(Collectors.groupingBy(taskItem -> taskItem.getOptimizeTask().getPartition()));

    ((SupportHive) testKeyedHiveTable.baseTable()).getHMSClient().run(client -> {
      List<Partition> partitions = client.listPartitions(testKeyedHiveTable.id().getDatabase(),
          testKeyedHiveTable.id().getTableName(), Short.MAX_VALUE);
      Assert.assertEquals(0, partitions.size());
      return null;
    });

    SupportHiveCommit optimizeCommit = new SupportHiveCommit(testKeyedHiveTable, partitionTasks, taskItem -> {
    });
    optimizeCommit.commit(testKeyedHiveTable.baseTable().currentSnapshot().snapshotId());

    Set<String> newDataFilesPath = new HashSet<>();
    testKeyedHiveTable.baseTable().newScan().planFiles()
        .forEach(fileScanTask -> newDataFilesPath.add((String) fileScanTask.file().path()));
    for (String newFilePath : newDataFilesPath) {
      Assert.assertTrue(newFilePath.contains(testKeyedHiveTable.hiveLocation()));
    }
    Assert.assertNotEquals(oldDataFilesPath, newDataFilesPath);

    ((SupportHive) testKeyedHiveTable.baseTable()).getHMSClient().run(client -> {
      List<Partition> partitions = client.listPartitions(testKeyedHiveTable.id().getDatabase(),
          testKeyedHiveTable.id().getTableName(), Short.MAX_VALUE);
      Assert.assertEquals(1, partitions.size());
      for (String newFilePath : newDataFilesPath) {
        Assert.assertTrue(newFilePath.contains(partitions.get(0).getSd().getLocation()));
      }
      return null;
    });
  }

  @Test
  public void testKeyedTableFullMajorOptimizeSupportHiveCommit() throws Exception {
    testKeyedHiveTable.updateProperties()
        .set(TableProperties.FULL_OPTIMIZE_TRIGGER_MAX_INTERVAL, "86400000")
        .commit();
    List<DataFile> baseDataFiles = insertTableBaseDataFiles(testKeyedHiveTable, 1L);
    baseDataFilesInfo.addAll(baseDataFiles.stream()
        .map(dataFile ->
            DataFileInfoUtils.convertToDatafileInfo(dataFile, System.currentTimeMillis(), testKeyedHiveTable))
        .collect(Collectors.toList()));

    Set<DataTreeNode> targetNodes = baseDataFilesInfo.stream()
        .map(dataFileInfo -> DataTreeNode.of(dataFileInfo.getMask(), dataFileInfo.getIndex())).collect(Collectors.toSet());
    List<DeleteFile> deleteFiles = insertBasePosDeleteFiles(testKeyedHiveTable, 2L, baseDataFiles, targetNodes);
    posDeleteFilesInfo.addAll(deleteFiles.stream()
        .map(deleteFile ->
            DataFileInfoUtils.convertToDatafileInfo(deleteFile, System.currentTimeMillis(), testKeyedHiveTable.asKeyedTable()))
        .collect(Collectors.toList()));

    Set<String> oldDataFilesPath = new HashSet<>();
    testKeyedHiveTable.baseTable().newScan().planFiles()
        .forEach(fileScanTask -> oldDataFilesPath.add((String) fileScanTask.file().path()));

    TableOptimizeRuntime tableOptimizeRuntime = new TableOptimizeRuntime(testKeyedHiveTable.id());
    SupportHiveFullOptimizePlan fullOptimizePlan = new SupportHiveFullOptimizePlan(testKeyedHiveTable,
        tableOptimizeRuntime, baseDataFilesInfo, posDeleteFilesInfo,
        new HashMap<>(), 1, System.currentTimeMillis());
    List<BaseOptimizeTask> tasks = fullOptimizePlan.plan();
    Assert.assertEquals(OptimizeType.FullMajor, tasks.get(0).getTaskId().getType());

    Map<TreeNode, List<DataFile>> resultFiles = generateTargetFiles(testKeyedHiveTable, tasks.get(0).getTaskId().getType());
    List<OptimizeTaskItem> taskItems = tasks.stream().map(task -> {
      BaseOptimizeTaskRuntime optimizeRuntime = new BaseOptimizeTaskRuntime(task.getTaskId());
      List<DataFile> targetFiles = resultFiles.get(task.getSourceNodes().get(0));
      optimizeRuntime.setPreparedTime(System.currentTimeMillis());
      optimizeRuntime.setStatus(OptimizeStatus.Prepared);
      optimizeRuntime.setReportTime(System.currentTimeMillis());
      optimizeRuntime.setNewFileCnt(targetFiles == null ? 0 : targetFiles.size());
      if (targetFiles != null) {
        optimizeRuntime.setNewFileSize(targetFiles.get(0).fileSizeInBytes());
        optimizeRuntime.setTargetFiles(targetFiles.stream().map(SerializationUtil::toByteBuffer).collect(Collectors.toList()));
      }
      // 1min
      optimizeRuntime.setCostTime(60 * 1000);
      return new OptimizeTaskItem(task, optimizeRuntime);
    }).collect(Collectors.toList());
    Map<String, List<OptimizeTaskItem>> partitionTasks = taskItems.stream()
        .collect(Collectors.groupingBy(taskItem -> taskItem.getOptimizeTask().getPartition()));

    ((SupportHive) testKeyedHiveTable.baseTable()).getHMSClient().run(client -> {
      List<Partition> partitions = client.listPartitions(testKeyedHiveTable.id().getDatabase(),
          testKeyedHiveTable.id().getTableName(), Short.MAX_VALUE);
      Assert.assertEquals(0, partitions.size());
      return null;
    });

    SupportHiveCommit optimizeCommit = new SupportHiveCommit(testKeyedHiveTable, partitionTasks, taskItem -> {
    });
    optimizeCommit.commit(testKeyedHiveTable.baseTable().currentSnapshot().snapshotId());

    Set<String> newDataFilesPath = new HashSet<>();
    testKeyedHiveTable.baseTable().newScan().planFiles()
        .forEach(fileScanTask -> newDataFilesPath.add((String) fileScanTask.file().path()));
    for (String newFilePath : newDataFilesPath) {
      Assert.assertTrue(newFilePath.contains(testKeyedHiveTable.hiveLocation()));
    }
    Assert.assertNotEquals(oldDataFilesPath, newDataFilesPath);

    ((SupportHive) testKeyedHiveTable.baseTable()).getHMSClient().run(client -> {
      List<Partition> partitions = client.listPartitions(testKeyedHiveTable.id().getDatabase(),
          testKeyedHiveTable.id().getTableName(), Short.MAX_VALUE);
      Assert.assertEquals(1, partitions.size());
      for (String newFilePath : newDataFilesPath) {
        Assert.assertTrue(newFilePath.contains(partitions.get(0).getSd().getLocation()));
      }
      return null;
    });
  }

  @Test
  public void testUnKeyedTableMajorOptimizeSupportHiveCommit() throws Exception {
    List<DataFile> baseDataFiles = insertTableBaseDataFiles(testHiveTable, null);
    baseDataFilesInfo.addAll(baseDataFiles.stream()
        .map(dataFile -> DataFileInfoUtils.convertToDatafileInfo(dataFile, System.currentTimeMillis(), testHiveTable))
        .collect(Collectors.toList()));

    Set<String> oldDataFilesPath = new HashSet<>();
    testHiveTable.asUnkeyedTable().newScan().planFiles()
        .forEach(fileScanTask -> oldDataFilesPath.add((String) fileScanTask.file().path()));

    TableOptimizeRuntime tableOptimizeRuntime = new TableOptimizeRuntime(testHiveTable.id());
    SupportHiveMajorOptimizePlan majorOptimizePlan = new SupportHiveMajorOptimizePlan(testHiveTable,
        tableOptimizeRuntime, baseDataFilesInfo, posDeleteFilesInfo,
        new HashMap<>(), 1, System.currentTimeMillis());
    List<BaseOptimizeTask> tasks = majorOptimizePlan.plan();
    Assert.assertEquals(OptimizeType.Major, tasks.get(0).getTaskId().getType());

    Map<TreeNode, List<DataFile>> resultFiles = generateTargetFiles(testHiveTable, tasks.get(0).getTaskId().getType());
    List<OptimizeTaskItem> taskItems = tasks.stream().map(task -> {
      BaseOptimizeTaskRuntime optimizeRuntime = new BaseOptimizeTaskRuntime(task.getTaskId());
      ContentFile<?> baseFile = SerializationUtil.toInternalTableFile(task.getBaseFiles().get(0));
      DataTreeNode dataTreeNode = FileUtil.parseFileNodeFromFileName(baseFile.path().toString());
      TreeNode treeNode = new TreeNode(dataTreeNode.getMask(), dataTreeNode.getIndex());
      List<DataFile> targetFiles = resultFiles.get(treeNode);
      optimizeRuntime.setPreparedTime(System.currentTimeMillis());
      optimizeRuntime.setStatus(OptimizeStatus.Prepared);
      optimizeRuntime.setReportTime(System.currentTimeMillis());
      optimizeRuntime.setNewFileCnt(targetFiles.size());
      optimizeRuntime.setNewFileSize(targetFiles.get(0).fileSizeInBytes());
      optimizeRuntime.setTargetFiles(targetFiles.stream().map(SerializationUtil::toByteBuffer).collect(Collectors.toList()));
      // 1min
      optimizeRuntime.setCostTime(60 * 1000);
      return new OptimizeTaskItem(task, optimizeRuntime);
    }).collect(Collectors.toList());
    Map<String, List<OptimizeTaskItem>> partitionTasks = taskItems.stream()
        .collect(Collectors.groupingBy(taskItem -> taskItem.getOptimizeTask().getPartition()));

    ((SupportHive) testHiveTable.asUnkeyedTable()).getHMSClient().run(client -> {
      List<Partition> partitions = client.listPartitions(testHiveTable.id().getDatabase(),
          testHiveTable.id().getTableName(), Short.MAX_VALUE);
      Assert.assertEquals(0, partitions.size());
      return null;
    });

    SupportHiveCommit optimizeCommit = new SupportHiveCommit(testHiveTable, partitionTasks, taskItem -> {
    });
    optimizeCommit.commit(testHiveTable.asUnkeyedTable().currentSnapshot().snapshotId());

    Set<String> newDataFilesPath = new HashSet<>();
    testHiveTable.asUnkeyedTable().newScan().planFiles()
        .forEach(fileScanTask -> newDataFilesPath.add((String) fileScanTask.file().path()));
    for (String newFilePath : newDataFilesPath) {
      Assert.assertTrue(newFilePath.contains(testHiveTable.hiveLocation()));
    }
    Assert.assertNotEquals(oldDataFilesPath, newDataFilesPath);

    ((SupportHive) testHiveTable.asUnkeyedTable()).getHMSClient().run(client -> {
      List<Partition> partitions = client.listPartitions(testHiveTable.id().getDatabase(),
          testHiveTable.id().getTableName(), Short.MAX_VALUE);
      Assert.assertEquals(1, partitions.size());
      for (String newFilePath : newDataFilesPath) {
        Assert.assertTrue(newFilePath.contains(partitions.get(0).getSd().getLocation()));
      }
      return null;
    });
  }

  @Test
  public void testUnKeyedTableFullMajorOptimizeSupportHiveCommit() throws Exception {
    testHiveTable.updateProperties()
        .set(TableProperties.FULL_OPTIMIZE_TRIGGER_MAX_INTERVAL, "86400000")
        .commit();
    List<DataFile> baseDataFiles = insertTableBaseDataFiles(testHiveTable, null);
    baseDataFilesInfo.addAll(baseDataFiles.stream()
        .map(dataFile -> DataFileInfoUtils.convertToDatafileInfo(dataFile, System.currentTimeMillis(), testHiveTable))
        .collect(Collectors.toList()));

    Set<String> oldDataFilesPath = new HashSet<>();
    testHiveTable.asUnkeyedTable().newScan().planFiles()
        .forEach(fileScanTask -> oldDataFilesPath.add((String) fileScanTask.file().path()));

    TableOptimizeRuntime tableOptimizeRuntime = new TableOptimizeRuntime(testHiveTable.id());
    SupportHiveFullOptimizePlan fullOptimizePlan = new SupportHiveFullOptimizePlan(testHiveTable,
        tableOptimizeRuntime, baseDataFilesInfo, posDeleteFilesInfo,
        new HashMap<>(), 1, System.currentTimeMillis());
    List<BaseOptimizeTask> tasks = fullOptimizePlan.plan();
    Assert.assertEquals(OptimizeType.FullMajor, tasks.get(0).getTaskId().getType());

    Map<TreeNode, List<DataFile>> resultFiles = generateTargetFiles(testHiveTable, tasks.get(0).getTaskId().getType());
    List<OptimizeTaskItem> taskItems = tasks.stream().map(task -> {
      BaseOptimizeTaskRuntime optimizeRuntime = new BaseOptimizeTaskRuntime(task.getTaskId());
      ContentFile<?> baseFile = SerializationUtil.toInternalTableFile(task.getBaseFiles().get(0));
      DataTreeNode dataTreeNode = FileUtil.parseFileNodeFromFileName(baseFile.path().toString());
      TreeNode treeNode = new TreeNode(dataTreeNode.getMask(), dataTreeNode.getIndex());
      List<DataFile> targetFiles = resultFiles.get(treeNode);
      optimizeRuntime.setPreparedTime(System.currentTimeMillis());
      optimizeRuntime.setStatus(OptimizeStatus.Prepared);
      optimizeRuntime.setReportTime(System.currentTimeMillis());
      optimizeRuntime.setNewFileCnt(targetFiles.size());
      optimizeRuntime.setNewFileSize(targetFiles.get(0).fileSizeInBytes());
      optimizeRuntime.setTargetFiles(targetFiles.stream().map(SerializationUtil::toByteBuffer).collect(Collectors.toList()));
      // 1min
      optimizeRuntime.setCostTime(60 * 1000);
      return new OptimizeTaskItem(task, optimizeRuntime);
    }).collect(Collectors.toList());
    Map<String, List<OptimizeTaskItem>> partitionTasks = taskItems.stream()
        .collect(Collectors.groupingBy(taskItem -> taskItem.getOptimizeTask().getPartition()));

    ((SupportHive) testHiveTable.asUnkeyedTable()).getHMSClient().run(client -> {
      List<Partition> partitions = client.listPartitions(testHiveTable.id().getDatabase(),
          testHiveTable.id().getTableName(), Short.MAX_VALUE);
      Assert.assertEquals(0, partitions.size());
      return null;
    });

    SupportHiveCommit optimizeCommit = new SupportHiveCommit(testHiveTable, partitionTasks, taskItem -> {
    });
    optimizeCommit.commit(testHiveTable.asUnkeyedTable().currentSnapshot().snapshotId());

    Set<String> newDataFilesPath = new HashSet<>();
    testHiveTable.asUnkeyedTable().newScan().planFiles()
        .forEach(fileScanTask -> newDataFilesPath.add((String) fileScanTask.file().path()));
    for (String newFilePath : newDataFilesPath) {
      Assert.assertTrue(newFilePath.contains(testHiveTable.hiveLocation()));
    }
    Assert.assertNotEquals(oldDataFilesPath, newDataFilesPath);

    ((SupportHive) testHiveTable.asUnkeyedTable()).getHMSClient().run(client -> {
      List<Partition> partitions = client.listPartitions(testHiveTable.id().getDatabase(),
          testHiveTable.id().getTableName(), Short.MAX_VALUE);
      Assert.assertEquals(1, partitions.size());
      for (String newFilePath : newDataFilesPath) {
        Assert.assertTrue(newFilePath.contains(partitions.get(0).getSd().getLocation()));
      }
      return null;
    });
  }

  @Test
  public void testUnPartitionTableMajorOptimizeSupportHiveCommit() throws Exception {
    List<DataFile> baseDataFiles = insertTableBaseDataFiles(testUnPartitionKeyedHiveTable, 2L);
    baseDataFilesInfo.addAll(baseDataFiles.stream()
        .map(dataFile ->
            DataFileInfoUtils.convertToDatafileInfo(dataFile, System.currentTimeMillis(), testUnPartitionKeyedHiveTable))
        .collect(Collectors.toList()));

    Set<String> oldDataFilesPath = new HashSet<>();
    testUnPartitionKeyedHiveTable.baseTable().newScan().planFiles()
        .forEach(fileScanTask -> oldDataFilesPath.add((String) fileScanTask.file().path()));

    TableOptimizeRuntime tableOptimizeRuntime = new TableOptimizeRuntime(testUnPartitionKeyedHiveTable.id());
    SupportHiveMajorOptimizePlan majorOptimizePlan = new SupportHiveMajorOptimizePlan(testUnPartitionKeyedHiveTable,
        tableOptimizeRuntime, baseDataFilesInfo, posDeleteFilesInfo,
        new HashMap<>(), 1, System.currentTimeMillis());
    List<BaseOptimizeTask> tasks = majorOptimizePlan.plan();
    Assert.assertEquals(OptimizeType.Major, tasks.get(0).getTaskId().getType());

    Map<TreeNode, List<DataFile>> resultFiles = generateTargetFiles(testUnPartitionKeyedHiveTable, tasks.get(0).getTaskId().getType());
    List<OptimizeTaskItem> taskItems = tasks.stream().map(task -> {
      BaseOptimizeTaskRuntime optimizeRuntime = new BaseOptimizeTaskRuntime(task.getTaskId());
      List<DataFile> targetFiles = resultFiles.get(task.getSourceNodes().get(0));
      optimizeRuntime.setPreparedTime(System.currentTimeMillis());
      optimizeRuntime.setStatus(OptimizeStatus.Prepared);
      optimizeRuntime.setReportTime(System.currentTimeMillis());
      optimizeRuntime.setNewFileCnt(targetFiles == null ? 0 : targetFiles.size());
      if (targetFiles != null) {
        optimizeRuntime.setNewFileSize(targetFiles.get(0).fileSizeInBytes());
        optimizeRuntime.setTargetFiles(targetFiles.stream().map(SerializationUtil::toByteBuffer).collect(Collectors.toList()));
      }
      // 1min
      optimizeRuntime.setCostTime(60 * 1000);
      return new OptimizeTaskItem(task, optimizeRuntime);
    }).collect(Collectors.toList());
    Map<String, List<OptimizeTaskItem>> partitionTasks = taskItems.stream()
        .collect(Collectors.groupingBy(taskItem -> taskItem.getOptimizeTask().getPartition()));

    String oldHiveLocation = ((SupportHive) testHiveTable.asUnkeyedTable()).getHMSClient().run(client -> {
      Table table = client.getTable(testUnPartitionKeyedHiveTable.id().getDatabase(),
          testUnPartitionKeyedHiveTable.id().getTableName());
      return table.getSd().getLocation();
    });

    SupportHiveCommit optimizeCommit = new SupportHiveCommit(testUnPartitionKeyedHiveTable, partitionTasks, taskItem -> {
    });
    optimizeCommit.commit(testUnPartitionKeyedHiveTable.baseTable().currentSnapshot().snapshotId());

    Set<String> newDataFilesPath = new HashSet<>();
    testUnPartitionKeyedHiveTable.baseTable().newScan().planFiles()
        .forEach(fileScanTask -> newDataFilesPath.add((String) fileScanTask.file().path()));
    for (String newFilePath : newDataFilesPath) {
      Assert.assertTrue(newFilePath.contains(testUnPartitionKeyedHiveTable.hiveLocation()));
    }
    Assert.assertNotEquals(oldDataFilesPath, newDataFilesPath);

    ((SupportHive) testUnPartitionKeyedHiveTable.asKeyedTable().baseTable()).getHMSClient().run(client -> {
      Table table = client.getTable(testUnPartitionKeyedHiveTable.id().getDatabase(),
          testUnPartitionKeyedHiveTable.id().getTableName());
      Assert.assertEquals(oldHiveLocation, table.getSd().getLocation());
      for (String newFilePath : newDataFilesPath) {
        Assert.assertTrue(newFilePath.contains(table.getSd().getLocation()));
      }
      return null;
    });
  }

  @Test
  public void testUnPartitionTableFullMajorOptimizeSupportHiveCommit() throws Exception {
    testUnPartitionKeyedHiveTable.updateProperties()
        .set(TableProperties.FULL_OPTIMIZE_TRIGGER_MAX_INTERVAL, "86400000")
        .commit();
    List<DataFile> baseDataFiles = insertTableBaseDataFiles(testUnPartitionKeyedHiveTable, 1L);
    baseDataFilesInfo.addAll(baseDataFiles.stream()
        .map(dataFile ->
            DataFileInfoUtils.convertToDatafileInfo(dataFile, System.currentTimeMillis(), testUnPartitionKeyedHiveTable))
        .collect(Collectors.toList()));

    Set<DataTreeNode> targetNodes = baseDataFilesInfo.stream()
        .map(dataFileInfo -> DataTreeNode.of(dataFileInfo.getMask(), dataFileInfo.getIndex())).collect(Collectors.toSet());
    List<DeleteFile> deleteFiles = insertBasePosDeleteFiles(testUnPartitionKeyedHiveTable, 2L, baseDataFiles,
        targetNodes);
    posDeleteFilesInfo.addAll(deleteFiles.stream()
        .map(deleteFile ->
            DataFileInfoUtils.convertToDatafileInfo(deleteFile, System.currentTimeMillis(), testUnPartitionKeyedHiveTable.asKeyedTable()))
        .collect(Collectors.toList()));

    Set<String> oldDataFilesPath = new HashSet<>();
    testUnPartitionKeyedHiveTable.baseTable().newScan().planFiles()
        .forEach(fileScanTask -> oldDataFilesPath.add((String) fileScanTask.file().path()));

    TableOptimizeRuntime tableOptimizeRuntime = new TableOptimizeRuntime(testUnPartitionKeyedHiveTable.id());
    SupportHiveFullOptimizePlan fullOptimizePlan = new SupportHiveFullOptimizePlan(testUnPartitionKeyedHiveTable,
        tableOptimizeRuntime, baseDataFilesInfo, posDeleteFilesInfo,
        new HashMap<>(), 1, System.currentTimeMillis());
    List<BaseOptimizeTask> tasks = fullOptimizePlan.plan();
    Assert.assertEquals(OptimizeType.FullMajor, tasks.get(0).getTaskId().getType());

    Map<TreeNode, List<DataFile>> resultFiles = generateTargetFiles(testUnPartitionKeyedHiveTable, tasks.get(0).getTaskId().getType());
    List<OptimizeTaskItem> taskItems = tasks.stream().map(task -> {
      BaseOptimizeTaskRuntime optimizeRuntime = new BaseOptimizeTaskRuntime(task.getTaskId());
      List<DataFile> targetFiles = resultFiles.get(task.getSourceNodes().get(0));
      optimizeRuntime.setPreparedTime(System.currentTimeMillis());
      optimizeRuntime.setStatus(OptimizeStatus.Prepared);
      optimizeRuntime.setReportTime(System.currentTimeMillis());
      optimizeRuntime.setNewFileCnt(targetFiles == null ? 0 : targetFiles.size());
      if (targetFiles != null) {
        optimizeRuntime.setNewFileSize(targetFiles.get(0).fileSizeInBytes());
        optimizeRuntime.setTargetFiles(targetFiles.stream().map(SerializationUtil::toByteBuffer).collect(Collectors.toList()));
      }
      // 1min
      optimizeRuntime.setCostTime(60 * 1000);
      return new OptimizeTaskItem(task, optimizeRuntime);
    }).collect(Collectors.toList());
    Map<String, List<OptimizeTaskItem>> partitionTasks = taskItems.stream()
        .collect(Collectors.groupingBy(taskItem -> taskItem.getOptimizeTask().getPartition()));

    String oldHiveLocation = ((SupportHive) testHiveTable.asUnkeyedTable()).getHMSClient().run(client -> {
      Table table = client.getTable(testUnPartitionKeyedHiveTable.id().getDatabase(),
          testUnPartitionKeyedHiveTable.id().getTableName());
      return table.getSd().getLocation();
    });

    SupportHiveCommit optimizeCommit = new SupportHiveCommit(testUnPartitionKeyedHiveTable, partitionTasks, taskItem -> {
    });
    optimizeCommit.commit(testUnPartitionKeyedHiveTable.baseTable().currentSnapshot().snapshotId());

    Set<String> newDataFilesPath = new HashSet<>();
    testUnPartitionKeyedHiveTable.baseTable().newScan().planFiles()
        .forEach(fileScanTask -> newDataFilesPath.add((String) fileScanTask.file().path()));
    for (String newFilePath : newDataFilesPath) {
      Assert.assertTrue(newFilePath.contains(testUnPartitionKeyedHiveTable.hiveLocation()));
    }
    Assert.assertNotEquals(oldDataFilesPath, newDataFilesPath);

    ((SupportHive) testUnPartitionKeyedHiveTable.asKeyedTable().baseTable()).getHMSClient().run(client -> {
      Table table = client.getTable(testUnPartitionKeyedHiveTable.id().getDatabase(),
          testUnPartitionKeyedHiveTable.id().getTableName());
      Assert.assertNotEquals(oldHiveLocation, table.getSd().getLocation());
      for (String newFilePath : newDataFilesPath) {
        Assert.assertTrue(newFilePath.contains(table.getSd().getLocation()));
      }
      return null;
    });
  }

  private Map<TreeNode, List<DataFile>> generateTargetFiles(ArcticTable arcticTable,
                                                            OptimizeType optimizeType) throws IOException {
    List<DataFile> dataFiles = insertOptimizeTargetDataFiles(arcticTable, optimizeType, 3);
    return dataFiles.stream().collect(Collectors.groupingBy(dataFile -> {
      DefaultKeyedFile keyedFile = new DefaultKeyedFile(dataFile);
      return keyedFile.node().toAmsTreeNode();
    }));
  }
}
