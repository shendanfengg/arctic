package com.netease.arctic.ams.server.model;

import com.netease.arctic.table.TableIdentifier;

/**
 * Created by shendanfeng on 2023/9/13.
 */
public class OptimizeCommitFailedHistory {
  private TableIdentifier tableIdentifier;
  private String failReason;
  private long failTime;
  private String partition;
  private String optimizeType;
  private String planGroup;

  public OptimizeCommitFailedHistory() {
  }

  public OptimizeCommitFailedHistory(TableIdentifier tableIdentifier, String failReason, long failTime,
                                     String partition, String optimizeType, String planGroup) {
    this.tableIdentifier = tableIdentifier;
    this.failReason = failReason;
    this.failTime = failTime;
    this.partition = partition;
    this.optimizeType = optimizeType;
    this.planGroup = planGroup;
  }

  public TableIdentifier getTableIdentifier() {
    return tableIdentifier;
  }

  public void setTableIdentifier(TableIdentifier tableIdentifier) {
    this.tableIdentifier = tableIdentifier;
  }

  public String getFailReason() {
    return failReason;
  }

  public void setFailReason(String failReason) {
    this.failReason = failReason;
  }

  public long getFailTime() {
    return failTime;
  }

  public void setFailTime(long failTime) {
    this.failTime = failTime;
  }

  public String getPartition() {
    return partition;
  }

  public void setPartition(String partition) {
    this.partition = partition;
  }

  public String getOptimizeType() {
    return optimizeType;
  }

  public void setOptimizeType(String optimizeType) {
    this.optimizeType = optimizeType;
  }

  public String getPlanGroup() {
    return planGroup;
  }

  public void setPlanGroup(String planGroup) {
    this.planGroup = planGroup;
  }

  @Override
  public String toString() {
    return "OptimizeCommitFailedHistory{" +
        "tableIdentifier=" + tableIdentifier +
        ", failReason='" + failReason + '\'' +
        ", failTime=" + failTime +
        '}';
  }
}
