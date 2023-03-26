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
import com.netease.arctic.ams.api.OptimizeTaskId;
import com.netease.arctic.ams.api.OptimizeType;
import com.netease.arctic.ams.api.TreeNode;
import com.netease.arctic.ams.api.properties.OptimizeTaskProperties;
import com.netease.arctic.ams.server.model.BaseOptimizeTask;
import com.netease.arctic.ams.server.model.FileTree;
import com.netease.arctic.ams.server.model.FilesStatistics;
import com.netease.arctic.ams.server.model.TableOptimizeRuntime;
import com.netease.arctic.ams.server.model.TaskConfig;
import com.netease.arctic.ams.server.utils.FilesStatisticsBuilder;
import com.netease.arctic.ams.server.utils.UnKeyedTableUtil;
import com.netease.arctic.data.DataTreeNode;
import com.netease.arctic.hive.utils.HiveTableUtil;
import com.netease.arctic.table.ArcticTable;
import com.netease.arctic.table.TableIdentifier;
import com.netease.arctic.table.TableProperties;
import com.netease.arctic.utils.SerializationUtil;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public abstract class BaseOptimizePlan {
  private static final Logger LOG = LoggerFactory.getLogger(BaseOptimizePlan.class);

  protected final ArcticTable arcticTable;
  protected final List<DataFileInfo> baseTableFileList;
  protected final List<DataFileInfo> changeTableFileList;
  protected final List<DataFileInfo> posDeleteFileList;
  protected final TableOptimizeRuntime tableOptimizeRuntime;
  protected final int queueId;
  protected final long currentTime;
  protected final Map<String, Boolean> partitionTaskRunning;
  protected final String planGroup;
  // Whether to customize the directory
  protected boolean isCustomizeDir;
  // table file format
  protected String fileFormat;

  // partition -> fileTree
  protected final Map<String, FileTree> partitionFileTree = new LinkedHashMap<>();
  // partition -> position delete file
  protected final Map<String, List<DeleteFile>> partitionPosDeleteFiles = new LinkedHashMap<>();
  // partition -> optimize type(FullMajor or Major or Minor)
  protected final Map<String, OptimizeType> partitionOptimizeType = new HashMap<>();
  // We store current partitions, for the next plans to decide if any partition reach the max plan interval,
  // if not, the new added partitions will be ignored by mistake.
  // After plan files, current partitions of table will be set.
  protected final Set<String> currentPartitions = new HashSet<>();

  // for base table or unKeyed table
  protected long currentBaseSnapshotId = TableOptimizeRuntime.INVALID_SNAPSHOT_ID;
  // for change table
  protected long currentChangeSnapshotId = TableOptimizeRuntime.INVALID_SNAPSHOT_ID;

  public BaseOptimizePlan(ArcticTable arcticTable, TableOptimizeRuntime tableOptimizeRuntime,
                          List<DataFileInfo> baseTableFileList,
                          List<DataFileInfo> changeTableFileList,
                          List<DataFileInfo> posDeleteFileList,
                          Map<String, Boolean> partitionTaskRunning,
                          int queueId, long currentTime) {
    this.baseTableFileList = baseTableFileList;
    this.changeTableFileList = changeTableFileList;
    this.posDeleteFileList = posDeleteFileList;
    this.arcticTable = arcticTable;
    this.tableOptimizeRuntime = tableOptimizeRuntime;
    this.queueId = queueId;
    this.currentTime = currentTime;
    this.partitionTaskRunning = partitionTaskRunning;
    this.planGroup = UUID.randomUUID().toString();
    this.isCustomizeDir = false;
    this.fileFormat = arcticTable.properties().getOrDefault(TableProperties.DEFAULT_FILE_FORMAT,
        TableProperties.DEFAULT_FILE_FORMAT_DEFAULT);
  }

  /**
   * check whether partition need to plan
   * @param partitionToPath target partition
   * @return whether partition need to plan. if true, partition try to plan, otherwise skip.
   */
  protected abstract boolean partitionNeedPlan(String partitionToPath);

  /**
   * check whether node task need to build
   * @param posDeleteFiles pos-delete files in node
   * @param baseFiles base files in node
   * @return whether the node task need to build. If true, build task, otherwise skip.
   */
  protected abstract boolean nodeTaskNeedBuild(List<DeleteFile> posDeleteFiles, List<DataFile> baseFiles);

  protected abstract void addOptimizeFilesTree();

  protected abstract OptimizeType getOptimizeType();

  protected abstract List<BaseOptimizeTask> collectTask(String partition);

  protected abstract boolean tableChanged();

  public List<BaseOptimizeTask> plan() {
    long startTime = System.nanoTime();

    if (!tableNeedPlan()) {
      LOG.debug("{} === skip {} plan", tableId(), getOptimizeType());
      return Collections.emptyList();
    }

    addOptimizeFilesTree();

    if (!hasFileToOptimize()) {
      return Collections.emptyList();
    }

    List<BaseOptimizeTask> results = collectTasks();

    long endTime = System.nanoTime();
    LOG.debug("{} ==== {} plan tasks cost {} ns, {} ms", tableId(), getOptimizeType(), endTime - startTime,
        (endTime - startTime) / 1_000_000);
    LOG.debug("{} {} plan get {} tasks", tableId(), getOptimizeType(), results.size());
    return results;
  }

  public List<BaseOptimizeTask> collectTasks() {
    List<BaseOptimizeTask> results = new ArrayList<>();

    List<String> skippedPartitions = new ArrayList<>();
    for (Map.Entry<String, FileTree> fileTreeEntry : partitionFileTree.entrySet()) {
      String partition = fileTreeEntry.getKey();

      if (anyTaskRunning(partition)) {
        LOG.warn("{} {} any task running while collect tasks? should not arrive here, partitionPath={}",
            tableId(), getOptimizeType(), partition);
        skippedPartitions.add(partition);
        continue;
      }

      // partition don't need to plan
      if (!partitionNeedPlan(partition)) {
        skippedPartitions.add(partition);
        continue;
      }

      List<BaseOptimizeTask> optimizeTasks = collectTask(partition);
      LOG.debug("{} partition {} ==== collect {} {} tasks", tableId(), partition, optimizeTasks.size(),
          getOptimizeType());
      results.addAll(optimizeTasks);
    }

    LOG.debug("{} ==== after collect {} task, skip partitions {}/{}", tableId(), getOptimizeType(),
        skippedPartitions.size(), partitionFileTree.entrySet().size());
    return results;
  }

  protected BaseOptimizeTask buildOptimizeTask(@Nullable List<DataTreeNode> sourceNodes,
                                               List<DataFile> insertFiles,
                                               List<DataFile> deleteFiles,
                                               List<DataFile> baseFiles,
                                               List<DeleteFile> posDeleteFiles,
                                               TaskConfig taskConfig) {
    // build task
    BaseOptimizeTask optimizeTask = new BaseOptimizeTask();
    optimizeTask.setTaskCommitGroup(taskConfig.getCommitGroup());
    optimizeTask.setTaskPlanGroup(taskConfig.getPlanGroup());
    optimizeTask.setCreateTime(taskConfig.getCreateTime());

    List<ByteBuffer> baseFileBytesList =
        baseFiles.stream()
            .map(SerializationUtil::toByteBuffer)
            .collect(Collectors.toList());
    List<ByteBuffer> insertFileBytesList =
        insertFiles.stream()
            .map(SerializationUtil::toByteBuffer)
            .collect(Collectors.toList());
    List<ByteBuffer> deleteFileBytesList =
        deleteFiles.stream()
            .map(SerializationUtil::toByteBuffer)
            .collect(Collectors.toList());
    List<ByteBuffer> posDeleteFileBytesList =
        posDeleteFiles.stream()
            .map(SerializationUtil::toByteBuffer)
            .collect(Collectors.toList());
    optimizeTask.setBaseFiles(baseFileBytesList);
    optimizeTask.setInsertFiles(insertFileBytesList);
    optimizeTask.setDeleteFiles(deleteFileBytesList);
    optimizeTask.setPosDeleteFiles(posDeleteFileBytesList);

    FilesStatisticsBuilder baseFb = new FilesStatisticsBuilder();
    FilesStatisticsBuilder insertFb = new FilesStatisticsBuilder();
    FilesStatisticsBuilder deleteFb = new FilesStatisticsBuilder();
    FilesStatisticsBuilder posDeleteFb = new FilesStatisticsBuilder();
    baseFiles.stream().map(DataFile::fileSizeInBytes)
        .forEach(baseFb::addFile);
    insertFiles.stream().map(DataFile::fileSizeInBytes)
        .forEach(insertFb::addFile);
    deleteFiles.stream().map(DataFile::fileSizeInBytes)
        .forEach(deleteFb::addFile);
    posDeleteFiles.stream().map(DeleteFile::fileSizeInBytes)
        .forEach(posDeleteFb::addFile);

    FilesStatistics baseFs = baseFb.build();
    FilesStatistics insertFs = insertFb.build();
    FilesStatistics deleteFs = deleteFb.build();
    FilesStatistics posDeleteFs = posDeleteFb.build();

    // file size
    optimizeTask.setBaseFileSize(baseFs.getTotalSize());
    optimizeTask.setInsertFileSize(insertFs.getTotalSize());
    optimizeTask.setDeleteFileSize(deleteFs.getTotalSize());
    optimizeTask.setPosDeleteFileSize(posDeleteFs.getTotalSize());

    // file count
    optimizeTask.setBaseFileCnt(baseFs.getFileCnt());
    optimizeTask.setInsertFileCnt(insertFs.getFileCnt());
    optimizeTask.setDeleteFileCnt(deleteFs.getFileCnt());
    optimizeTask.setPosDeleteFileCnt(posDeleteFs.getFileCnt());

    optimizeTask.setPartition(taskConfig.getPartition());
    optimizeTask.setQueueId(queueId);
    optimizeTask.setTaskId(new OptimizeTaskId(taskConfig.getOptimizeType(), UUID.randomUUID().toString()));
    optimizeTask.setTableIdentifier(arcticTable.id().buildTableIdentifier());

    // for keyed table
    if (sourceNodes != null) {
      optimizeTask.setSourceNodes(sourceNodes.stream()
          .map(node ->
              new TreeNode(node.getMask(), node.getIndex()))
          .collect(Collectors.toList()));
    }
    if (taskConfig.getMaxTransactionId() != null) {
      optimizeTask.setMaxChangeTransactionId(taskConfig.getMaxTransactionId());
    }

    // table ams url
    Map<String, String> properties = new HashMap<>();
    properties.put(OptimizeTaskProperties.ALL_FILE_COUNT, (optimizeTask.getBaseFiles().size() +
        optimizeTask.getInsertFiles().size() + optimizeTask.getDeleteFiles().size()) + "");
    properties.put(OptimizeTaskProperties.CUSTOM_HIVE_SUB_DIRECTORY, taskConfig.getCustomHiveSubdirectory());
    optimizeTask.setProperties(properties);
    return optimizeTask;
  }

  public Map<String, OptimizeType> getPartitionOptimizeType() {
    return partitionOptimizeType;
  }

  public boolean tableNeedPlan() {
    if (arcticTable.isKeyedTable()) {
      this.currentBaseSnapshotId = UnKeyedTableUtil.getSnapshotId(arcticTable.asKeyedTable().baseTable());
      this.currentChangeSnapshotId = UnKeyedTableUtil.getSnapshotId(arcticTable.asKeyedTable().changeTable());
    } else {
      this.currentBaseSnapshotId = UnKeyedTableUtil.getSnapshotId(arcticTable.asUnkeyedTable());
    }

    return tableChanged();
  }

  public boolean hasFileToOptimize() {
    return !partitionFileTree.isEmpty();
  }

  public TableIdentifier tableId() {
    return arcticTable.id();
  }

  public Set<String> getCurrentPartitions() {
    return currentPartitions;
  }

  public long getCurrentBaseSnapshotId() {
    return currentBaseSnapshotId;
  }

  public long getCurrentChangeSnapshotId() {
    return currentChangeSnapshotId;
  }

  protected boolean anyTaskRunning(String partition) {
    return partitionTaskRunning.get(partition) != null && partitionTaskRunning.get(partition);
  }

  protected String constructCustomHiveSubdirectory(long transactionId) {
    String dir = "";
    if (isCustomizeDir) {
      return HiveTableUtil.newHiveSubdirectory(transactionId);
    }
    return dir;
  }
}
