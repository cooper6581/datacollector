/**
 * (c) 2015 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.dc.execution.store;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableList;
import com.streamsets.dc.execution.PipelineState;
import com.streamsets.dc.execution.PipelineStateStore;
import com.streamsets.dc.execution.PipelineStatus;
import com.streamsets.pipeline.api.ExecutionMode;
import com.streamsets.pipeline.config.PipelineConfiguration;
import com.streamsets.pipeline.main.RuntimeInfo;
import com.streamsets.pipeline.main.RuntimeModule;
import com.streamsets.pipeline.runner.MockStages;
import com.streamsets.pipeline.store.PipelineStoreException;
import com.streamsets.pipeline.store.PipelineStoreTask;
import com.streamsets.pipeline.store.impl.FilePipelineStoreTask;
import com.streamsets.pipeline.util.Configuration;
import com.streamsets.pipeline.util.LockCacheModule;
import com.streamsets.pipeline.util.TestUtil;

import dagger.Module;
import dagger.ObjectGraph;
import dagger.Provides;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class TestPipelineStateStore {

  private static PipelineStateStore pipelineStateStore;
  private static PipelineStoreTask pipelineStoreTask;

  static class MockFilePipelineStateStore extends CachePipelineStateStore {

    @Inject
    public MockFilePipelineStateStore(PipelineStateStore pipelineStateStore) {
      super(pipelineStateStore);
    }

    static boolean INVALIDATE_CACHE = false;

    @Override
    public PipelineState edited(String user, String name, String rev, ExecutionMode executionMode) throws PipelineStoreException {
      PipelineState state = super.edited(user, name, rev, executionMode);
      if (INVALIDATE_CACHE) {
        // invalidate cache
        super.destroy();
      }
      return state;
    }

    @Override
    public PipelineState saveState(String user, String name, String rev, PipelineStatus status, String message,
                                   Map<String, Object> attributes, ExecutionMode executionMode) throws PipelineStoreException {
      if (INVALIDATE_CACHE) {
        super.destroy();
      }
      return super.saveState(user, name, rev, status, message, attributes, executionMode);
    }

  }

  @Module(injects = {PipelineStateStore.class, PipelineStoreTask.class}, library = true,
    includes = {TestUtil.TestStageLibraryModule.class, LockCacheModule.class})
  static class TestPipelineStateStoreModule {

    @Provides @Singleton
    public RuntimeInfo provideRuntimeInfo() {
      return new RuntimeInfo(RuntimeModule.SDC_PROPERTY_PREFIX, new MetricRegistry(),
        ImmutableList.of(getClass().getClassLoader()));
    }

    @Provides @Singleton
    public Configuration provideConfiguration() {
      return new Configuration();
    }

    @Provides @Singleton
    public PipelineStateStore providePipelineStateStore(RuntimeInfo runtimeInfo, Configuration configuration) {
      return new MockFilePipelineStateStore(new FilePipelineStateStore(runtimeInfo, configuration));
    }

    @Provides @Singleton
    public PipelineStoreTask providePipelineStore(FilePipelineStoreTask pipelineStoreTask) {
      return pipelineStoreTask;
    }
  }

  @BeforeClass
  public static void beforeClass() throws IOException {
    System.setProperty(RuntimeModule.SDC_PROPERTY_PREFIX + RuntimeInfo.DATA_DIR, "./target/var");
    TestUtil.captureMockStages();
  }

  @AfterClass
  public static void afterClass() throws IOException {
    System.getProperties().remove(RuntimeModule.SDC_PROPERTY_PREFIX + RuntimeInfo.DATA_DIR);
  }

  @Before()
  public void setUp() throws IOException {
    File f = new File(System.getProperty(RuntimeModule.SDC_PROPERTY_PREFIX + RuntimeInfo.DATA_DIR));
    FileUtils.deleteDirectory(f);
    ObjectGraph objectGraph = ObjectGraph.create(TestPipelineStateStoreModule.class);
    pipelineStateStore = objectGraph.get(PipelineStateStore.class);
    pipelineStoreTask = objectGraph.get(PipelineStoreTask.class);
    pipelineStoreTask.init();
  }

  private PipelineConfiguration createPipeline(UUID uuid) {
    PipelineConfiguration pc = MockStages.createPipelineConfigurationWithClusterOnlyStage(ExecutionMode.CLUSTER);
    pc.setUuid(uuid);
    return pc;
  }

  @After
  public void tearDown() {
    pipelineStoreTask.stop();
  }

  @Test
  public void testCreatePipeline() throws Exception {
    pipelineStoreTask.create("user2", "name1", "description");
    PipelineState pipelineState = pipelineStateStore.getState("name1", "0");

    assertEquals("user2", pipelineState.getUser());
    assertEquals("name1", pipelineState.getName());
    assertEquals("0", pipelineState.getRev());
    assertEquals(ExecutionMode.STANDALONE, pipelineState.getExecutionMode());

    PipelineConfiguration pc0 = pipelineStoreTask.load("name1", "0");
    pc0 = createPipeline(pc0.getUuid());
    pipelineStoreTask.save("user3", "name1", "0", "execution mdoe changed", pc0);
    pipelineState = pipelineStateStore.getState("name1", "0");
    assertEquals("user3", pipelineState.getUser());
    assertEquals("name1", pipelineState.getName());
    assertEquals("0", pipelineState.getRev());
    assertEquals(ExecutionMode.CLUSTER, pipelineState.getExecutionMode());

    pc0 = pipelineStoreTask.load("name1", "0");
    pc0 = createPipeline(pc0.getUuid());
    pipelineStoreTask.save("user4", "name1", "0", "execution mdoe same", pc0);
    pipelineState = pipelineStateStore.getState("name1", "0");
    // should still be user3 as we dont persist state file on each edit (unless the execution mode has changed)
    assertEquals("user3", pipelineState.getUser());
  }

  @Test
  public void testStateSaveNoCache() throws Exception {
    MockFilePipelineStateStore.INVALIDATE_CACHE = true;
    stateSave();
  }

  @Test
  public void testStateSaveCache() throws Exception {
    MockFilePipelineStateStore.INVALIDATE_CACHE = false;
    stateSave();
  }

  @Test
  public void testStateEditNoCache() throws Exception {
    MockFilePipelineStateStore.INVALIDATE_CACHE = true;
    stateEdit();
  }

  @Test
  public void testStateEditCache() throws Exception {
    MockFilePipelineStateStore.INVALIDATE_CACHE = false;
    stateEdit();
  }

  @Test
  public void testStateDeleteNoCache() throws Exception {
    MockFilePipelineStateStore.INVALIDATE_CACHE = true;
    stateDelete();
  }

  @Test
  public void testStateDeleteCache() throws Exception {
    MockFilePipelineStateStore.INVALIDATE_CACHE = false;
    stateDelete();
  }

  @Test
  public void stateHistory() throws Exception {
    pipelineStateStore.saveState("user1", "aaa", "0", PipelineStatus.STOPPED, "Pipeline stopped", null, ExecutionMode.STANDALONE);
    pipelineStateStore.saveState("user1", "aaa", "0", PipelineStatus.RUNNING, "Pipeline stopped", null, ExecutionMode.STANDALONE);
    List<PipelineState> history = pipelineStateStore.getHistory("aaa", "0", true);
    for (PipelineState pipelineState: history) {
      assertEquals(PipelineStatus.RUNNING, pipelineState.getStatus());
      assertEquals(PipelineStatus.STOPPED, pipelineState.getStatus());
    }
  }

  @Test
  public void stateChangeExecutionMode() throws Exception {
    pipelineStateStore.saveState("user1", "aaa", "0", PipelineStatus.STOPPED, "Pipeline stopped", null, ExecutionMode.CLUSTER);
    PipelineState pipelineState = pipelineStateStore.getState("aaa", "0");
    assertEquals(ExecutionMode.CLUSTER, pipelineState.getExecutionMode());
    pipelineStateStore.saveState("user1", "aaa", "0", PipelineStatus.STOPPED, "Pipeline stopped", null, ExecutionMode.STANDALONE);
    pipelineState = pipelineStateStore.getState("aaa", "0");
    assertEquals(ExecutionMode.STANDALONE, pipelineState.getExecutionMode());
  }

  public void stateSave() throws Exception {
    pipelineStateStore.saveState("user1", "aaa", "0", PipelineStatus.EDITED, "Pipeline edited", null, ExecutionMode.STANDALONE);
    PipelineState pipelineState = pipelineStateStore.getState("aaa", "0");
    assertEquals("user1", pipelineState.getUser());
    assertEquals("aaa", pipelineState.getName());
    assertEquals("0", pipelineState.getRev());
    assertEquals(PipelineStatus.EDITED, pipelineState.getStatus());
    assertEquals("Pipeline edited", pipelineState.getMessage());
    assertEquals(ExecutionMode.STANDALONE, pipelineState.getExecutionMode());
  }

  public void stateDelete() throws Exception {
    pipelineStateStore.saveState("user1", "aaa", "0", PipelineStatus.STOPPED, "Pipeline stopped", null, ExecutionMode.STANDALONE);
    pipelineStateStore.delete("aaa", "0");
    try {
      pipelineStateStore.getState("aaa", "0");
      fail("Expected exception but didn't get any");
    } catch (PipelineStoreException ex) {
      // expected
    }
  }

  public void stateEdit() throws Exception {
    pipelineStateStore.saveState("user1", "aaa", "0", PipelineStatus.STOPPED, "Pipeline stopped", null, ExecutionMode.STANDALONE);
    pipelineStateStore.edited("user2", "aaa", "0", ExecutionMode.STANDALONE);
    PipelineState pipelineState = pipelineStateStore.getState("aaa", "0");
    assertEquals("user2", pipelineState.getUser());
    assertEquals("aaa", pipelineState.getName());
    assertEquals("0", pipelineState.getRev());
    assertEquals(PipelineStatus.EDITED, pipelineState.getStatus());

    pipelineStateStore.saveState("user1", "aaa", "0", PipelineStatus.RUNNING, "Pipeline running", null, ExecutionMode.STANDALONE);
    try {
      pipelineStateStore.edited("user2", "aaa", "0", ExecutionMode.STANDALONE);
      fail("Expected exception but didn't get any");
    } catch (IllegalStateException ex) {
      // expected
    }
  }

}