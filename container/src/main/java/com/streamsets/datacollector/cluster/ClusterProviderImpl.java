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
package com.streamsets.datacollector.cluster;

import static com.streamsets.datacollector.definition.StageLibraryDefinitionExtractor.DATA_COLLECTOR_LIBRARY_PROPERTIES;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.streamsets.datacollector.config.PipelineConfiguration;
import com.streamsets.datacollector.config.RuleDefinitions;
import com.streamsets.datacollector.config.StageConfiguration;
import com.streamsets.datacollector.config.StageDefinition;
import com.streamsets.datacollector.creation.PipelineBean;
import com.streamsets.datacollector.creation.PipelineBeanCreator;
import com.streamsets.datacollector.creation.PipelineConfigBean;
import com.streamsets.datacollector.creation.StageBean;
import com.streamsets.datacollector.execution.runner.common.Constants;
import com.streamsets.datacollector.http.WebServerTask;
import com.streamsets.datacollector.json.ObjectMapperFactory;
import com.streamsets.datacollector.main.RuntimeInfo;
import com.streamsets.datacollector.main.RuntimeModule;
import com.streamsets.datacollector.restapi.bean.BeanHelper;
import com.streamsets.datacollector.security.SecurityConfiguration;
import com.streamsets.datacollector.stagelibrary.StageLibraryTask;
import com.streamsets.datacollector.stagelibrary.StageLibraryUtils;
import com.streamsets.datacollector.store.PipelineInfo;
import com.streamsets.datacollector.store.impl.FilePipelineStoreTask;
import com.streamsets.datacollector.util.PipelineDirectoryUtil;
import com.streamsets.datacollector.util.SystemProcessFactory;
import com.streamsets.datacollector.validation.Issue;
import com.streamsets.pipeline.api.impl.PipelineUtils;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.api.Config;
import com.streamsets.pipeline.api.ExecutionMode;
import com.streamsets.pipeline.lib.util.ThreadUtil;
import com.streamsets.pipeline.util.SystemProcess;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClusterProviderImpl implements ClusterProvider {
  static final Pattern YARN_APPLICATION_ID_REGEX = Pattern.compile("\\s(application_[0-9]+_[0-9]+)(\\s|$)");
  static final Pattern NO_VALID_CREDENTIALS = Pattern.compile("(No valid credentials provided.*)");
  public static final String CLUSTER_TYPE = "CLUSTER_TYPE";
  public static final String CLUSTER_TYPE_SPARK = "spark";
  public static final String CLUSTER_TYPE_MAPREDUCE = "mr";
  private static final String CLUSTER_TYPE_YARN = "yarn";
  private static final String KERBEROS_AUTH = "KERBEROS_AUTH";
  private static final String KERBEROS_KEYTAB = "KERBEROS_KEYTAB";
  private static final String KERBEROS_PRINCIPAL = "KERBEROS_PRINCIPAL";
  private static final String CLUSTER_MODE_JAR_BLACKLIST = "cluster.jar.blacklist.regex_";
  private static final String ALL_STAGES = "*";
  private final RuntimeInfo runtimeInfo;
  private final YARNStatusParser yarnStatusParser;
  /**
   * Only null in the case of tests
   */
  private final @Nullable SecurityConfiguration securityConfiguration;

  private static final Logger LOG = LoggerFactory.getLogger(ClusterProviderImpl.class);
  private static final boolean IS_TRACE_ENABLED = LOG.isTraceEnabled();

  @VisibleForTesting
  ClusterProviderImpl() {
    this(null, null);
  }

  public ClusterProviderImpl(RuntimeInfo runtimeInfo, SecurityConfiguration securityConfiguration) {
    this.runtimeInfo = runtimeInfo;
    this.securityConfiguration = securityConfiguration;
    this.yarnStatusParser = new YARNStatusParser();
  }

  @Override
  public void killPipeline(SystemProcessFactory systemProcessFactory, File sparkManager, File tempDir,
                    String appId, PipelineConfiguration pipelineConfiguration) throws TimeoutException, IOException {
    Map<String, String> environment = new HashMap<>();
    environment.put(CLUSTER_TYPE, CLUSTER_TYPE_YARN);
    addKerberosConfiguration(environment);
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add(sparkManager.getAbsolutePath());
    args.add("kill");
    args.add(appId);
    SystemProcess process = systemProcessFactory.create(ClusterProviderImpl.class.getSimpleName(), tempDir, args.build());
    try {
      process.start(environment);
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        logOutput(appId, process);
        throw new TimeoutException(errorString("YARN kill command for {} timed out.", appId));
      }
    } finally {
      process.cleanup();
    }
  }

  private static String errorString(String template, Object... args) {
    return Utils.format("ERROR: " + template, args);
  }

  private static void logOutput(String appId, SystemProcess process) {
    try {
      LOG.info("YARN status command standard error: {} ", Joiner.on("\n").join(process.getAllError()));
      LOG.info("YARN status command standard output: {} ", Joiner.on("\n").join(process.getAllOutput()));
    } catch (Exception e) {
      String msg = errorString("Could not read output of command '{}' for app {}: {}", process.getCommand(), appId, e);
      LOG.error(msg, e);
    }
  }

  @Override
  public ClusterPipelineStatus getStatus(SystemProcessFactory systemProcessFactory, File sparkManager, File tempDir,
                    String appId, PipelineConfiguration pipelineConfiguration) throws TimeoutException, IOException {

    Map<String, String> environment = new HashMap<>();
    environment.put(CLUSTER_TYPE, CLUSTER_TYPE_YARN);
    addKerberosConfiguration(environment);
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add(sparkManager.getAbsolutePath());
    args.add("status");
    args.add(appId);
    SystemProcess process = systemProcessFactory.create(ClusterProviderImpl.class.getSimpleName(), tempDir, args.build());
    try {
      process.start(environment);
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        logOutput(appId, process);
        throw new TimeoutException(errorString("YARN status command for {} timed out.", appId));
      }
      if (process.exitValue() != 0) {
        logOutput(appId, process);
        throw new IllegalStateException(errorString("YARN status command for {} failed with exit code {}.", appId,
          process.exitValue()));
      }
      logOutput(appId, process);
      String status = yarnStatusParser.parseStatus(process.getAllOutput());
      return ClusterPipelineStatus.valueOf(status);
    } finally {
      process.cleanup();
    }
  }

  private void rewriteProperties(File sdcPropertiesFile, Map<String, String> sourceConfigs,
                                 Map<String, String> sourceInfo, String clusterToken) throws IOException{
    InputStream sdcInStream = null;
    OutputStream sdcOutStream = null;
    Properties sdcProperties = new Properties();
    try {
      sdcInStream = new FileInputStream(sdcPropertiesFile);
      sdcProperties.load(sdcInStream);
      sdcProperties.setProperty(WebServerTask.HTTP_PORT_KEY, "0");
      sdcProperties.setProperty(WebServerTask.HTTPS_PORT_KEY, "-1");
      sdcProperties.setProperty(RuntimeModule.PIPELINE_EXECUTION_MODE_KEY,
        ExecutionMode.SLAVE.name());
      if(runtimeInfo != null) {
        sdcProperties.setProperty(Constants.PIPELINE_CLUSTER_TOKEN_KEY, clusterToken);
        sdcProperties.setProperty(Constants.CALLBACK_SERVER_URL_KEY, runtimeInfo.getClusterCallbackURL());
      }

      for (Map.Entry<String, String> entry : sourceConfigs.entrySet()) {
        try {
          sdcProperties.setProperty(entry.getKey(), entry.getValue());
        } catch (NullPointerException ex) {
          // we've had bugs where a key or value was null in the past
          String msg = Utils.format("Key '{}', value: '{}'", entry.getKey(), entry.getValue());
          throw new NullPointerException(msg);
        }
      }
      for (Map.Entry<String, String> entry : sourceInfo.entrySet()) {
        try {
          sdcProperties.setProperty(entry.getKey(), entry.getValue());
        } catch (NullPointerException ex) {
          // we've had bugs where a key or value was null in the past
          String msg = Utils.format("Key '{}', value: '{}'", entry.getKey(), entry.getValue());
          throw new NullPointerException(msg);
        }
      }

      sdcOutStream = new FileOutputStream(sdcPropertiesFile);
      sdcProperties.store(sdcOutStream, null);
      LOG.debug("sourceConfigs = {}", sourceConfigs);
      LOG.debug("sourceInfo = {}", sourceInfo);
      LOG.debug("sdcProperties = {}", sdcProperties);
      sdcOutStream.flush();
      sdcOutStream.close();
    } finally {
      if (sdcInStream != null) {
        IOUtils.closeQuietly(sdcInStream);
      }
      if (sdcOutStream != null) {
        IOUtils.closeQuietly(sdcOutStream);
      }
    }
  }

  private static File getBootstrapJar(File bootstrapDir, final String name) {
    Utils.checkState(bootstrapDir.isDirectory(), Utils.format("SDC bootstrap lib does not exist: {}", bootstrapDir));
    File[] candidates = bootstrapDir.listFiles(new FileFilter() {
      @Override
      public boolean accept(File canidate) {
        return canidate.getName().startsWith(name) &&
          canidate.getName().endsWith(".jar");
      }
    });
    Utils.checkState(candidates != null, Utils.format("Did not find jar matching {} in {}", name, bootstrapDir));
    Utils.checkState(candidates.length == 1, Utils.format("Did not find exactly one bootstrap jar: {}",
      Arrays.toString(candidates)));
    return candidates[0];
  }

  private void addKerberosConfiguration(Map<String, String> environment) {
    if (securityConfiguration != null) {
      environment.put(KERBEROS_AUTH, String.valueOf(securityConfiguration.isKerberosEnabled()));
      if (securityConfiguration.isKerberosEnabled()) {
        environment.put(KERBEROS_PRINCIPAL, securityConfiguration.getKerberosPrincipal());
        environment.put(KERBEROS_KEYTAB, securityConfiguration.getKerberosKeytab());
      }
    }
  }

  static File createDirectoryClone(File srcDir, String dirName, File tempDir) throws IOException {
    File tempSrcDir = new File(tempDir, dirName);
    FileUtils.deleteQuietly(tempSrcDir);
    Utils.checkState(tempSrcDir.mkdir(), Utils.formatL("Could not create {}", tempSrcDir));
    doCopyDirectory(srcDir, tempSrcDir);
    return tempSrcDir;
  }
  private static void doCopyDirectory(final File srcDir, final File destDir)
    throws IOException {
    // code copied from commons-io FileUtils to work around files which cannot be read
    // recurse
    final File[] srcFiles = srcDir.listFiles();
    if (srcFiles == null) {  // null if abstract pathname does not denote a directory, or if an I/O error occurs
      throw new IOException("Failed to list contents of " + srcDir);
    }
    if (destDir.exists()) {
      if (destDir.isDirectory() == false) {
        throw new IOException("Destination '" + destDir + "' exists but is not a directory");
      }
    } else {
      if (!destDir.mkdirs() && !destDir.isDirectory()) {
        throw new IOException("Destination '" + destDir + "' directory cannot be created");
      }
    }
    if (destDir.canWrite() == false) {
      throw new IOException("Destination '" + destDir + "' cannot be written to");
    }
    for (final File srcFile : srcFiles) {
      final File dstFile = new File(destDir, srcFile.getName());
      if (srcFile.canRead()) { // ignore files which cannot be read
        if (srcFile.isDirectory()) {
          doCopyDirectory(srcFile, dstFile);
        } else {
          try (InputStream in = new FileInputStream((srcFile))) {
            try (OutputStream out = new FileOutputStream((dstFile))) {
              IOUtils.copy(in, out);
            }
          }
        }
      }
    }
  }


  static boolean exclude(List<String> blacklist, String name) {
    for (String pattern : blacklist) {
      if (Pattern.compile(pattern).matcher(name).find()) {
        return true;
      } else if (IS_TRACE_ENABLED) {
        LOG.trace("Pattern '{}' does not match '{}'", pattern, name);
      }
    }
    return false;
  }

  private static Properties readDataCollectorProperties(ClassLoader cl) throws IOException {
    Properties properties = new Properties();
    while (cl != null) {
      Enumeration<URL> urls = cl.getResources(DATA_COLLECTOR_LIBRARY_PROPERTIES);
      if (urls != null) {
        while (urls.hasMoreElements()) {
          URL url = urls.nextElement();
          LOG.trace("Loading data collector library properties: {}", url);
          try (InputStream inputStream = url.openStream()) {
            properties.load(inputStream);
          }
        }
      }
      cl = cl.getParent();
    }
    LOG.trace("Final properties: {} ", properties);
    return properties;
  }

  private static List<URL> findJars(String name, URLClassLoader cl, @Nullable String stageClazzName)
    throws IOException {
    Properties properties = readDataCollectorProperties(cl);
    List<String> blacklist = new ArrayList<>();
    for (Map.Entry entry : properties.entrySet()) {
      String key = (String) entry.getKey();
      if (stageClazzName != null && key.equals(CLUSTER_MODE_JAR_BLACKLIST + stageClazzName)) {
        String value = (String) entry.getValue();
        blacklist.addAll(Splitter.on(",").trimResults().omitEmptyStrings().splitToList(value));
      } else if (key.equals(CLUSTER_MODE_JAR_BLACKLIST + ALL_STAGES)) {
        String value = (String) entry.getValue();
        blacklist.addAll(Splitter.on(",").trimResults().omitEmptyStrings().splitToList(value));
      }
    }
    if (IS_TRACE_ENABLED) {
      LOG.trace("Blacklist for '{}': '{}'", name, blacklist);
    }
    List<URL> urls = new ArrayList<>();
    for (URL url : cl.getURLs()) {
      if (blacklist.isEmpty()) {
        urls.add(url);
      } else {
        if (exclude(blacklist, FilenameUtils.getName(url.getPath()))) {
          LOG.trace("Skipping '{}' for '{}' due to '{}'", url, name, blacklist);
        } else {
          urls.add(url);
        }
      }
    }
    return urls;
  }

  @Override
  public ApplicationState startPipeline(SystemProcessFactory systemProcessFactory, File clusterManager, File outputDir,
                       Map<String, String> environment, Map<String, String> sourceInfo,
                       PipelineConfiguration pipelineConfiguration, StageLibraryTask stageLibrary,
                       File etcDir, File resourcesDir, File staticWebDir, File bootstrapDir, URLClassLoader apiCL,
                       URLClassLoader containerCL, long timeToWaitForFailure, RuleDefinitions ruleDefinitions) throws IOException, TimeoutException {
    File stagingDir = new File(outputDir, "staging");
    stagingDir.mkdirs();
    if (!stagingDir.isDirectory()) {
      String msg = Utils.format("Could not create staging directory: {}", stagingDir);
      throw new IllegalStateException(msg);
    }
    try {
      return startPipelineInternal(systemProcessFactory, clusterManager, outputDir, environment, sourceInfo,
        pipelineConfiguration, stageLibrary, etcDir, resourcesDir, staticWebDir, bootstrapDir, apiCL, containerCL,
        timeToWaitForFailure, stagingDir, ruleDefinitions);
    } finally {
      // in testing mode the staging dir is used by yarn
      // tasks and thus cannot be deleted
      if (!Boolean.getBoolean("sdc.testing-mode") && !FileUtils.deleteQuietly(stagingDir)) {
        LOG.warn("Unable to cleanup: {}", stagingDir);
      }
    }
  }


  private ApplicationState  startPipelineInternal(SystemProcessFactory systemProcessFactory, File clusterManager,
      File outputDir, Map<String, String> environment, Map<String, String> sourceInfo,
      PipelineConfiguration pipelineConfiguration, StageLibraryTask stageLibrary,
      File etcDir, File resourcesDir, File staticWebDir, File bootstrapDir, URLClassLoader apiCL,
      URLClassLoader containerCL, long timeToWaitForFailure, File stagingDir, RuleDefinitions ruleDefinitions) throws IOException, TimeoutException {
    environment = Maps.newHashMap(environment);
    // create libs.tar.gz file for pipeline
    Map<String, List<URL> > streamsetsLibsCl = new HashMap<>();
    Map<String, List<URL> > userLibsCL = new HashMap<>();
    Map<String, String> sourceConfigs = new HashMap<>();
    ImmutableList.Builder<StageConfiguration> pipelineConfigurations = ImmutableList.builder();
    // order is important here as we don't want error stage
    // configs overriding source stage configs
    String clusterToken = UUID.randomUUID().toString();
    List<String> jarsToShip = new ArrayList<String>();
    List<Issue> errors = new ArrayList<>();
    PipelineBean pipelineBean = PipelineBeanCreator.get().create(false, stageLibrary, pipelineConfiguration, errors);
    if (!errors.isEmpty()) {
      String msg = Utils.format("Found '{}' configuration errors: {}", errors.size(), errors);
      throw new IllegalStateException(msg);
    }
    pipelineConfigurations.add(pipelineBean.getErrorStage().getConfiguration());
    for (StageBean stageBean : pipelineBean.getStages()) {
      pipelineConfigurations.add(stageBean.getConfiguration());
    }
    boolean isBatch = false;
    for (StageConfiguration stageConf : pipelineConfigurations.build()) {
      StageDefinition stageDef = stageLibrary.getStage(stageConf.getLibrary(), stageConf.getStageName(),
                                                     false);
      if (stageConf.getInputLanes().isEmpty()) {
        for (Config conf : stageConf.getConfiguration()) {
          if (conf.getValue() != null) {
            Object value = conf.getValue();
            if (value instanceof List) {
              List values = (List) value;
              if (values.isEmpty()) {
                LOG.debug("Conf value for " + conf.getName() + " is empty");
              } else {
                Object first = values.get(0);
                if (canCastToString(first)) {
                  sourceConfigs.put(conf.getName(), Joiner.on(",").join(values));
                } else if (first instanceof Map) {
                  addToSourceConfigs(sourceConfigs, (List<Map<String,Object>>)values);
                } else {
                  LOG.info("List is of type '{}' which cannot be converted to property value.", first.getClass()
                    .getName());
                }
              }
            } else if (canCastToString(conf.getValue())) {
              LOG.debug("Adding to source configs " + conf.getName() + "=" + value);
              sourceConfigs.put(conf.getName(), String.valueOf(value));
            } else if (value instanceof Enum) {
              value = ((Enum)value).name();
              LOG.debug("Adding to source configs " + conf.getName() + "=" + value);
              sourceConfigs.put(conf.getName(), String.valueOf(value));
            } else {
              LOG.warn("Conf value is of unknown type " + conf.getValue());
            }
          }
        }
        ExecutionMode executionMode = PipelineBeanCreator.get().getExecutionMode(pipelineConfiguration, new ArrayList<Issue>());
        if (executionMode == ExecutionMode.CLUSTER_BATCH) {
          isBatch = true;
        } else if (executionMode == ExecutionMode.CLUSTER_STREAMING) {
          isBatch = false;
        } else {
          throw new IllegalStateException(Utils.format("Unsupported execution mode '{}' for cluster mode",
            executionMode));
        }
        List<String> libJarsRegex = stageDef.getLibJarsRegex();
        if (!libJarsRegex.isEmpty()) {
          for (URL jarUrl : ((URLClassLoader) stageDef.getStageClassLoader()).getURLs()) {
            File jarFile = new File(jarUrl.getPath());
            for (String libJar : libJarsRegex) {
              Pattern pattern = Pattern.compile(libJar);
              Matcher matcher = pattern.matcher(jarFile.getName());
              if (matcher.matches()) {
                jarsToShip.add(jarFile.getAbsolutePath());
              }
            }
          }
        }
      }
      String type = StageLibraryUtils.getLibraryType(stageDef.getStageClassLoader());
      String name = StageLibraryUtils.getLibraryName(stageDef.getStageClassLoader());
      if (ClusterModeConstants.STREAMSETS_LIBS.equals(type)) {
        streamsetsLibsCl.put(name, findJars(name, (URLClassLoader)stageDef.getStageClassLoader(),
          stageDef.getClassName()));
      } else if (ClusterModeConstants.USER_LIBS.equals(type)) {
        userLibsCL.put(name, findJars(name, (URLClassLoader) stageDef.getStageClassLoader(), stageDef.getClassName()));
      } else {
        throw new IllegalStateException(Utils.format("Error unknown stage library type: '{}'", type));
      }
    }
    LOG.info("stagingDir = '{}'", stagingDir);
    LOG.info("bootstrapDir = '{}'", bootstrapDir);
    LOG.info("etcDir = '{}'", etcDir);
    LOG.info("resourcesDir = '{}'", resourcesDir);
    LOG.info("staticWebDir = '{}'", staticWebDir);

    Utils.checkState(staticWebDir.isDirectory(), Utils.format("Expected '{}' to be a directory", staticWebDir));
    File libsTarGz = new File(stagingDir, "libs.tar.gz");
    try {
      TarFileCreator.createLibsTarGz(findJars("api", apiCL, null), findJars("container", containerCL, null),
        streamsetsLibsCl, userLibsCL, staticWebDir, libsTarGz);
    } catch (Exception ex) {
      String msg = errorString("Serializing classpath: '{}'", ex);
      throw new RuntimeException(msg, ex);
    }
    File resourcesTarGz = new File(stagingDir, "resources.tar.gz");
    try {
      resourcesDir = createDirectoryClone(resourcesDir, "resources", stagingDir);
      TarFileCreator.createTarGz(resourcesDir, resourcesTarGz);
    } catch (Exception ex) {
      String msg = errorString("Serializing resources directory: '{}'", resourcesDir.getName(), ex);
      throw new RuntimeException(msg, ex);
    }
    File etcTarGz = new File(stagingDir, "etc.tar.gz");
    File sdcPropertiesFile;
    try {
      etcDir = createDirectoryClone(etcDir, "etc", stagingDir);
      PipelineInfo pipelineInfo = Utils.checkNotNull(pipelineConfiguration.getInfo(), "Pipeline Info");
      String pipelineName = pipelineInfo.getName();
      File rootDataDir = new File(etcDir, "data");
      File pipelineBaseDir = new File(rootDataDir, PipelineDirectoryUtil.PIPELINE_INFO_BASE_DIR);
      File pipelineDir = new File(pipelineBaseDir, PipelineUtils.escapedPipelineName(pipelineName));
      if (!pipelineDir.exists()) {
        pipelineDir.mkdirs();
      }
      File pipelineFile = new File(pipelineDir, FilePipelineStoreTask.PIPELINE_FILE);
      ObjectMapperFactory.getOneLine().writeValue(pipelineFile,
        BeanHelper.wrapPipelineConfiguration(pipelineConfiguration));
      File infoFile = new File(pipelineDir, FilePipelineStoreTask.INFO_FILE);
      ObjectMapperFactory.getOneLine().writeValue(infoFile, BeanHelper.wrapPipelineInfo(pipelineInfo));
      Utils.checkNotNull(ruleDefinitions, "ruleDefinitions");
      File rulesFile = new File(pipelineDir, FilePipelineStoreTask.RULES_FILE);
      ObjectMapperFactory.getOneLine().writeValue(rulesFile, BeanHelper.wrapRuleDefinitions(ruleDefinitions));
      sdcPropertiesFile = new File(etcDir, "sdc.properties");
      rewriteProperties(sdcPropertiesFile, sourceConfigs, sourceInfo, clusterToken);
      TarFileCreator.createTarGz(etcDir, etcTarGz);
    } catch (Exception ex) {
      String msg = errorString("serializing etc directory: {}", ex);
      throw new RuntimeException(msg, ex);
    }
    File bootstrapJar = getBootstrapJar(new File(bootstrapDir, "main"), "streamsets-datacollector-bootstrap");
    File clusterBootstrapJar = getBootstrapJar(new File(bootstrapDir, "spark"),
      "streamsets-datacollector-spark-bootstrap");
    File log4jProperties = new File(stagingDir, "log4j.properties");
    InputStream clusterLog4jProperties = null;
    try {
      if (isBatch) {
        clusterLog4jProperties = Utils.checkNotNull(getClass().getResourceAsStream("/cluster-mr-log4j.properties"),
          "Cluster Log4J Properties");
      } else {
        clusterLog4jProperties = Utils.checkNotNull(getClass().getResourceAsStream("/cluster-spark-log4j.properties"),
          "Cluster Log4J Properties");
      }
      FileUtils.copyInputStreamToFile(clusterLog4jProperties, log4jProperties);
    } catch (IOException ex) {
      String msg = errorString("copying log4j configuration: {}", ex);
      throw new RuntimeException(msg, ex);
    } finally {
      if (clusterLog4jProperties != null) {
        IOUtils.closeQuietly(clusterLog4jProperties);
      }
    }
    addKerberosConfiguration(environment);
    errors.clear();
    PipelineConfigBean config = PipelineBeanCreator.get().create(pipelineConfiguration, errors);
    Utils.checkArgument(config != null, Utils.formatL("Invalid pipeline configuration: {}", errors));
    String numExecutors = sourceInfo.get(ClusterModeConstants.NUM_EXECUTORS_KEY);
    List<String> args;
    if (isBatch) {
      LOG.info("Submitting MapReduce Job");
      environment.put(CLUSTER_TYPE, CLUSTER_TYPE_MAPREDUCE);
      args = generateMRArgs(clusterManager.getAbsolutePath(), String.valueOf(config.clusterSlaveMemory),
        config.clusterSlaveJavaOpts, libsTarGz.getAbsolutePath(), etcTarGz.getAbsolutePath(),
        resourcesTarGz.getAbsolutePath(), log4jProperties.getAbsolutePath(), bootstrapJar.getAbsolutePath(),
        sdcPropertiesFile.getAbsolutePath(), clusterBootstrapJar.getAbsolutePath(), jarsToShip);
    } else {
      LOG.info("Submitting Spark Job");
      environment.put(CLUSTER_TYPE, CLUSTER_TYPE_SPARK);
      args = generateSparkArgs(clusterManager.getAbsolutePath(), String.valueOf(config.clusterSlaveMemory),
        config.clusterSlaveJavaOpts, numExecutors, libsTarGz.getAbsolutePath(), etcTarGz.getAbsolutePath(),
        resourcesTarGz.getAbsolutePath(), log4jProperties.getAbsolutePath(), bootstrapJar.getAbsolutePath(),
        jarsToShip, clusterBootstrapJar.getAbsolutePath());
    }
    SystemProcess process = systemProcessFactory.create(ClusterProviderImpl.class.getSimpleName(), outputDir, args);
    LOG.info("Starting: " + process);
    try {
      process.start(environment);
      long start = System.currentTimeMillis();
      Set<String> applicationIds = new HashSet<>();
      while (true) {
        long elapsedSeconds = TimeUnit.SECONDS.convert(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS);
        LOG.debug("Waiting for application id, elapsed seconds: " + elapsedSeconds);
        if (applicationIds.size() > 1) {
          logOutput("unknown", process);
          throw new IllegalStateException(errorString("Found more than one application id: {}", applicationIds));
        } else if (!applicationIds.isEmpty()) {
          String appId = applicationIds.iterator().next();
          logOutput(appId, process);
          ApplicationState applicationState = new ApplicationState();
          applicationState.setId(appId);
          applicationState.setSdcToken(clusterToken);
          return applicationState;
        }
        if (!ThreadUtil.sleep(1000)) {
          throw new IllegalStateException("Interrupted while waiting for pipeline to start");
        }
        List<String> lines = new ArrayList<>();
        lines.addAll(process.getOutput());
        lines.addAll(process.getError());
        for (String line : lines) {
          Matcher m = YARN_APPLICATION_ID_REGEX.matcher(line);
          if (m.find()) {
            LOG.info("Found application id " + m.group(1));
            applicationIds.add(m.group(1));
          }
          m = NO_VALID_CREDENTIALS.matcher(line);
          if (m.find()) {
            LOG.info("Kerberos Error found on line: " + line);
            String msg = "Kerberos Error: " + m.group(1);
            throw new IOException(msg);
          }
        }
        if (elapsedSeconds > timeToWaitForFailure) {
          logOutput("unknown", process);
          String msg = Utils.format("Timed out after waiting {} seconds for for cluster application to start. " +
            "Submit command {} alive.", elapsedSeconds, (process.isAlive() ? "is" : "is not"));
          throw new IllegalStateException(msg);
        }
      }
    } finally {
      process.cleanup();
    }
  }
  private List<String> generateMRArgs(String clusterManager, String slaveMemory, String javaOpts,
                                      String libsTarGz, String etcTarGz, String resourcesTarGz, String log4jProperties,
                                      String bootstrapJar, String sdcPropertiesFile,
                                      String clusterBootstrapJar, List<String> jarsToShip) {
    List<String> args = new ArrayList<>();
    args.add(clusterManager);
    args.add("start");
    args.add("jar");
    args.add(clusterBootstrapJar);
    args.add("com.streamsets.pipeline.BootstrapClusterBatch");
    args.add("-archives");
    args.add(Joiner.on(",").join(libsTarGz, etcTarGz, resourcesTarGz));
    args.add("-D");
    args.add("mapreduce.job.log4j-properties-file=" + log4jProperties);
    args.add("-libjars");
    String libJarString = bootstrapJar;
    for (String jarToShip: jarsToShip) {
      libJarString = libJarString + "," + jarToShip;
    }
    args.add(libJarString);
    args.add(sdcPropertiesFile);
    args.add(Joiner.on(" ").join(String.format("-Xmx%sm", slaveMemory), javaOpts,
      "-javaagent:./" + (new File(bootstrapJar)).getName()));
    return args;
  }

  private List<String> generateSparkArgs(String clusterManager, String slaveMemory, String javaOpts,
                             String numExecutors, String libsTarGz, String etcTarGz, String resourcesTarGz,
                             String log4jProperties, String bootstrapJar, List<String> jarsToShip,
                             String clusterBootstrapJar) {
    List<String> args = new ArrayList<>();
    args.add(clusterManager);
    args.add("start");
    // we only support yarn-cluster mode
    args.add("--master");
    args.add("yarn-cluster");
    args.add("--executor-memory");
    args.add(slaveMemory + "m");
    // one single sdc per executor
    args.add("--executor-cores");
    args.add("1");

    // Number of Executors based on the origin parallelism
    checkNumExecutors(numExecutors);
    args.add("--num-executors");
    args.add(numExecutors);

    // ship our stage libs and etc directory
    args.add("--archives");
    args.add(Joiner.on(",").join(libsTarGz, etcTarGz, resourcesTarGz));
    // required or else we won't be able to log on cluster
    args.add("--files");
    args.add(log4jProperties);
    args.add("--jars");
    String libJarString = bootstrapJar;
    for (String jarToShip: jarsToShip) {
      libJarString = libJarString + "," + jarToShip;
    }
    args.add(libJarString);
    // use our javaagent and java opt configs
    args.add("--conf");
    args.add("spark.executor.extraJavaOptions=" + Joiner.on(" ").join("-javaagent:./" + (new File(bootstrapJar)).getName(),
      javaOpts));
    // main class
    args.add("--class");
    args.add("com.streamsets.pipeline.BootstrapClusterStreaming");
    args.add(clusterBootstrapJar);
    return args;
  }

  private void addToSourceConfigs(Map<String, String> sourceConfigs, List<Map<String, Object>> arrayListValues) {
    for (Map<String, Object> map : arrayListValues) {
      String confKey = null;
      String confValue = null;
      for (Map.Entry<String, Object> mapEntry : map.entrySet()) {
        String mapKey = mapEntry.getKey();
        Object mapValue = mapEntry.getValue();
        if (mapKey.equals("key")) {
          // Assuming the key is always string
          confKey = String.valueOf(mapValue);
        } else if (mapKey.equals("value")) {
          confValue = canCastToString(mapValue) ? String.valueOf(mapValue) : null;
        } else {
          confKey = mapKey;
          confValue = canCastToString(mapValue) ? String.valueOf(mapValue) : null;
        }
        if (confKey != null && confValue != null) {
          LOG.debug("Adding to source configs " + confKey + "=" + confValue);
          sourceConfigs.put(confKey, confValue);
        }
      }
    }
  }

  private boolean canCastToString(Object value) {
    if (value instanceof String || value instanceof Number || value.getClass().isPrimitive()
      || value instanceof Boolean) {
      return true;
    }
    return false;
  }

  private void checkNumExecutors(String numExecutorsString) {
    Utils.checkNotNull(numExecutorsString,"Number of executors not found");
    int numExecutors;
    try {
      numExecutors = Integer.parseInt(numExecutorsString);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Number of executors is not a valid integer");
    }
    Utils.checkArgument(numExecutors > 0, "Number of executors cannot be less than 1");
  }

  private enum ClusterOrigin {
    HDFS, KAFKA;
  }
}
