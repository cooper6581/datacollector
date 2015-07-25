/**
 * (c) 2015 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.datacollector.websockets;

import com.streamsets.datacollector.execution.StateEventListener;

import java.util.Queue;


public class StatusWebSocket extends BaseWebSocket implements StateEventListener{
  public static final String TYPE = "status";

  public StatusWebSocket(ListenerManager<StateEventListener> listenerManager, Queue<WebSocketMessage> queue) {
    super(TYPE, listenerManager, queue);
  }

}