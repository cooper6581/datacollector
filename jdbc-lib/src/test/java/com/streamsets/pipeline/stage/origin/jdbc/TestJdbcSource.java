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
package com.streamsets.pipeline.stage.origin.jdbc;

import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.sdk.SourceRunner;
import com.streamsets.pipeline.sdk.StageRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class TestJdbcSource {
  private static final Logger LOG = LoggerFactory.getLogger(TestJdbcSource.class);
  private final String username = "sa";
  private final String password = "sa";
  private final String database = "test";

  private final String h2ConnectionString = "jdbc:h2:mem:" + database;
  private final String query = "SELECT * FROM TEST.TEST_TABLE WHERE P_ID > ${offset} ORDER BY P_ID ASC LIMIT 10;";
  private final String initialOffset = "0";
  private final long queryInterval = 0L;

  private Connection connection = null;

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws SQLException {
    // Create a table in H2 and put some data in it for querying.
    connection = DriverManager.getConnection(h2ConnectionString, username, password);
    try (Statement statement = connection.createStatement()) {
      // Setup table
      statement.addBatch("CREATE SCHEMA IF NOT EXISTS TEST;");
      statement.addBatch(
          "CREATE TABLE IF NOT EXISTS TEST.TEST_TABLE " +
          "(p_id INT NOT NULL, first_name VARCHAR(255), last_name VARCHAR(255), UNIQUE(p_id));"
      );
      statement.addBatch(
          "CREATE TABLE IF NOT EXISTS TEST.TEST_ARRAY " +
          "(p_id INT NOT NULL, non_scalar ARRAY, UNIQUE(p_id));"
      );
      // Add some data
      statement.addBatch("INSERT INTO TEST.TEST_TABLE VALUES (1, 'Adam', 'Kunicki')");
      statement.addBatch("INSERT INTO TEST.TEST_TABLE VALUES (2, 'Jon', 'Natkins')");
      statement.addBatch("INSERT INTO TEST.TEST_TABLE VALUES (3, 'Jon', 'Daulton')");
      statement.addBatch("INSERT INTO TEST.TEST_TABLE VALUES (4, 'Girish', 'Pancha')");
      statement.addBatch("INSERT INTO TEST.TEST_ARRAY VALUES (1, (1,2,3))");

      statement.executeBatch();
    }
  }

  @After
  public void tearDown() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      // Setup table
      statement.execute("DROP TABLE IF EXISTS TEST.TEST_TABLE;");
      statement.execute("DROP TABLE IF EXISTS TEST.TEST_ARRAY;");
    }

    // Last open connection terminates H2
    connection.close();
  }

  @Test
  public void testIncrementalMode() throws Exception {
    JdbcSource origin = new JdbcSource(
        true,
        h2ConnectionString,
        query,
        initialOffset,
        "P_ID",
        queryInterval,
        username,
        password,
        new HashMap<String, String>(),
        "",
        "",
        "",
        1000,
        JdbcRecordType.LIST_MAP);
    SourceRunner runner = new SourceRunner.Builder(JdbcDSource.class, origin)
        .addOutputLane("lane")
        .build();

    runner.runInit();

    try {
      // Check that existing rows are loaded.
      StageRunner.Output output = runner.runProduce(null, 2);
      Map<String, List<Record>> recordMap = output.getRecords();
      List<Record> parsedRecords = recordMap.get("lane");

      assertEquals(2, parsedRecords.size());

      assertEquals("2", output.getNewOffset());

      // Check that the remaining rows in the initial cursor are read.
      output = runner.runProduce(output.getNewOffset(), 100);
      parsedRecords = output.getRecords().get("lane");
      assertEquals(2, parsedRecords.size());


      // Check that new rows are loaded.
      runInsertNewRows();
      output = runner.runProduce(output.getNewOffset(), 100);
      parsedRecords = output.getRecords().get("lane");
      assertEquals(2, parsedRecords.size());

      assertEquals("10", output.getNewOffset());

      // Check that older rows are not loaded.
      runInsertOldRows();
      output = runner.runProduce(output.getNewOffset(), 100);
      parsedRecords = output.getRecords().get("lane");
      assertEquals(0, parsedRecords.size());
    } finally {
      runner.runDestroy();
    }
  }

  @Test
  public void testNonIncrementalMode() throws Exception {
    JdbcSource origin = new JdbcSource(
        false,
        h2ConnectionString,
        query,
        initialOffset,
        "P_ID",
        queryInterval,
        username,
        password,
        new HashMap<String, String>(),
        "",
        "",
        "",
        1000,
        JdbcRecordType.LIST_MAP);
    SourceRunner runner = new SourceRunner.Builder(JdbcDSource.class, origin)
        .addOutputLane("lane")
        .build();

    runner.runInit();

    try {
      // Check that existing rows are loaded.
      StageRunner.Output output = runner.runProduce(null, 2);
      Map<String, List<Record>> recordMap = output.getRecords();
      List<Record> parsedRecords = recordMap.get("lane");

      assertEquals(2, parsedRecords.size());

      assertEquals(initialOffset, output.getNewOffset());

      // Check that the remaining rows in the initial cursor are read.
      output = runner.runProduce(output.getNewOffset(), 100);
      parsedRecords = output.getRecords().get("lane");
      assertEquals(2, parsedRecords.size());


      // Check that new rows are loaded.
      runInsertNewRows();
      output = runner.runProduce(output.getNewOffset(), 100);
      parsedRecords = output.getRecords().get("lane");
      assertEquals(6, parsedRecords.size());

      assertEquals(initialOffset, output.getNewOffset());

      // Check that older rows are loaded.
      runInsertOldRows();
      output = runner.runProduce(output.getNewOffset(), 100);
      parsedRecords = output.getRecords().get("lane");
      assertEquals(8, parsedRecords.size());
    } finally {
      runner.runDestroy();
    }
  }

  private void runInsertNewRows() throws SQLException {
    try (Connection connection = DriverManager.getConnection(h2ConnectionString, username, password)){
      try (Statement statement = connection.createStatement()) {
        // Add some data
        statement.addBatch("INSERT INTO TEST.TEST_TABLE VALUES (9, 'Arvind', 'Prabhakar')");
        statement.addBatch("INSERT INTO TEST.TEST_TABLE VALUES (10, 'Brock', 'Noland')");

        statement.executeBatch();
      }
    }
  }

  @Test
  public void testBadConnectionString() throws Exception {
    JdbcSource origin = new JdbcSource(
        true,
        "some bad connection string",
        query,
        initialOffset,
        "P_ID",
        queryInterval,
        username,
        password,
        new HashMap<String, String>(),
        "",
        "",
        "",
        1000,
        JdbcRecordType.LIST_MAP);

    SourceRunner runner = new SourceRunner.Builder(JdbcDSource.class, origin)
        .addOutputLane("lane")
        .build();

    List<Stage.ConfigIssue> issues = runner.runValidateConfigs();
    assertEquals(1, issues.size());
  }

  @Test
  public void testMissingWhereClause() throws Exception {
    String queryMissingWhere = "SELECT * FROM TEST.TEST_TABLE ORDER BY P_ID ASC LIMIT 10;";
    JdbcSource origin = new JdbcSource(
        true,
        h2ConnectionString,
        queryMissingWhere,
        initialOffset,
        "P_ID",
        queryInterval,
        username,
        password,
        new HashMap<String, String>(),
        "",
        "",
        "",
        1000,
        JdbcRecordType.LIST_MAP);

    SourceRunner runner = new SourceRunner.Builder(JdbcDSource.class, origin)
        .addOutputLane("lane")
        .build();

    List<Stage.ConfigIssue> issues = runner.runValidateConfigs();
    assertEquals(1, issues.size());
  }

  @Test
  public void testMissingOrderByClause() throws Exception {
    String queryMissingOrderBy = "SELECT * FROM TEST.TEST_TABLE WHERE P_ID > ${offset} LIMIT 10;";
    JdbcSource origin = new JdbcSource(
        true,
        h2ConnectionString,
        queryMissingOrderBy,
        initialOffset,
        "P_ID",
        queryInterval,
        username,
        password,
        new HashMap<String, String>(),
        "",
        "",
        "",
        1000,
        JdbcRecordType.LIST_MAP);

    SourceRunner runner = new SourceRunner.Builder(JdbcDSource.class, origin)
        .addOutputLane("lane")
        .build();

    List<Stage.ConfigIssue> issues = runner.runValidateConfigs();
    for (Stage.ConfigIssue issue : issues) {
      LOG.info(issue.toString());
    }
    assertEquals(1, issues.size());
  }

  @Test
  public void testMissingWhereAndOrderByClause() throws Exception {
    String queryMissingWhereAndOrderBy = "SELECT * FROM TEST.TEST_TABLE;";
    JdbcSource origin = new JdbcSource(
        true,
        h2ConnectionString,
        queryMissingWhereAndOrderBy,
        initialOffset,
        "P_ID",
        queryInterval,
        username,
        password,
        new HashMap<String, String>(),
        "",
        "",
        "",
        1000,
        JdbcRecordType.LIST_MAP);

    SourceRunner runner = new SourceRunner.Builder(JdbcDSource.class, origin)
        .addOutputLane("lane")
        .build();

    List<Stage.ConfigIssue> issues = runner.runValidateConfigs();
    assertEquals(1, issues.size());
  }

  @Test
  public void testInvalidQuery() throws Exception {
    String queryInvalid = "SELET * FORM TABLE WHERE P_ID > ${offset} ORDER BY P_ID LIMIT 10;";
    JdbcSource origin = new JdbcSource(
        true,
        h2ConnectionString,
        queryInvalid,
        initialOffset,
        "P_ID",
        queryInterval,
        username,
        password,
        new HashMap<String, String>(),
        "",
        "",
        "",
        1000,
        JdbcRecordType.LIST_MAP);

    SourceRunner runner = new SourceRunner.Builder(JdbcDSource.class, origin)
        .addOutputLane("lane")
        .build();

    List<Stage.ConfigIssue> issues = runner.runValidateConfigs();
    assertEquals(1, issues.size());
  }

  @Test
  public void testMultiLineQuery() throws Exception {
    String queryInvalid = "SELECT * FROM TEST.TEST_TABLE WHERE\nP_ID > ${offset}\nORDER BY P_ID ASC LIMIT 10;";
    JdbcSource origin = new JdbcSource(
        true,
        h2ConnectionString,
        queryInvalid,
        initialOffset,
        "P_ID",
        queryInterval,
        username,
        password,
        new HashMap<String, String>(),
        "",
        "",
        "",
        1000,
        JdbcRecordType.LIST_MAP);

    SourceRunner runner = new SourceRunner.Builder(JdbcDSource.class, origin)
        .addOutputLane("lane")
        .build();

    List<Stage.ConfigIssue> issues = runner.runValidateConfigs();
    assertEquals(0, issues.size());
  }

  private void runInsertOldRows() throws SQLException {
    try (Connection connection = DriverManager.getConnection(h2ConnectionString, username, password)){
      try (Statement statement = connection.createStatement()) {
        // Add some data
        statement.addBatch("INSERT INTO TEST.TEST_TABLE VALUES (5, 'Arvind', 'Prabhakar')");
        statement.addBatch("INSERT INTO TEST.TEST_TABLE VALUES (6, 'Brock', 'Noland')");

        statement.executeBatch();
      }
    }
  }

  @Test
  public void testCdcMode() throws Exception {
    JdbcSource origin = new JdbcSource(
        true,
        h2ConnectionString,
        query,
        "1",
        "P_ID",
        queryInterval,
        username,
        password,
        new HashMap<String, String>(),
        "",
        "",
        "FIRST_NAME",
        1000,
        JdbcRecordType.MAP);
    SourceRunner runner = new SourceRunner.Builder(JdbcDSource.class, origin)
        .addOutputLane("lane")
        .build();

    runner.runInit();

    try {
      // Check that existing rows are loaded.
      StageRunner.Output output = runner.runProduce(null, 1000);
      Map<String, List<Record>> recordMap = output.getRecords();
      List<Record> parsedRecords = recordMap.get("lane");

      assertEquals(2, parsedRecords.size());

      assertEquals("3", output.getNewOffset());

      // Check that the next 'transaction' of 1 row is read.
      output = runner.runProduce(output.getNewOffset(), 1000);
      parsedRecords = output.getRecords().get("lane");
      assertEquals(1, parsedRecords.size());

    } finally {
      runner.runDestroy();
    }
  }

  @Test
  public void testCdcSplitTransactionMode() throws Exception {
    JdbcSource origin = new JdbcSource(
        true,
        h2ConnectionString,
        query,
        "1",
        "P_ID",
        queryInterval,
        username,
        password,
        new HashMap<String, String>(),
        "",
        "",
        "FIRST_NAME",
        1,
        JdbcRecordType.LIST);
    SourceRunner runner = new SourceRunner.Builder(JdbcDSource.class, origin)
        .addOutputLane("lane")
        .build();

    runner.runInit();

    try {
      // Check that existing rows are loaded.
      StageRunner.Output output = runner.runProduce(null, 1000);
      Map<String, List<Record>> recordMap = output.getRecords();
      List<Record> parsedRecords = recordMap.get("lane");

      assertEquals(1, parsedRecords.size());
      assertEquals("2", output.getNewOffset());

      // Check that the next 'transaction' of 1 row is read.
      output = runner.runProduce(output.getNewOffset(), 1000);
      parsedRecords = output.getRecords().get("lane");

      assertEquals(1, parsedRecords.size());
      assertEquals("3", output.getNewOffset());

    } finally {
      runner.runDestroy();
    }
  }
}
