package com.netease.arctic.ams.server.optimize;

import com.google.common.base.Preconditions;
import com.netease.arctic.ams.api.DataFileInfo;
import com.netease.arctic.ams.api.OptimizeType;
import com.netease.arctic.ams.server.model.TableOptimizeRuntime;
import com.netease.arctic.hive.table.SupportHive;
import com.netease.arctic.hive.utils.TableTypeUtil;
import com.netease.arctic.table.ArcticTable;
import com.netease.arctic.table.TableProperties;
import org.apache.commons.collections.CollectionUtils;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.util.PropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class SupportHiveMajorOptimizePlan extends MajorOptimizePlan {
  private static final Logger LOG = LoggerFactory.getLogger(SupportHiveMajorOptimizePlan.class);

  // hive location.
  protected final String hiveLocation;
  // files in locations don't need to major optimize
  protected final Set<String> excludeLocations = new HashSet<>();

  public SupportHiveMajorOptimizePlan(ArcticTable arcticTable, TableOptimizeRuntime tableOptimizeRuntime,
                                      List<DataFileInfo> baseTableFileList, List<DataFileInfo> posDeleteFileList,
                                      Map<String, Boolean> partitionTaskRunning, int queueId, long currentTime) {
    super(arcticTable, tableOptimizeRuntime, baseTableFileList, posDeleteFileList,
        partitionTaskRunning, queueId, currentTime);

    Preconditions.checkArgument(TableTypeUtil.isHive(arcticTable), "The table not support hive");
    hiveLocation = ((SupportHive) arcticTable).hiveLocation();
    excludeLocations.add(hiveLocation);
  }

  @Override
  public boolean partitionNeedPlan(String partitionToPath) {
    long current = System.currentTimeMillis();

    List<DeleteFile> posDeleteFiles = partitionPosDeleteFiles.getOrDefault(partitionToPath, new ArrayList<>());
    List<DataFile> needMajorOptimizeFiles =
        partitionNeedMajorOptimizeFiles.getOrDefault(partitionToPath, new ArrayList<>());
    List<DataFile> smallFiles = filterSmallFiles(partitionToPath, needMajorOptimizeFiles);

    // check whether partition need plan by files info.
    // if partition has no pos-delete file, and there are files in not hive location, need plan
    // if partition has pos-delete, and there are small file count greater than 2 in not hive location, need plan
    boolean hasPos = CollectionUtils.isNotEmpty(posDeleteFiles) && smallFiles.size() >= 2;
    boolean noPos = CollectionUtils.isEmpty(posDeleteFiles) && CollectionUtils.isNotEmpty(needMajorOptimizeFiles);
    boolean partitionNeedPlan = hasPos || noPos;

    // check small data file count
    if (checkSmallFileCount(smallFiles) && partitionNeedPlan) {
      partitionOptimizeType.put(partitionToPath, OptimizeType.Major);
      return true;
    }

    // check major optimize interval
    if (checkMajorOptimizeInterval(current, partitionToPath) && partitionNeedPlan) {
      partitionOptimizeType.put(partitionToPath, OptimizeType.Major);
      return true;
    }

    LOG.debug("{} ==== don't need {} optimize plan, skip partition {}, partitionNeedPlan is {}",
        tableId(), getOptimizeType(), partitionToPath, partitionNeedPlan);
    return false;
  }

  @Override
  protected boolean checkMajorOptimizeInterval(long current, String partitionToPath) {
    if (current - tableOptimizeRuntime.getLatestMajorOptimizeTime(partitionToPath) >=
        PropertyUtil.propertyAsLong(arcticTable.properties(), TableProperties.MAJOR_OPTIMIZE_TRIGGER_MAX_INTERVAL,
            TableProperties.MAJOR_OPTIMIZE_TRIGGER_MAX_INTERVAL_DEFAULT)) {
      // need to rewrite or move all files that not in hive location to hive location.
      long fileCount = partitionNeedMajorOptimizeFiles.get(partitionToPath) == null ?
          0 : partitionNeedMajorOptimizeFiles.get(partitionToPath).size();
      return fileCount >= 1;
    }

    return false;
  }

  @Override
  protected void fillPartitionNeedOptimizeFiles(String partition, ContentFile<?> contentFile) {
    // for support hive table, add all files in iceberg base store and not in hive store
    if (canInclude(contentFile.path().toString())) {
      List<DataFile> files = partitionNeedMajorOptimizeFiles.computeIfAbsent(partition, e -> new ArrayList<>());
      files.add((DataFile) contentFile);
      partitionNeedMajorOptimizeFiles.put(partition, files);
    }
  }

  @Override
  protected boolean nodeTaskNeedBuild(List<DeleteFile> posDeleteFiles, List<DataFile> baseFiles) {
    return true;
  }

  private List<DataFile> filterSmallFiles(String partition, List<DataFile> dataFileList) {
    // for support hive table, filter small files
    List<DataFile> smallFileList = dataFileList.stream().filter(file -> file.fileSizeInBytes() <=
        PropertyUtil.propertyAsLong(arcticTable.properties(), TableProperties.OPTIMIZE_SMALL_FILE_SIZE_BYTES_THRESHOLD,
            TableProperties.OPTIMIZE_SMALL_FILE_SIZE_BYTES_THRESHOLD_DEFAULT)).collect(Collectors.toList());

    // if iceberg store has pos-delete, only optimize small files
    if (CollectionUtils.isNotEmpty(partitionPosDeleteFiles.get(partition))) {
      partitionNeedMajorOptimizeFiles.put(partition, smallFileList);
    }

    return smallFileList;
  }

  private boolean canInclude(String filePath) {
    for (String exclude : excludeLocations) {
      if (filePath.contains(exclude)) {
        return false;
      }
    }
    return true;
  }
}
