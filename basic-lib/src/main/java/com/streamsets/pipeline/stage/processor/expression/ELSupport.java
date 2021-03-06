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
package com.streamsets.pipeline.stage.processor.expression;

import com.streamsets.pipeline.api.ElFunction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ELSupport {

  //TODO: decide prefix. These functions seem very similar to uuid which is in DataUtilEL and has no prefix.
  @ElFunction(
    prefix = "",
    name = "emptyMap",
    description = "Creates an empty map")
  public static Map createEmptyMap() {
    return new HashMap<>();
  }

  @ElFunction(
    prefix = "",
    name = "emptyList",
    description = "Creates an empty list")
  public static List createEmptyList() {
    return new ArrayList<>();
  }
}
