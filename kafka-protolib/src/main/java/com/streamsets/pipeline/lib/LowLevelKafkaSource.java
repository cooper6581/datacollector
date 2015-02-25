/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.lib;

import com.streamsets.pipeline.api.ChooserMode;
import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.ValueChooser;
import com.streamsets.pipeline.config.CsvMode;
import com.streamsets.pipeline.config.CsvModeChooserValues;
import com.streamsets.pipeline.config.JsonModeChooserValues;
import com.streamsets.pipeline.lib.json.StreamingJsonParser;

import java.util.List;

public class LowLevelKafkaSource extends LowLevelAbstractKafkaSource {

  @ConfigDef(required = true,
    type = ConfigDef.Type.MODEL,
    label = "JSON Content",
    description = "Indicates if the JSON files have a single JSON array object or multiple JSON objects",
    defaultValue = "ARRAY_OBJECTS",
    dependsOn = "consumerPayloadType",
    triggeredByValue = {"JSON"},
    group = "JSON_PROPERTIES")
  @ValueChooser(type = ChooserMode.PROVIDED, chooserValues = JsonModeChooserValues.class)
  public StreamingJsonParser.Mode jsonContent;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.BOOLEAN,
    defaultValue = "false",
    label = "Produce Single Record",
    description = "Indicates if multiple json objects must be accommodated in a single record.",
    group = "JSON",
    dependsOn = "jsonContent",
    triggeredByValue = "MULTIPLE_OBJECTS"
  )
  public boolean produceSingleRecord;

  @ConfigDef(required = true,
    type = ConfigDef.Type.INTEGER,
    label = "Maximum JSON Object Length",
    description = "The maximum length for a JSON Object being converted to a record, if greater the full JSON " +
      "object is discarded and processing continues with the next JSON object",
    defaultValue = "4096",
    dependsOn = "consumerPayloadType",
    triggeredByValue = {"JSON"},
    group = "JSON_PROPERTIES")
  public int maxJsonObjectLen;

  @ConfigDef(required = true,
    type = ConfigDef.Type.MODEL,
    label = "CSV Format",
    description = "The specific CSV format of the files",
    group = "CSV_PROPERTIES",
    dependsOn = "consumerPayloadType",
    triggeredByValue = {"DELIMITED"},
    defaultValue = "CSV")
  @ValueChooser(type = ChooserMode.PROVIDED, chooserValues = CsvModeChooserValues.class)
  public CsvMode csvFileFormat;

  private RecordCreator recordCreator;

  @Override
  public void init() throws StageException {
    super.init();
    switch ((consumerPayloadType)) {
      case JSON:
        recordCreator = new JsonRecordCreator(getContext(), jsonContent, maxJsonObjectLen, produceSingleRecord, topic);
        break;
      case TEXT:
        recordCreator = new LogRecordCreator(getContext(), topic);
        break;
      case DELIMITED:
        recordCreator = new CsvRecordCreator(getContext(), csvFileFormat, topic);
        break;
      case XML:
        recordCreator = new XmlRecordCreator(getContext(), topic);
        break;
      default :
    }
  }

  @Override
  protected List<Record> createRecords(MessageAndOffset message , int currentRecordCount) throws StageException {
    return recordCreator.createRecords(message, currentRecordCount);
  }
}