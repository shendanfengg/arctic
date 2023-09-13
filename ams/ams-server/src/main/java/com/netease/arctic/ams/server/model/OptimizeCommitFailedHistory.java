package com.netease.arctic.ams.server.model;

import com.netease.arctic.table.TableIdentifier;

/**
 * Created by shendanfeng on 2023/9/13.
 */
public class OptimizeCommitFailedHistory {
  private TableIdentifier tableIdentifier;

  private String failReason;

  private long failTime;

  public OptimizeCommitFailedHistory() {
  }

  public OptimizeCommitFailedHistory(TableIdentifier tableIdentifier, String failReason, long failTime) {
    this.tableIdentifier = tableIdentifier;
    this.failReason = failReason;
    this.failTime = failTime;
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

  @Override
  public String toString() {
    return "OptimizeCommitFailedHistory{" +
        "tableIdentifier=" + tableIdentifier +
        ", failReason='" + failReason + '\'' +
        ", failTime=" + failTime +
        '}';
  }
}
