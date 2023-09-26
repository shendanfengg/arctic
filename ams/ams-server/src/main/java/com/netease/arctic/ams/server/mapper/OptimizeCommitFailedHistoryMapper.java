package com.netease.arctic.ams.server.mapper;

import com.netease.arctic.ams.server.model.OptimizeCommitFailedHistory;
import org.apache.ibatis.annotations.Insert;

/**
 * Created by shendanfeng on 2023/9/13.
 */
public interface OptimizeCommitFailedHistoryMapper {
  String TABLE_NAME = "optimize_commit_failed_history";


  @Insert("insert into " + TABLE_NAME + "(catalog_name, db_name, table_name, partition, optimize_type, " +
      "plan_group, fail_reason, fail_time) values (" +
      "#{optimizeCommitFailedHistory.tableIdentifier.catalog}, " +
      "#{optimizeCommitFailedHistory.tableIdentifier.database}, " +
      "#{optimizeCommitFailedHistory.tableIdentifier.tableName}, " +
      "#{optimizeCommitFailedHistory.partition}, " +
      "#{optimizeCommitFailedHistory.optimizeType}, " +
      "#{optimizeCommitFailedHistory.planGroup}, " +
      "#{optimizeCommitFailedHistory.failReason}, " +
      "#{optimizeCommitFailedHistory.failTime, typeHandler=com.netease.arctic.ams.server.mybatis.Long2TsConvertor})")
  public void insertOptimizeCommitFailedHistory(OptimizeCommitFailedHistory optimizeCommitFailedHistory);
}
