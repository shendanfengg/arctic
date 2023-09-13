package com.netease.arctic.ams.server.mapper;

import com.netease.arctic.table.TableIdentifier;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * Created by shendanfeng on 2023/9/13.
 */
public interface OptimizeCommitFailedHistoryMapper {
  String TABLE_NAME = "optimize_commit_failed_history";

  @Insert("insert into " + TABLE_NAME + " (catalog_name, db_name, table_name, fail_reason, fail_time) values(" +
      " #{tableIdentifier.catalog}, #{tableIdentifier.database}, #{tableIdentifier.tableName}, #{failReason}, " +
      "#{failTime})")
  public void insertOptimizeCommitFailedHistory(@Param("tableIdentifier") TableIdentifier tableIdentifier,
                                                @Param("failReason") String failReason,
                                                @Param("failTime") long failTime);
}
