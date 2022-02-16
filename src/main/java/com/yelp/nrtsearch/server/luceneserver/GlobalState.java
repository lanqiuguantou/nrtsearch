/*
 * Copyright 2020 Yelp Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yelp.nrtsearch.server.luceneserver;

import com.yelp.nrtsearch.server.backup.Archiver;
import com.yelp.nrtsearch.server.config.LuceneServerConfiguration;
import com.yelp.nrtsearch.server.config.ThreadPoolConfiguration;
import com.yelp.nrtsearch.server.luceneserver.state.BackendGlobalState;
import com.yelp.nrtsearch.server.luceneserver.state.LegacyGlobalState;
import com.yelp.nrtsearch.server.utils.ThreadPoolExecutorFactory;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import org.apache.lucene.search.TimeLimitingCollector;
import org.apache.lucene.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class GlobalState implements Closeable {
  private static final Logger logger = LoggerFactory.getLogger(GlobalState.class);
  private final String hostName;
  private final int port;
  private final int replicationPort;
  private final ThreadPoolConfiguration threadPoolConfiguration;
  private final Archiver incArchiver;
  private int replicaReplicationPortPingInterval;
  private final String ephemeralId = UUID.randomUUID().toString();

  private final String nodeName;

  private final List<RemoteNodeConnection> remoteNodes = new CopyOnWriteArrayList<>();

  private final LuceneServerConfiguration configuration;

  /** Server shuts down once this latch is decremented. */
  private final CountDownLatch shutdownNow = new CountDownLatch(1);

  private final Path stateDir;
  private final Path indexDirBase;

  private final ExecutorService indexService;
  private final ExecutorService fetchService;
  private final ThreadPoolExecutor searchThreadPoolExecutor;

  public static GlobalState createState(LuceneServerConfiguration luceneServerConfiguration)
      throws IOException {
    return createState(luceneServerConfiguration, null);
  }

  public static GlobalState createState(
      LuceneServerConfiguration luceneServerConfiguration, Archiver incArchiver)
      throws IOException {
    if (luceneServerConfiguration.getStateConfig().useLegacyStateManagement()) {
      return new LegacyGlobalState(luceneServerConfiguration, incArchiver);
    } else {
      return new BackendGlobalState(luceneServerConfiguration, incArchiver);
    }
  }

  public Optional<Archiver> getIncArchiver() {
    return Optional.ofNullable(incArchiver);
  }

  protected GlobalState(LuceneServerConfiguration luceneServerConfiguration, Archiver incArchiver)
      throws IOException {
    this.incArchiver = incArchiver;
    this.nodeName = luceneServerConfiguration.getNodeName();
    this.stateDir = Paths.get(luceneServerConfiguration.getStateDir());
    this.indexDirBase = Paths.get(luceneServerConfiguration.getIndexDir());
    this.hostName = luceneServerConfiguration.getHostName();
    this.port = luceneServerConfiguration.getPort();
    this.replicationPort = luceneServerConfiguration.getReplicationPort();
    this.replicaReplicationPortPingInterval =
        luceneServerConfiguration.getReplicaReplicationPortPingInterval();
    this.threadPoolConfiguration = luceneServerConfiguration.getThreadPoolConfiguration();
    if (Files.exists(stateDir) == false) {
      Files.createDirectories(stateDir);
    }
    this.indexService =
        ThreadPoolExecutorFactory.getThreadPoolExecutor(
            ThreadPoolExecutorFactory.ExecutorType.INDEX,
            luceneServerConfiguration.getThreadPoolConfiguration());
    this.searchThreadPoolExecutor =
        ThreadPoolExecutorFactory.getThreadPoolExecutor(
            ThreadPoolExecutorFactory.ExecutorType.SEARCH,
            luceneServerConfiguration.getThreadPoolConfiguration());
    this.fetchService =
        ThreadPoolExecutorFactory.getThreadPoolExecutor(
            ThreadPoolExecutorFactory.ExecutorType.FETCH,
            luceneServerConfiguration.getThreadPoolConfiguration());
    this.configuration = luceneServerConfiguration;
  }

  public LuceneServerConfiguration getConfiguration() {
    return configuration;
  }

  public String getNodeName() {
    return nodeName;
  }

  public String getHostName() {
    return hostName;
  }

  public int getPort() {
    return port;
  }

  public int getReplicaReplicationPortPingInterval() {
    return replicaReplicationPortPingInterval;
  }

  public void setReplicaReplicationPortPingInterval(int replicaReplicationPortPingInterval) {
    this.replicaReplicationPortPingInterval = replicaReplicationPortPingInterval;
  }

  public Path getStateDir() {
    return stateDir;
  }

  public CountDownLatch getShutdownLatch() {
    return shutdownNow;
  }

  @Override
  public void close() throws IOException {
    // searchThread.interrupt();
    IOUtils.close(remoteNodes);
    indexService.shutdown();
    TimeLimitingCollector.getGlobalTimerThread().stopTimer();
    try {
      TimeLimitingCollector.getGlobalTimerThread().join();
    } catch (InterruptedException ie) {
      throw new RuntimeException(ie);
    }
  }

  public Path getIndexDir(String indexName) {
    return Paths.get(indexDirBase.toString(), indexName);
  }

  public abstract void setStateDir(Path source) throws IOException;

  public abstract Set<String> getIndexNames();

  /** Create a new index. */
  public abstract IndexState createIndex(String name) throws IOException;

  public abstract IndexState getIndex(String name, boolean hasRestore) throws IOException;

  /** Get the {@link IndexState} by index name. */
  public abstract IndexState getIndex(String name) throws IOException;

  /** Remove the specified index. */
  public abstract void deleteIndex(String name) throws IOException;

  public abstract void indexClosed(String name);

  public Future<Long> submitIndexingTask(Callable job) {
    return indexService.submit(job);
  }

  public int getReplicationPort() {
    return replicationPort;
  }

  public ThreadPoolConfiguration getThreadPoolConfiguration() {
    return threadPoolConfiguration;
  }

  public ThreadPoolExecutor getSearchThreadPoolExecutor() {
    return searchThreadPoolExecutor;
  }

  public ExecutorService getFetchService() {
    return fetchService;
  }

  public String getEphemeralId() {
    return ephemeralId;
  }
}
