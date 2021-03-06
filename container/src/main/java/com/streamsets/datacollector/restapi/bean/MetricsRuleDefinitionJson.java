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
package com.streamsets.datacollector.restapi.bean;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.streamsets.pipeline.api.impl.Utils;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricsRuleDefinitionJson {

 private final com.streamsets.datacollector.config.MetricsRuleDefinition metricsRuleDefinition;

  @JsonCreator
  public MetricsRuleDefinitionJson(@JsonProperty("id") String id,
                                   @JsonProperty("alertText") String alertText,
                                   @JsonProperty("metricId") String metricId,
                                   @JsonProperty("metricType") MetricTypeJson metricType,
                                   @JsonProperty("metricElement") MetricElementJson metricElementJson,
                                   @JsonProperty("condition") String condition,
                                   @JsonProperty("sendEmail") boolean sendEmail,
                                   @JsonProperty("enabled") boolean enabled) {
    this.metricsRuleDefinition = new com.streamsets.datacollector.config.MetricsRuleDefinition(id, alertText, metricId,
      BeanHelper.unwrapMetricType(metricType), BeanHelper.unwrapMetricElement(metricElementJson), condition, sendEmail,
      enabled);

  }

  public MetricsRuleDefinitionJson(com.streamsets.datacollector.config.MetricsRuleDefinition metricsRuleDefinition) {
    Utils.checkNotNull(metricsRuleDefinition, "metricsRuleDefinition");
    this.metricsRuleDefinition = metricsRuleDefinition;
  }

  public String getId() {
    return metricsRuleDefinition.getId();
  }

  public String getAlertText() {
    return metricsRuleDefinition.getAlertText();
  }

  public String getCondition() {
    return metricsRuleDefinition.getCondition();
  }

  public boolean isSendEmail() {
    return metricsRuleDefinition.isSendEmail();
  }

  public boolean isEnabled() {
    return metricsRuleDefinition.isEnabled();
  }

  public boolean isValid() {
    return metricsRuleDefinition.isValid();
  }

  public String getMetricId() {
    return metricsRuleDefinition.getMetricId();
  }

  public MetricElementJson getMetricElement() {
    return BeanHelper.wrapMetricElement(metricsRuleDefinition.getMetricElement());
  }

  public MetricTypeJson getMetricType() {
    return BeanHelper.wrapMetricType(metricsRuleDefinition.getMetricType());
  }

  @JsonIgnore
  public com.streamsets.datacollector.config.MetricsRuleDefinition getMetricsRuleDefinition() {
    return metricsRuleDefinition;
  }
}