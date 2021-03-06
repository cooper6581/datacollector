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
package com.streamsets.datacollector.runner;

import com.google.common.collect.ImmutableList;
import com.streamsets.datacollector.config.PipelineConfiguration;
import com.streamsets.datacollector.creation.PipelineBean;
import com.streamsets.datacollector.creation.PipelineBeanCreator;
import com.streamsets.datacollector.creation.StageBean;
import com.streamsets.datacollector.record.RecordImpl;
import com.streamsets.datacollector.runner.BatchImpl;
import com.streamsets.datacollector.runner.BatchMakerImpl;
import com.streamsets.datacollector.runner.FullPipeBatch;
import com.streamsets.datacollector.runner.LaneResolver;
import com.streamsets.datacollector.runner.PipeBatch;
import com.streamsets.datacollector.runner.SourceOffsetTracker;
import com.streamsets.datacollector.runner.StageContext;
import com.streamsets.datacollector.runner.StageOutput;
import com.streamsets.datacollector.runner.StagePipe;
import com.streamsets.datacollector.runner.StageRuntime;
import com.streamsets.datacollector.stagelibrary.StageLibraryTask;
import com.streamsets.datacollector.validation.Issue;
import com.streamsets.pipeline.api.Batch;
import com.streamsets.pipeline.api.Record;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class TestPipeBatch {

  private PipelineBean getPipelineBean() {
    List<Issue> errors = new ArrayList<>();
    StageLibraryTask library = MockStages.createStageLibrary();
    PipelineConfiguration pipelineConf = MockStages.createPipelineConfigurationSourceTarget();
    PipelineBean pipelineBean = PipelineBeanCreator.get().create(false, library, pipelineConf, errors);
    if (pipelineBean == null) {
      Assert.fail(errors.toString());
    }
    return pipelineBean;
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSourceOffsetTracker() throws Exception {
    SourceOffsetTracker tracker = Mockito.mock(SourceOffsetTracker.class);
    Mockito.when(tracker.getOffset()).thenReturn("foo");
    PipeBatch pipeBatch = new FullPipeBatch(tracker, -1, false);
    Assert.assertEquals("foo", pipeBatch.getPreviousOffset());
    Mockito.verify(tracker, Mockito.times(1)).getOffset();
    Mockito.verifyNoMoreInteractions(tracker);
    pipeBatch.setNewOffset("bar");
    Mockito.verify(tracker, Mockito.times(1)).setOffset(Mockito.eq("bar"));
    Mockito.verifyNoMoreInteractions(tracker);

    pipeBatch.commitOffset();
    Mockito.verify(tracker, Mockito.times(1)).commitOffset();
    Mockito.verifyNoMoreInteractions(tracker);

    PipelineBean pipelineBean = getPipelineBean();
    StageBean stageBean = pipelineBean.getStages().get(0);

    StageRuntime stage = new StageRuntime(pipelineBean, stageBean);

    StagePipe pipe = new StagePipe(stage, Collections.EMPTY_LIST,
      LaneResolver.getPostFixed(stage.getConfiguration().getOutputLanes(),
                                LaneResolver.STAGE_OUT));

    Batch batch = pipeBatch.getBatch(pipe);
    Assert.assertEquals("foo", batch.getSourceOffset());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testStageMethodsNoSnapshot() throws Exception {
    SourceOffsetTracker tracker = Mockito.mock(SourceOffsetTracker.class);
    PipeBatch pipeBatch = new FullPipeBatch(tracker, -1, false);

    PipelineBean pipelineBean = getPipelineBean();
    StageRuntime[] stages = {
        new StageRuntime(pipelineBean, pipelineBean.getStages().get(0)),
        new StageRuntime(pipelineBean, pipelineBean.getStages().get(1))};

    StageContext context = Mockito.mock(StageContext.class);
    Mockito.when(context.isPreview()).thenReturn(false);
    stages[0].setContext(context);
    stages[1].setContext(context);

    List<String> stageOutputLanes = stages[0].getConfiguration().getOutputLanes();
    StagePipe pipe = new StagePipe(stages[0], Collections.EMPTY_LIST,
      LaneResolver.getPostFixed(stageOutputLanes, LaneResolver.STAGE_OUT));

    // starting source
    BatchMakerImpl batchMaker = pipeBatch.startStage(pipe);
    Assert.assertEquals(new ArrayList<String>(stageOutputLanes), batchMaker.getLanes());

    Record origRecord = new RecordImpl("i", "source", null, null);
    origRecord.getHeader().setAttribute("a", "A");
    batchMaker.addRecord(origRecord, stageOutputLanes.get(0));

    // completing source
    pipeBatch.completeStage(batchMaker);

    pipe = new StagePipe(stages[1], LaneResolver.getPostFixed(stageOutputLanes, LaneResolver.STAGE_OUT),
      Collections.EMPTY_LIST);

    // starting target
    batchMaker = pipeBatch.startStage(pipe);

    BatchImpl batch = pipeBatch.getBatch(pipe);

    // completing target
    pipeBatch.completeStage(batchMaker);

    Iterator<Record> records = batch.getRecords();
    Record recordFromBatch = records.next();

    Assert.assertNotSame(origRecord, recordFromBatch);
    Assert.assertEquals(origRecord.getHeader().getAttributeNames(), recordFromBatch.getHeader().getAttributeNames());
    Assert.assertEquals(origRecord.getHeader().getStageCreator(), recordFromBatch.getHeader().getStageCreator());
    Assert.assertEquals(origRecord.getHeader().getSourceId(), recordFromBatch.getHeader().getSourceId());
    Assert.assertEquals("s", recordFromBatch.getHeader().getStagesPath());
    Assert.assertNotEquals(origRecord.getHeader().getStagesPath(), recordFromBatch.getHeader().getStagesPath());
    Assert.assertNotEquals(origRecord.getHeader().getTrackingId(), recordFromBatch.getHeader().getTrackingId());

    Assert.assertFalse(records.hasNext());
    Assert.assertNull(pipeBatch.getSnapshotsOfAllStagesOutput());

    try {
      pipeBatch.startStage(pipe);
      Assert.fail();
    } catch (IllegalStateException ex) {
      //expected
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testStageMethodsWithSnapshot() throws Exception {
    SourceOffsetTracker tracker = Mockito.mock(SourceOffsetTracker.class);
    PipeBatch pipeBatch = new FullPipeBatch(tracker, -1, true);

    PipelineBean pipelineBean = getPipelineBean();
    StageRuntime[] stages = {
        new StageRuntime(pipelineBean, pipelineBean.getStages().get(0)),
        new StageRuntime(pipelineBean, pipelineBean.getStages().get(1))};

    StageContext context = Mockito.mock(StageContext.class);
    Mockito.when(context.isPreview()).thenReturn(false);
    stages[0].setContext(context);
    stages[1].setContext(context);

    List<String> stageOutputLanes = stages[0].getConfiguration().getOutputLanes();
    StagePipe sourcePipe = new StagePipe(stages[0], Collections.EMPTY_LIST,
      LaneResolver.getPostFixed(stageOutputLanes, LaneResolver.STAGE_OUT));

    // starting source
    BatchMakerImpl batchMaker = pipeBatch.startStage(sourcePipe);
    Assert.assertEquals(new ArrayList<String>(stageOutputLanes), batchMaker.getLanes());

    Record origRecord = new RecordImpl("i", "source", null, null);
    origRecord.getHeader().setAttribute("a", "A");
    batchMaker.addRecord(origRecord, stageOutputLanes.get(0));

    // completing source
    pipeBatch.completeStage(batchMaker);

    StagePipe targetPipe = new StagePipe(stages[1], LaneResolver.getPostFixed(stageOutputLanes, LaneResolver.STAGE_OUT),
      Collections.EMPTY_LIST);

    // starting target
    batchMaker = pipeBatch.startStage(targetPipe);

    BatchImpl batch = pipeBatch.getBatch(targetPipe);

    // completing target
    pipeBatch.completeStage(batchMaker);

    Iterator<Record> records = batch.getRecords();
    Record recordFromBatch = records.next();

    Assert.assertNotSame(origRecord, recordFromBatch);
    Assert.assertEquals(origRecord.getHeader().getAttributeNames(), recordFromBatch.getHeader().getAttributeNames());
    Assert.assertEquals(origRecord.getHeader().getStageCreator(), recordFromBatch.getHeader().getStageCreator());
    Assert.assertEquals(origRecord.getHeader().getSourceId(), recordFromBatch.getHeader().getSourceId());
    Assert.assertEquals("s", recordFromBatch.getHeader().getStagesPath());
    Assert.assertNotEquals(origRecord.getHeader().getStagesPath(), recordFromBatch.getHeader().getStagesPath());
    Assert.assertNotEquals(origRecord.getHeader().getTrackingId(), recordFromBatch.getHeader().getTrackingId());

    Assert.assertEquals(origRecord.get(), recordFromBatch.get());
    Assert.assertEquals(origRecord.get(), recordFromBatch.get());
    Assert.assertEquals(origRecord.get(), recordFromBatch.get());
    Assert.assertEquals(origRecord.get(), recordFromBatch.get());

    Assert.assertTrue(recordFromBatch.getHeader().getStagesPath().
        endsWith(sourcePipe.getStage().getInfo().getInstanceName()));

    Assert.assertFalse(records.hasNext());

    List<StageOutput> stageOutputs = pipeBatch.getSnapshotsOfAllStagesOutput();
    Assert.assertNotNull(stageOutputs);
    Assert.assertEquals(2, stageOutputs.size());
    Assert.assertEquals("s", stageOutputs.get(0).getInstanceName());
    Assert.assertEquals(1, stageOutputs.get(0).getOutput().size());
    Record recordFromSnapshot = stageOutputs.get(0).getOutput().get(stageOutputLanes.get(0)).get(0);

    Assert.assertNotSame(origRecord, recordFromBatch);
    Assert.assertNotSame(origRecord, recordFromBatch);
    Assert.assertEquals(origRecord.getHeader().getAttributeNames(), recordFromBatch.getHeader().getAttributeNames());
    Assert.assertEquals(origRecord.getHeader().getStageCreator(), recordFromBatch.getHeader().getStageCreator());
    Assert.assertEquals(origRecord.getHeader().getSourceId(), recordFromBatch.getHeader().getSourceId());
    Assert.assertEquals("s", recordFromBatch.getHeader().getStagesPath());
    Assert.assertNotEquals(origRecord.getHeader().getStagesPath(), recordFromBatch.getHeader().getStagesPath());
    Assert.assertNotEquals(origRecord.getHeader().getTrackingId(), recordFromBatch.getHeader().getTrackingId());

    Assert.assertNotSame(origRecord, recordFromSnapshot);
    Assert.assertEquals(origRecord.getHeader().getAttributeNames(), recordFromSnapshot.getHeader().getAttributeNames());
    Assert.assertEquals(origRecord.getHeader().getStageCreator(), recordFromSnapshot.getHeader().getStageCreator());
    Assert.assertEquals(origRecord.getHeader().getSourceId(), recordFromSnapshot.getHeader().getSourceId());
    Assert.assertEquals("s", recordFromSnapshot.getHeader().getStagesPath());
    Assert.assertNotEquals(origRecord.getHeader().getStagesPath(), recordFromSnapshot.getHeader().getStagesPath());
    Assert.assertNotEquals(origRecord.getHeader().getTrackingId(), recordFromSnapshot.getHeader().getTrackingId());

    Assert.assertEquals(recordFromBatch, recordFromSnapshot);
    Assert.assertNotSame(recordFromBatch, recordFromSnapshot);

    Assert.assertEquals("t", stageOutputs.get(1).getInstanceName());
    Assert.assertEquals(0, stageOutputs.get(1).getOutput().size());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testMoveLane() throws Exception {
    SourceOffsetTracker tracker = Mockito.mock(SourceOffsetTracker.class);
    FullPipeBatch pipeBatch = new FullPipeBatch(tracker, -1, true);

    PipelineBean pipelineBean = getPipelineBean();
    StageRuntime[] stages = {
        new StageRuntime(pipelineBean, pipelineBean.getStages().get(0)),
        new StageRuntime(pipelineBean, pipelineBean.getStages().get(1))};

    StageContext context = Mockito.mock(StageContext.class);
    Mockito.when(context.isPreview()).thenReturn(false);
    stages[0].setContext(context);

    List<String> stageOutputLanes = stages[0].getConfiguration().getOutputLanes();
    StagePipe pipe = new StagePipe(stages[0], Collections.EMPTY_LIST,
      LaneResolver.getPostFixed(stageOutputLanes, LaneResolver.STAGE_OUT));

    // starting source
    BatchMakerImpl batchMaker = pipeBatch.startStage(pipe);
    Assert.assertEquals(new ArrayList<String>(stageOutputLanes), batchMaker.getLanes());

    Record record = new RecordImpl("i", "source", null, null);
    record.getHeader().setAttribute("a", "A");
    batchMaker.addRecord(record, stageOutputLanes.get(0));

    // completing source
    pipeBatch.completeStage(batchMaker);

    Record origRecord = pipeBatch.getFullPayload().get(pipe.getOutputLanes().get(0)).get(0);
    pipeBatch.moveLane(pipe.getOutputLanes().get(0), "x");
    Record movedRecord = pipeBatch.getFullPayload().get("x").get(0);
    Assert.assertSame(origRecord, movedRecord);

    Map<String, List<Record>> snapshot = pipeBatch.getLaneOutputRecords(ImmutableList.of("x"));
    Assert.assertEquals(1, snapshot.size());
    Assert.assertEquals(1, snapshot.get("x").size());
    Assert.assertEquals("A", snapshot.get("x").get(0).getHeader().getAttribute("a"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testMoveLaneCopying() throws Exception {
    SourceOffsetTracker tracker = Mockito.mock(SourceOffsetTracker.class);
    FullPipeBatch pipeBatch = new FullPipeBatch(tracker, -1, true);

    PipelineBean pipelineBean = getPipelineBean();
    StageRuntime[] stages = {
        new StageRuntime(pipelineBean, pipelineBean.getStages().get(0)),
        new StageRuntime(pipelineBean, pipelineBean.getStages().get(1))};

    StageContext context = Mockito.mock(StageContext.class);
    Mockito.when(context.isPreview()).thenReturn(false);
    stages[0].setContext(context);

    List<String> stageOutputLanes = stages[0].getConfiguration().getOutputLanes();
    StagePipe pipe = new StagePipe(stages[0], Collections.EMPTY_LIST,
      LaneResolver.getPostFixed(stageOutputLanes, LaneResolver.STAGE_OUT));

    // starting source
    BatchMakerImpl batchMaker = pipeBatch.startStage(pipe);
    Assert.assertEquals(new ArrayList<String>(stageOutputLanes), batchMaker.getLanes());

    Record record = new RecordImpl("i", "source", null, null);
    record.getHeader().setAttribute("a", "A");
    batchMaker.addRecord(record, stageOutputLanes.get(0));

    // completing source
    pipeBatch.completeStage(batchMaker);

    List<String> list = ImmutableList.of("x", "y");


    Record origRecord = pipeBatch.getFullPayload().get(pipe.getOutputLanes().get(0)).get(0);
    pipeBatch.moveLaneCopying(pipe.getOutputLanes().get(0), list);
    Record copiedRecordX = pipeBatch.getFullPayload().get("x").get(0);
    Record copiedRecordY = pipeBatch.getFullPayload().get("y").get(0);

    Assert.assertEquals(origRecord, copiedRecordX);
    Assert.assertNotSame(origRecord, copiedRecordX);

    Assert.assertEquals(origRecord, copiedRecordY);
    Assert.assertNotSame(origRecord, copiedRecordY);


    Map<String, List<Record>> snapshot = pipeBatch.getLaneOutputRecords(list);
    Assert.assertEquals(2, snapshot.size());
    Assert.assertEquals(1, snapshot.get("x").size());
    Assert.assertEquals(1, snapshot.get("y").size());
    Assert.assertEquals("A", snapshot.get("x").get(0).getHeader().getAttribute("a"));
    Assert.assertEquals("A", snapshot.get("y").get(0).getHeader().getAttribute("a"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testCombineLanes() throws Exception {
    SourceOffsetTracker tracker = Mockito.mock(SourceOffsetTracker.class);
    FullPipeBatch pipeBatch = new FullPipeBatch(tracker, -1, true);

    PipelineBean pipelineBean = getPipelineBean();
    StageRuntime[] stages = {
        new StageRuntime(pipelineBean, pipelineBean.getStages().get(0)),
        new StageRuntime(pipelineBean, pipelineBean.getStages().get(1))};

    StageContext context = Mockito.mock(StageContext.class);
    Mockito.when(context.isPreview()).thenReturn(false);
    stages[0].setContext(context);

    List<String> stageOutputLanes = stages[0].getConfiguration().getOutputLanes();
    StagePipe pipe = new StagePipe(stages[0], Collections.EMPTY_LIST,
      LaneResolver.getPostFixed(stageOutputLanes, LaneResolver.STAGE_OUT));

    // starting source
    BatchMakerImpl batchMaker = pipeBatch.startStage(pipe);
    Assert.assertEquals(new ArrayList<String>(stageOutputLanes), batchMaker.getLanes());

    Record record = new RecordImpl("i", "source", null, null);
    record.getHeader().setAttribute("a", "A");
    batchMaker.addRecord(record, stageOutputLanes.get(0));

    // completing source
    pipeBatch.completeStage(batchMaker);

    List<String> list = ImmutableList.of("x", "y");
    pipeBatch.moveLaneCopying(pipe.getOutputLanes().get(0), list);

    Record copiedRecordX = pipeBatch.getFullPayload().get("x").get(0);
    Record copiedRecordY = pipeBatch.getFullPayload().get("y").get(0);

    pipeBatch.combineLanes(list, "z");
    Map<String, List<Record>> snapshot = pipeBatch.getLaneOutputRecords(ImmutableList.of("z"));

    Assert.assertEquals(1, snapshot.size());
    Assert.assertEquals(2, snapshot.get("z").size());

    Assert.assertSame(copiedRecordX, pipeBatch.getFullPayload().get("z").get(0));
    Assert.assertSame(copiedRecordY, pipeBatch.getFullPayload().get("z").get(1));
  }


  @Test
  @SuppressWarnings("unchecked")
  public void testOverride() throws Exception {
    SourceOffsetTracker tracker = Mockito.mock(SourceOffsetTracker.class);
    PipeBatch pipeBatch = new FullPipeBatch(tracker, -1, true);

    PipelineBean pipelineBean = getPipelineBean();
    StageRuntime[] stages = {
        new StageRuntime(pipelineBean, pipelineBean.getStages().get(0)),
        new StageRuntime(pipelineBean, pipelineBean.getStages().get(1))};

    StageContext context = Mockito.mock(StageContext.class);
    Mockito.when(context.isPreview()).thenReturn(false);
    stages[0].setContext(context);
    stages[1].setContext(context);

    List<String> stageOutputLanes = stages[0].getConfiguration().getOutputLanes();
    StagePipe sourcePipe = new StagePipe(stages[0], Collections.EMPTY_LIST,
      LaneResolver.getPostFixed(stageOutputLanes, LaneResolver.STAGE_OUT));

    // starting source
    BatchMakerImpl batchMaker = pipeBatch.startStage(sourcePipe);
    Assert.assertEquals(new ArrayList<String>(stageOutputLanes), batchMaker.getLanes());

    Record origRecord = new RecordImpl("i", "source", null, null);
    origRecord.getHeader().setAttribute("a", "A");
    batchMaker.addRecord(origRecord, stageOutputLanes.get(0));

    // completing source
    pipeBatch.completeStage(batchMaker);

    StagePipe targetPipe = new StagePipe(stages[1], LaneResolver.getPostFixed(stageOutputLanes,
      LaneResolver.STAGE_OUT), Collections.EMPTY_LIST);

    // starting target
    batchMaker = pipeBatch.startStage(targetPipe);

    BatchImpl batch = pipeBatch.getBatch(targetPipe);

    // completing target
    pipeBatch.completeStage(batchMaker);

    // getting stages ouptut
    List<StageOutput> stageOutputs = pipeBatch.getSnapshotsOfAllStagesOutput();

    StageOutput sourceOutput = stageOutputs.get(0);
    Assert.assertEquals("s", sourceOutput.getInstanceName());

    Record modRecord = new RecordImpl("i", "source", null, null);
    modRecord.getHeader().setAttribute("a", "B");
    //modifying the source output
    sourceOutput.getOutput().get(stages[0].getConfiguration().getOutputLanes().get(0)).set(0, modRecord);

    //starting a new pipe batch
    pipeBatch = new FullPipeBatch(tracker, -1, true);

    //instead running source, we inject its previous-modified output, it implicitly starts the pipe
    pipeBatch.overrideStageOutput(sourcePipe, sourceOutput);

    // starting target
    pipeBatch.startStage(targetPipe);
    batch = pipeBatch.getBatch(targetPipe);
    Iterator<Record> it = batch.getRecords();
    Record tRecord = it.next();
    //check that we get the injected record.
    Assert.assertEquals("B", tRecord.getHeader().getAttribute("a"));
    Assert.assertFalse(it.hasNext());

    // completing target
    pipeBatch.completeStage(batchMaker);
  }

}
