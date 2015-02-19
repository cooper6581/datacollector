/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.stage.destination.hdfs;

import com.streamsets.pipeline.api.Label;

//  TODO: SCD-65
//  public enum TimeFrom implements BaseEnumChooserValues.EnumWithLabel {
//    DATA_COLLECTOR_CLOCK;
//
//    @Override
//    public String getLabel() {
//      return "Use clock from Data Collector host";
//    }
//
//  }
public enum HdfsLateRecordsAction implements Label {
  SEND_TO_ERROR("Send to error"),
  SEND_TO_LATE_RECORDS_FILE("Send to late records file"),
  DISCARD("Discard"),

  ;
  private final String label;
  HdfsLateRecordsAction(String label) {
    this.label = label;
  }

  @Override
  public String getLabel() {
    return label;
  }
}