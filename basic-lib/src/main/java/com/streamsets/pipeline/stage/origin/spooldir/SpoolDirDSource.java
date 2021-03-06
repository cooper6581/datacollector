/**
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
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
package com.streamsets.pipeline.stage.origin.spooldir;

import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.ConfigGroups;
import com.streamsets.pipeline.api.ExecutionMode;
import com.streamsets.pipeline.api.GenerateResourceBundle;
import com.streamsets.pipeline.api.ListBeanModel;
import com.streamsets.pipeline.api.RawSource;
import com.streamsets.pipeline.api.Source;
import com.streamsets.pipeline.api.StageDef;
import com.streamsets.pipeline.api.ValueChooserModel;
import com.streamsets.pipeline.config.CharsetChooserValues;
import com.streamsets.pipeline.config.Compression;
import com.streamsets.pipeline.config.CompressionChooserValues;
import com.streamsets.pipeline.config.CsvHeader;
import com.streamsets.pipeline.config.CsvHeaderChooserValues;
import com.streamsets.pipeline.config.CsvMode;
import com.streamsets.pipeline.config.CsvModeChooserValues;
import com.streamsets.pipeline.config.CsvRecordType;
import com.streamsets.pipeline.config.CsvRecordTypeChooserValues;
import com.streamsets.pipeline.config.DataFormat;
import com.streamsets.pipeline.config.FileRawSourcePreviewer;
import com.streamsets.pipeline.config.JsonMode;
import com.streamsets.pipeline.config.JsonModeChooserValues;
import com.streamsets.pipeline.config.LogMode;
import com.streamsets.pipeline.config.LogModeChooserValues;
import com.streamsets.pipeline.config.OnParseError;
import com.streamsets.pipeline.config.OnParseErrorChooserValues;
import com.streamsets.pipeline.config.PostProcessingOptions;
import com.streamsets.pipeline.config.PostProcessingOptionsChooserValues;
import com.streamsets.pipeline.configurablestage.DSource;
import com.streamsets.pipeline.lib.parser.log.RegExConfig;

import java.util.List;

@StageDef(
    version = 4,
    label = "Directory",
    description = "Reads files from a directory",
    icon="directory.png",
    execution = ExecutionMode.STANDALONE,
    recordsByRef = true,
    upgrader = SpoolDirSourceUpgrader.class
)
@RawSource(rawSourcePreviewer = FileRawSourcePreviewer.class)
@ConfigGroups(Groups.class)
@GenerateResourceBundle
public class SpoolDirDSource extends DSource {

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      label = "Data Format",
      description = "Format of data in the files",
      displayPosition = 0,
      group = "FILES"
  )
  @ValueChooserModel(DataFormatChooserValues.class)
  public DataFormat dataFormat;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "UTF-8",
      label = "Files Charset",
      displayPosition = 5,
      group = "FILES",
      dependsOn = "dataFormat",
      triggeredByValue = {"TEXT", "JSON", "DELIMITED", "XML", "LOG"}
  )
  @ValueChooserModel(CharsetChooserValues.class)
  public String charset;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.BOOLEAN,
      defaultValue = "false",
      label = "Ignore Control Characters",
      description = "Use only if required as it impacts reading performance",
      displayPosition = 7,
      group = "FILES"
  )
  public boolean removeCtrlChars;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      label = "Files Directory",
      description = "Use a local directory",
      displayPosition = 10,
      group = "FILES"
  )
  public String spoolDir;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      label = "Buffer Limit (KB)",
      defaultValue = "128",
      description = "Low level reader buffer limit to avoid out of Memory errors",
      displayPosition = 15,
      group = "FILES",
      min = 1,
      max = Integer.MAX_VALUE
  )
  public int overrunLimit;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      label = "Batch Size (recs)",
      defaultValue = "1000",
      description = "Max number of records per batch",
      displayPosition = 20,
      group = "FILES",
      min = 0,
      max = Integer.MAX_VALUE
  )
  public int batchSize;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "600",
      label = "Batch Wait Time (secs)",
      description = "Max time to wait for new files before sending an empty batch",
      displayPosition = 30,
      group = "FILES",
      min = 1
  )
  public long poolingTimeoutSecs;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      label = "File Name Pattern",
      description = "A glob or regular expression that defines the pattern of the file names in the directory. " +
                    "Files are processed in lexicographically increasing order.",
      displayPosition = 40,
      group = "FILES"
  )
  public String filePattern;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "1000",
      label = "Max Files in Directory",
      description = "Max number of files in the directory waiting to be processed. Additional files cause the " +
                    "pipeline to fail.",
      displayPosition = 60,
      group = "FILES",
      dependsOn = "dataFormat",
      triggeredByValue = { "TEXT", "JSON", "XML", "DELIMITED", "LOG"},
      min = 1,
      max = Integer.MAX_VALUE
  )
  public int maxSpoolFiles;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.STRING,
      defaultValue = "",
      label = "First File to Process",
      description = "When configured, the Data Collector does not process earlier (naturally ascending order) file names",
      displayPosition = 50,
      group = "FILES",
      dependsOn = "dataFormat",
      triggeredByValue = { "TEXT", "JSON", "XML", "DELIMITED", "LOG"}
  )
  public String initialFileToProcess;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      description = "Compression formats gzip, bzip2, xz, lzma, Pack200, DEFLATE and Z are supported. " +
      "Archive formats 7z, ar, arj, cpio, dump, tar and zip are supported.",
      defaultValue = "NONE",
      label = "Files Compression",
      displayPosition = 70,
      group = "FILES"
  )
  @ValueChooserModel(CompressionChooserValues.class)
  public Compression fileCompression = Compression.NONE;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.STRING,
    label = "File Name Pattern within Compressed Directory",
    description = "A glob or regular expression that defines the pattern of the file names within the compressed directory",
    defaultValue = "*",
    displayPosition = 80,
    group = "#0",
    dependsOn = "fileCompression",
    triggeredByValue = {"ARCHIVE", "COMPRESSED_ARCHIVE"}
  )
  public String filePatternInArchive = "*";

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.STRING,
      label = "Error Directory",
      description = "Directory for files that could not be fully processed",
      displayPosition = 100,
      group = "POST_PROCESSING"
  )
  public String errorArchiveDir;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "NONE",
      label = "File Post Processing",
      description = "Action to take after processing a file",
      displayPosition = 110,
      group = "POST_PROCESSING"
  )
  @ValueChooserModel(PostProcessingOptionsChooserValues.class)
  public PostProcessingOptions postProcessing;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.STRING,
      label = "Archive Directory",
      description = "Directory to archive files after they have been processed",
      displayPosition = 200,
      group = "POST_PROCESSING",
      dependsOn = "postProcessing",
      triggeredByValue = "ARCHIVE"
  )
  public String archiveDir;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "0",
      label = "Archive Retention Time (mins)",
      description = "How long archived files should be kept before deleting, a value of zero means forever",
      displayPosition = 210,
      group = "POST_PROCESSING",
      dependsOn = "postProcessing",
      triggeredByValue = "ARCHIVE",
      min = 0
  )
  public long retentionTimeMins;

  // CSV Configuration

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "CSV",
      label = "File Type",
      description = "",
      displayPosition = 300,
      group = "DELIMITED",
      dependsOn = "dataFormat",
      triggeredByValue = "DELIMITED"
  )
  @ValueChooserModel(CsvModeChooserValues.class)
  public CsvMode csvFileFormat;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "NO_HEADER",
      label = "Header Line",
      description = "",
      displayPosition = 310,
      group = "DELIMITED",
      dependsOn = "dataFormat",
      triggeredByValue = "DELIMITED"
  )
  @ValueChooserModel(CsvHeaderChooserValues.class)
  public CsvHeader csvHeader;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "1024",
      label = "Max Record Length (chars)",
      description = "Larger objects are not processed",
      displayPosition = 320,
      group = "DELIMITED",
      dependsOn = "dataFormat",
      triggeredByValue = "DELIMITED",
      min = 1,
      max = Integer.MAX_VALUE
  )
  public int csvMaxObjectLen;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.CHARACTER,
      defaultValue = "|",
      label = "Delimiter Character",
      displayPosition = 330,
      group = "DELIMITED",
      dependsOn = "csvFileFormat",
      triggeredByValue = "CUSTOM"
  )
  public char csvCustomDelimiter;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.CHARACTER,
      defaultValue = "\\",
      label = "Escape Character",
      displayPosition = 340,
      group = "DELIMITED",
      dependsOn = "csvFileFormat",
      triggeredByValue = "CUSTOM"
  )
  public char csvCustomEscape;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.CHARACTER,
      defaultValue = "\"",
      label = "Quote Character",
      displayPosition = 350,
      group = "DELIMITED",
      dependsOn = "csvFileFormat",
      triggeredByValue = "CUSTOM"
  )
  public char csvCustomQuote;


  @ConfigDef(
    required = true,
    type = ConfigDef.Type.MODEL,
    defaultValue = "LIST_MAP",
    label = "Record Type",
    description = "",
    displayPosition = 310,
    group = "DELIMITED",
    dependsOn = "dataFormat",
    triggeredByValue = "DELIMITED"
  )
  @ValueChooserModel(CsvRecordTypeChooserValues.class)
  public CsvRecordType csvRecordType;

  // JSON Configuration

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "MULTIPLE_OBJECTS",
      label = "JSON Content",
      description = "",
      displayPosition = 400,
      group = "JSON",
      dependsOn = "dataFormat",
      triggeredByValue = "JSON"
  )
  @ValueChooserModel(JsonModeChooserValues.class)
  public JsonMode jsonContent;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "4096",
      label = "Max Object Length (chars)",
      description = "Larger objects are not processed",
      displayPosition = 410,
      group = "JSON",
      dependsOn = "dataFormat",
      triggeredByValue = "JSON",
      min = 1,
      max = Integer.MAX_VALUE
  )
  public int jsonMaxObjectLen;

  // TEXT Configuration

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "1024",
      label = "Max Line Length",
      description = "Longer lines are truncated",
      displayPosition = 500,
      group = "TEXT",
      dependsOn = "dataFormat",
      triggeredByValue = "TEXT",
      min = 1,
      max = Integer.MAX_VALUE
  )
  public int textMaxObjectLen;

  // XML Configuration

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      defaultValue = "",
      label = "Delimiter Element",
      description = "XML element that acts as a record delimiter. No delimiter will treat the whole XML document as one record.",
      displayPosition = 600,
      group = "XML",
      dependsOn = "dataFormat",
      triggeredByValue = "XML"
  )
  public String xmlRecordElement;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "4096",
      label = "Max Record Length (chars)",
      description = "Larger records are not processed",
      displayPosition = 610,
      group = "XML",
      dependsOn = "dataFormat",
      triggeredByValue = "XML",
      min = 1,
      max = Integer.MAX_VALUE
  )
  public int xmlMaxObjectLen;

  // LOG Configuration

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.MODEL,
    defaultValue = "COMMON_LOG_FORMAT",
    label = "Log Format",
    description = "",
    displayPosition = 700,
    group = "LOG",
    dependsOn = "dataFormat",
    triggeredByValue = "LOG"
  )
  @ValueChooserModel(LogModeChooserValues.class)
  public LogMode logMode;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.NUMBER,
    defaultValue = "1024",
    label = "Max Line Length",
    description = "Longer lines are truncated",
    displayPosition = 710,
    group = "LOG",
    dependsOn = "dataFormat",
    triggeredByValue = "LOG",
    min = 1,
    max = Integer.MAX_VALUE
  )
  public int logMaxObjectLen;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.BOOLEAN,
    defaultValue = "false",
    label = "Retain Original Line",
    description = "Indicates if the original line of log should be retained in the record",
    displayPosition = 720,
    group = "LOG",
    dependsOn = "dataFormat",
    triggeredByValue = "LOG"
  )
  public boolean retainOriginalLine;

  //APACHE_CUSTOM_LOG_FORMAT
  @ConfigDef(
    required = true,
    type = ConfigDef.Type.STRING,
    defaultValue = "%h %l %u %t \"%r\" %>s %b",
    label = "Custom Log Format",
    description = "",
    displayPosition = 730,
    group = "LOG",
    dependsOn = "logMode",
    triggeredByValue = "APACHE_CUSTOM_LOG_FORMAT"
  )
  public String customLogFormat;

  //REGEX

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.STRING,
    defaultValue = "^(\\S+) (\\S+) (\\S+) \\[([\\w:/]+\\s[+\\-]\\d{4})\\] \"(\\S+) (\\S+) (\\S+)\" (\\d{3}) (\\d+)",
    label = "Regular Expression",
    description = "The regular expression which is used to parse the log line.",
    displayPosition = 740,
    group = "LOG",
    dependsOn = "logMode",
    triggeredByValue = "REGEX"
  )
  public String regex;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.MODEL,
    defaultValue = "",
    label = "Field Path To RegEx Group Mapping",
    description = "Map groups in the regular expression to field paths.",
    displayPosition = 750,
    group = "LOG",
    dependsOn = "logMode",
    triggeredByValue = "REGEX"
  )
  @ListBeanModel
  public List<RegExConfig> fieldPathsToGroupName;

  //GROK

  @ConfigDef(
    required = false,
    type = ConfigDef.Type.TEXT,
    defaultValue = "",
    label = "Grok Pattern Definition",
    description = "Define your own grok patterns which will be used to parse the logs",
    displayPosition = 760,
    group = "LOG",
    dependsOn = "logMode",
    triggeredByValue = "GROK",
    mode = ConfigDef.Mode.PLAIN_TEXT
  )
  public String grokPatternDefinition;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.STRING,
    defaultValue = "%{COMMONAPACHELOG}",
    label = "Grok Pattern",
    description = "The grok pattern which is used to parse the log line.",
    displayPosition = 780,
    group = "LOG",
    dependsOn = "logMode",
    triggeredByValue = "GROK"
  )
  public String grokPattern;

  //LOG4J

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.MODEL,
    defaultValue = "ERROR",
    label = "On Parse Error",
    description = "Action to take when a log line cannot be parsed.",
    displayPosition = 790,
    group = "LOG",
    dependsOn = "logMode",
    triggeredByValue = "LOG4J"
  )
  @ValueChooserModel(OnParseErrorChooserValues.class)
  public OnParseError onParseError;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.NUMBER,
    defaultValue = "50",
    label = "Trim Stack Trace to Length",
    description = "Any line that does not match the expected pattern will be treated as a Stack trace and will be " +
      "trimmed to the specified number of lines.",
    displayPosition = 800,
    group = "LOG",
    dependsOn = "onParseError",
    triggeredByValue = "INCLUDE_AS_STACK_TRACE",
    min = 0,
    max = Integer.MAX_VALUE
  )
  public int maxStackTraceLines;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.BOOLEAN,
    defaultValue = "false",
    label = "Use Custom Log Format",
    description = "",
    displayPosition = 810,
    group = "LOG",
    dependsOn = "logMode",
    triggeredByValue = "LOG4J"
  )
  public boolean enableLog4jCustomLogFormat;


  @ConfigDef(
    required = true,
    type = ConfigDef.Type.STRING,
    defaultValue = "%r [%t] %-5p %c %x - %m%n",
    label = "Custom Format",
    description = "Specify your own custom log4j format.",
    displayPosition = 820,
    group = "LOG",
    dependsOn = "enableLog4jCustomLogFormat",
    triggeredByValue = "true"
  )
  public String log4jCustomLogFormat;

  //AVRO

  @ConfigDef(
    required = false,
    type = ConfigDef.Type.TEXT,
    defaultValue = "",
    label = "Avro Schema",
    description = "Overrides the schema associated with the data. Optionally use the runtime:loadResource function to use a schema stored in a file",
    displayPosition = 830,
    group = "AVRO",
    dependsOn = "dataFormat",
    triggeredByValue = "AVRO",
    mode = ConfigDef.Mode.JSON
  )
  public String avroSchema;

  @Override
  protected Source createSource() {
    return new SpoolDirSource(dataFormat, charset, removeCtrlChars, overrunLimit, spoolDir, batchSize,
      poolingTimeoutSecs, filePattern, maxSpoolFiles, initialFileToProcess, fileCompression, filePatternInArchive,
      errorArchiveDir, postProcessing, archiveDir,
      retentionTimeMins, csvFileFormat, csvHeader, csvMaxObjectLen, csvCustomDelimiter, csvCustomEscape, csvCustomQuote,
      jsonContent, jsonMaxObjectLen, textMaxObjectLen, xmlRecordElement, xmlMaxObjectLen, logMode,
      logMaxObjectLen, retainOriginalLine, customLogFormat, regex, fieldPathsToGroupName, grokPatternDefinition,
      grokPattern, enableLog4jCustomLogFormat, log4jCustomLogFormat, onParseError, maxStackTraceLines, avroSchema,
      csvRecordType);
  }

}
