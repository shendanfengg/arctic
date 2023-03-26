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

import com.google.common.collect.ImmutableList;
import com.netease.arctic.ams.api.DataFileInfo;
import com.netease.arctic.ams.api.OptimizeType;
import com.netease.arctic.ams.server.model.BaseOptimizeTask;
import com.netease.arctic.ams.server.model.FileTree;
import com.netease.arctic.ams.server.model.TableOptimizeRuntime;
import com.netease.arctic.ams.server.model.TaskConfig;
import com.netease.arctic.ams.server.utils.ContentFileUtil;
import com.netease.arctic.data.DataFileType;
import com.netease.arctic.data.DataTreeNode;
import com.netease.arctic.table.ArcticTable;
import com.netease.arctic.table.TableProperties;
import com.netease.arctic.table.UnkeyedTable;
import com.netease.arctic.utils.FileUtil;
import com.netease.arctic.utils.IdGenerator;
import org.apache.commons.collections.CollectionUtils;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileContent;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.util.BinPacking;
import org.apache.iceberg.util.PropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class FullOptimizePlan extends BaseOptimizePlan {
  private static final Logger LOG = LoggerFactory.getLogger(FullOptimizePlan.class);

  public FullOptimizePlan(ArcticTable arcticTable, TableOptimizeRuntime tableOptimizeRuntime,
                          List<DataFileInfo> baseTableFileList, List<DataFileInfo> posDeleteFileList,
                          Map<String, Boolean> partitionTaskRunning, int queueId, long currentTime) {
    super(arcticTable, tableOptimizeRuntime, baseTableFileList, Collections.emptyList(), posDeleteFileList,
        partitionTaskRunning, queueId, currentTime);
  }

  @Override
  protected boolean partitionNeedPlan(String partitionToPath) {
    long current = System.currentTimeMillis();

    // check position delete file total size
    if (checkPosDeleteTotalSize(partitionToPath)) {
      partitionOptimizeType.put(partitionToPath, OptimizeType.FullMajor);
      return true;
    }

    // check full optimize interval
    if (checkFullOptimizeInterval(current, partitionToPath)) {
      partitionOptimizeType.put(partitionToPath, OptimizeType.FullMajor);
      return true;
    }

    LOG.debug("{} ==== don't need {} optimize plan, skip partition {}", tableId(), getOptimizeType(), partitionToPath);
    return false;
  }

  @Override
  protected void addOptimizeFilesTree() {
    addBaseFileIntoFileTree();
  }

  @Override
  protected OptimizeType getOptimizeType() {
    return OptimizeType.FullMajor;
  }

  @Override
  protected List<BaseOptimizeTask> collectTask(String partition) {
    List<BaseOptimizeTask> result;
    if (arcticTable.isUnkeyedTable()) {
      // if Full, optimize all files in file tree.
      List<DataFile> fileList = partitionFileTree.get(partition).getBaseFiles();
      result = collectUnKeyedTableTasks(partition, fileList);
      // init files
      partitionFileTree.get(partition).initFiles();
    } else {
      FileTree treeRoot = partitionFileTree.get(partition);
      result = collectKeyedTableTasks(partition, treeRoot);
      // init files
      partitionPosDeleteFiles.put(partition, Collections.emptyList());
      partitionFileTree.get(partition).initFiles();
    }

    return result;
  }

  @Override
  protected boolean tableChanged() {
    return true;
  }


  protected boolean checkPosDeleteTotalSize(String partitionToPath) {
    long posDeleteSize = partitionPosDeleteFiles.get(partitionToPath) == null ?
        0 : partitionPosDeleteFiles.get(partitionToPath).stream().mapToLong(DeleteFile::fileSizeInBytes).sum();
    return posDeleteSize >= PropertyUtil.propertyAsLong(arcticTable.properties(),
        TableProperties.FULL_OPTIMIZE_TRIGGER_DELETE_FILE_SIZE_BYTES,
        TableProperties.FULL_OPTIMIZE_TRIGGER_DELETE_FILE_SIZE_BYTES_DEFAULT);
  }

  protected boolean checkFullOptimizeInterval(long current, String partitionToPath) {
    long fullMajorOptimizeInterval = PropertyUtil.propertyAsLong(arcticTable.properties(),
        TableProperties.FULL_OPTIMIZE_TRIGGER_MAX_INTERVAL,
        TableProperties.FULL_OPTIMIZE_TRIGGER_MAX_INTERVAL_DEFAULT);

    if (fullMajorOptimizeInterval != TableProperties.FULL_OPTIMIZE_TRIGGER_MAX_INTERVAL_DEFAULT) {
      long lastFullMajorOptimizeTime = tableOptimizeRuntime.getLatestFullOptimizeTime(partitionToPath);
      return current - lastFullMajorOptimizeTime >= fullMajorOptimizeInterval;
    }

    return false;
  }

  /**
   * check whether node task need to build
   *
   * @param posDeleteFiles pos-delete files in node
   * @param baseFiles      base files in node
   * @return whether the node task need to build. If true, build task, otherwise skip.
   */
  protected boolean nodeTaskNeedBuild(List<DeleteFile> posDeleteFiles, List<DataFile> baseFiles) {
    List<DataFile> smallFiles = baseFiles.stream().filter(file -> file.fileSizeInBytes() <=
        PropertyUtil.propertyAsLong(arcticTable.properties(), TableProperties.OPTIMIZE_SMALL_FILE_SIZE_BYTES_THRESHOLD,
            TableProperties.OPTIMIZE_SMALL_FILE_SIZE_BYTES_THRESHOLD_DEFAULT)).collect(Collectors.toList());
    return CollectionUtils.isNotEmpty(posDeleteFiles) || smallFiles.size() >= 2;
  }

  private List<BaseOptimizeTask> collectUnKeyedTableTasks(String partition, List<DataFile> fileList) {
    List<BaseOptimizeTask> collector = new ArrayList<>();

    List<DeleteFile> posDeleteFiles = partitionPosDeleteFiles.getOrDefault(partition, Collections.emptyList());
    if (nodeTaskNeedBuild(posDeleteFiles, fileList)) {
      String commitGroup = UUID.randomUUID().toString();
      long createTime = System.currentTimeMillis();
      TaskConfig taskPartitionConfig = new TaskConfig(partition,
          null, commitGroup, planGroup, partitionOptimizeType.get(partition), createTime,
          constructCustomHiveSubdirectory(arcticTable.isKeyedTable() ?
              getMaxTransactionId(fileList) : IdGenerator.randomId()));

      long taskSize =
          PropertyUtil.propertyAsLong(arcticTable.properties(), TableProperties.MAJOR_OPTIMIZE_MAX_TASK_FILE_SIZE,
              TableProperties.MAJOR_OPTIMIZE_MAX_TASK_FILE_SIZE_DEFAULT);
      Long sum = fileList.stream().map(DataFile::fileSizeInBytes).reduce(0L, Long::sum);
      int taskCnt = (int) (sum / taskSize) + 1;
      List<List<DataFile>> packed = new BinPacking.ListPacker<DataFile>(taskSize, taskCnt, true)
          .pack(fileList, DataFile::fileSizeInBytes);
      for (List<DataFile> files : packed) {
        if (CollectionUtils.isNotEmpty(files)) {
          collector.add(buildOptimizeTask(null,
              Collections.emptyList(), Collections.emptyList(), files, posDeleteFiles, taskPartitionConfig));
        }
      }
    }

    return collector;
  }

  private List<BaseOptimizeTask> collectKeyedTableTasks(String partition, FileTree treeRoot) {
    List<BaseOptimizeTask> collector = new ArrayList<>();
    String commitGroup = UUID.randomUUID().toString();
    long createTime = System.currentTimeMillis();

    treeRoot.completeTree(false);
    List<DataFile> allBaseFiles = new ArrayList<>();
    treeRoot.collectBaseFiles(allBaseFiles);
    TaskConfig taskPartitionConfig = new TaskConfig(partition,
        null, commitGroup, planGroup, partitionOptimizeType.get(partition), createTime,
        constructCustomHiveSubdirectory(arcticTable.isKeyedTable() ?
            getMaxTransactionId(allBaseFiles) : IdGenerator.randomId()));
    List<FileTree> subTrees = new ArrayList<>();
    // split tasks
    treeRoot.splitSubTree(subTrees, new CanSplitFileTree());
    for (FileTree subTree : subTrees) {
      List<DataFile> baseFiles = new ArrayList<>();
      subTree.collectBaseFiles(baseFiles);
      if (!baseFiles.isEmpty()) {
        List<DataTreeNode> sourceNodes = Collections.singletonList(subTree.getNode());
        Set<DataTreeNode> baseFileNodes = baseFiles.stream()
            .map(dataFile -> FileUtil.parseFileNodeFromFileName(dataFile.path().toString()))
            .collect(Collectors.toSet());
        List<DeleteFile> posDeleteFiles = partitionPosDeleteFiles
            .computeIfAbsent(partition, e -> Collections.emptyList()).stream()
            .filter(deleteFile ->
                baseFileNodes.contains(FileUtil.parseFileNodeFromFileName(deleteFile.path().toString())))
            .collect(Collectors.toList());

        if (nodeTaskNeedBuild(posDeleteFiles, baseFiles)) {
          collector.add(buildOptimizeTask(sourceNodes,
              Collections.emptyList(), Collections.emptyList(), baseFiles, posDeleteFiles, taskPartitionConfig));
        }
      }
    }

    return collector;
  }

  private void addBaseFileIntoFileTree() {
    LOG.debug("{} start {} plan base files", tableId(), getOptimizeType());
    UnkeyedTable baseTable;
    if (arcticTable.isKeyedTable()) {
      baseTable = arcticTable.asKeyedTable().baseTable();
    } else {
      baseTable = arcticTable.asUnkeyedTable();
    }

    AtomicInteger addCnt = new AtomicInteger();
    List<DataFileInfo> baseFileList = new ArrayList<>();
    baseFileList.addAll(ImmutableList.copyOf(baseTableFileList));
    baseFileList.addAll(ImmutableList.copyOf(posDeleteFileList));
    List<ContentFile<?>> baseOptimizeFiles = baseFileList.stream().map(dataFileInfo -> {
      PartitionSpec partitionSpec = baseTable.specs().get((int) dataFileInfo.getSpecId());
      String partition = dataFileInfo.getPartition() == null ? "" : dataFileInfo.getPartition();

      if (partitionSpec == null) {
        LOG.error("{} {} can not find partitionSpec id: {}", dataFileInfo.getPath(), getOptimizeType(),
            dataFileInfo.specId);
        return null;
      }

      ContentFile<?> contentFile = ContentFileUtil.buildContentFile(dataFileInfo, partitionSpec, fileFormat);
      currentPartitions.add(partition);
      if (!anyTaskRunning(partition)) {
        FileTree treeRoot =
            partitionFileTree.computeIfAbsent(partition, p -> FileTree.newTreeRoot());
        treeRoot.putNodeIfAbsent(DataTreeNode.of(dataFileInfo.getMask(), dataFileInfo.getIndex()))
            .addFile(contentFile,
                DataFileType.valueOf(dataFileInfo.getType()).equals(DataFileType.POS_DELETE_FILE) ?
                    DataFileType.POS_DELETE_FILE : DataFileType.BASE_FILE);

        // fill node position delete file map
        if (contentFile.content() == FileContent.POSITION_DELETES) {
          List<DeleteFile> files = partitionPosDeleteFiles.computeIfAbsent(partition, e -> new ArrayList<>());
          files.add((DeleteFile) contentFile);
          partitionPosDeleteFiles.put(partition, files);
        }

        addCnt.getAndIncrement();
      }
      return contentFile;
    }).filter(Objects::nonNull).collect(Collectors.toList());

    LOG.debug("{} ==== {} add {} base files into tree, total files: {}." + " After added, partition cnt of tree: {}",
        tableId(), getOptimizeType(), addCnt, baseOptimizeFiles.size(), partitionFileTree.size());
  }

  private long getMaxTransactionId(List<DataFile> dataFiles) {
    OptionalLong maxTransactionId = dataFiles.stream()
        .mapToLong(file -> FileUtil.parseFileTidFromFileName(file.path().toString())).max();
    if (maxTransactionId.isPresent()) {
      return maxTransactionId.getAsLong();
    }

    return 0;
  }

  static class CanSplitFileTree implements Predicate<FileTree> {
    /**
     * file tree can't split:
     * - root node is leaf node
     * - root node contains any base files
     *
     * @param fileTree - file tree to split
     * @return true if this fileTree need split
     */
    @Override
    public boolean test(FileTree fileTree) {
      if (fileTree.getLeft() == null && fileTree.getRight() == null) {
        return false;
      }
      List<DataFile> baseFiles = fileTree.getBaseFiles();
      return baseFiles.isEmpty();
    }
  }
}
