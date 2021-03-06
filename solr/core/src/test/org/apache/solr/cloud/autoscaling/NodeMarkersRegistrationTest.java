/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cloud.autoscaling;

import java.lang.invoke.MethodHandles;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.lucene.util.LuceneTestCase;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.cloud.autoscaling.TriggerEventType;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.cloud.LiveNodesListener;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.util.LogLevel;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.cloud.autoscaling.AutoScalingHandlerTest.createAutoScalingRequest;

@LogLevel("org.apache.solr.cloud.autoscaling=DEBUG;org.apache.solr.client.solrj.cloud.autoscaling=DEBUG")
@LuceneTestCase.BadApple(bugUrl = "https://issues.apache.org/jira/browse/SOLR-12028")
public class NodeMarkersRegistrationTest extends SolrCloudTestCase {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static CountDownLatch actionInitCalled;
  private static CountDownLatch triggerFiredLatch;
  private static CountDownLatch actionConstructorCalled;
  private static Set<TriggerEvent> events = ConcurrentHashMap.newKeySet();
  private static ZkStateReader zkStateReader;
  private static ReentrantLock lock = new ReentrantLock();

  @BeforeClass
  public static void setupCluster() throws Exception {
    configureCluster(2)
        .addConfig("conf", configset("cloud-minimal"))
        .configure();
    zkStateReader = cluster.getSolrClient().getZkStateReader();
    // disable .scheduled_maintenance
    String suspendTriggerCommand = "{" +
        "'suspend-trigger' : {'name' : '.scheduled_maintenance'}" +
        "}";
    SolrRequest req = createAutoScalingRequest(SolrRequest.METHOD.POST, suspendTriggerCommand);
    SolrClient solrClient = cluster.getSolrClient();
    NamedList<Object> response = solrClient.request(req);
    assertEquals(response.get("result").toString(), "success");
  }

  private static CountDownLatch getTriggerFiredLatch() {
    return triggerFiredLatch;
  }

  @Test
  public void testNodeMarkersRegistration() throws Exception {
    // for this test we want to create two triggers so we must assert that the actions were created twice
    actionInitCalled = new CountDownLatch(2);
    // similarly we want both triggers to fire
    triggerFiredLatch = new CountDownLatch(2);
    actionConstructorCalled = new CountDownLatch(1);
    TestLiveNodesListener listener = registerLiveNodesListener();

    NamedList<Object> overSeerStatus = cluster.getSolrClient().request(CollectionAdminRequest.getOverseerStatus());
    String overseerLeader = (String) overSeerStatus.get("leader");
    int overseerLeaderIndex = 0;
    for (int i = 0; i < cluster.getJettySolrRunners().size(); i++) {
      JettySolrRunner jetty = cluster.getJettySolrRunner(i);
      if (jetty.getNodeName().equals(overseerLeader)) {
        overseerLeaderIndex = i;
        break;
      }
    }
    // add a node
    JettySolrRunner node = cluster.startJettySolrRunner();
    if (!listener.onChangeLatch.await(10, TimeUnit.SECONDS)) {
      fail("onChange listener didn't execute on cluster change");
    }
    assertEquals(1, listener.addedNodes.size());
    assertEquals(node.getNodeName(), listener.addedNodes.iterator().next());
    // verify that a znode doesn't exist (no trigger)
    String pathAdded = ZkStateReader.SOLR_AUTOSCALING_NODE_ADDED_PATH + "/" + node.getNodeName();
    assertFalse("Path " + pathAdded + " was created but there are no nodeAdded triggers", zkClient().exists(pathAdded, true));
    listener.reset();
    // stop overseer
    log.info("====== KILL OVERSEER 1");
    cluster.stopJettySolrRunner(overseerLeaderIndex);
    if (!listener.onChangeLatch.await(10, TimeUnit.SECONDS)) {
      fail("onChange listener didn't execute on cluster change");
    }
    assertEquals(1, listener.lostNodes.size());
    assertEquals(overseerLeader, listener.lostNodes.iterator().next());
    assertEquals(0, listener.addedNodes.size());
    // wait until the new overseer is up
    Thread.sleep(5000);
    // verify that a znode does NOT exist - there's no nodeLost trigger,
    // so the new overseer cleaned up existing nodeLost markers
    String pathLost = ZkStateReader.SOLR_AUTOSCALING_NODE_LOST_PATH + "/" + overseerLeader;
    assertFalse("Path " + pathLost + " exists", zkClient().exists(pathLost, true));

    listener.reset();

    // set up triggers
    CloudSolrClient solrClient = cluster.getSolrClient();

    log.info("====== ADD TRIGGERS");
    String setTriggerCommand = "{" +
        "'set-trigger' : {" +
        "'name' : 'node_added_triggerMR'," +
        "'event' : 'nodeAdded'," +
        "'waitFor' : '1s'," +
        "'enabled' : true," +
        "'actions' : [{'name':'test','class':'" + TestEventMarkerAction.class.getName() + "'}]" +
        "}}";
    SolrRequest req = createAutoScalingRequest(SolrRequest.METHOD.POST, setTriggerCommand);
    NamedList<Object> response = solrClient.request(req);
    assertEquals(response.get("result").toString(), "success");

    setTriggerCommand = "{" +
        "'set-trigger' : {" +
        "'name' : 'node_lost_triggerMR'," +
        "'event' : 'nodeLost'," +
        "'waitFor' : '1s'," +
        "'enabled' : true," +
        "'actions' : [{'name':'test','class':'" + TestEventMarkerAction.class.getName() + "'}]" +
        "}}";
    req = createAutoScalingRequest(SolrRequest.METHOD.POST, setTriggerCommand);
    response = solrClient.request(req);
    assertEquals(response.get("result").toString(), "success");

    overSeerStatus = cluster.getSolrClient().request(CollectionAdminRequest.getOverseerStatus());
    overseerLeader = (String) overSeerStatus.get("leader");
    overseerLeaderIndex = 0;
    for (int i = 0; i < cluster.getJettySolrRunners().size(); i++) {
      JettySolrRunner jetty = cluster.getJettySolrRunner(i);
      if (jetty.getNodeName().equals(overseerLeader)) {
        overseerLeaderIndex = i;
        break;
      }
    }

    // create another node
    log.info("====== ADD NODE 1");
    JettySolrRunner node1 = cluster.startJettySolrRunner();
    if (!listener.onChangeLatch.await(10, TimeUnit.SECONDS)) {
      fail("onChange listener didn't execute on cluster change");
    }
    assertEquals(1, listener.addedNodes.size());
    assertEquals(node1.getNodeName(), listener.addedNodes.iterator().next());
    // verify that a znode exists
    pathAdded = ZkStateReader.SOLR_AUTOSCALING_NODE_ADDED_PATH + "/" + node1.getNodeName();
    assertTrue("Path " + pathAdded + " wasn't created", zkClient().exists(pathAdded, true));

    Thread.sleep(5000);
    // nodeAdded marker should be consumed now by nodeAdded trigger
    assertFalse("Path " + pathAdded + " should have been deleted", zkClient().exists(pathAdded, true));

    listener.reset();
    events.clear();
    triggerFiredLatch = new CountDownLatch(1);
    // kill overseer again
    log.info("====== KILL OVERSEER 2");
    cluster.stopJettySolrRunner(overseerLeaderIndex);
    if (!listener.onChangeLatch.await(10, TimeUnit.SECONDS)) {
      fail("onChange listener didn't execute on cluster change");
    }


    if (!triggerFiredLatch.await(20, TimeUnit.SECONDS)) {
      fail("Trigger should have fired by now");
    }
    assertEquals(1, events.size());
    TriggerEvent ev = events.iterator().next();
    List<String> nodeNames = (List<String>) ev.getProperty(TriggerEvent.NODE_NAMES);
    assertTrue(nodeNames.contains(overseerLeader));
    assertEquals(TriggerEventType.NODELOST, ev.getEventType());
  }

  private TestLiveNodesListener registerLiveNodesListener() {
    TestLiveNodesListener listener = new TestLiveNodesListener();
    zkStateReader.registerLiveNodesListener(listener);
    return listener;
  }

  private static class TestLiveNodesListener implements LiveNodesListener {
    Set<String> lostNodes = new HashSet<>();
    Set<String> addedNodes = new HashSet<>();
    CountDownLatch onChangeLatch = new CountDownLatch(1);

    public void reset() {
      lostNodes.clear();
      addedNodes.clear();
      onChangeLatch = new CountDownLatch(1);
    }

    @Override
    public void onChange(SortedSet<String> oldLiveNodes, SortedSet<String> newLiveNodes) {
      onChangeLatch.countDown();
      Set<String> old = new HashSet<>(oldLiveNodes);
      old.removeAll(newLiveNodes);
      if (!old.isEmpty()) {
        lostNodes.addAll(old);
      }
      newLiveNodes.removeAll(oldLiveNodes);
      if (!newLiveNodes.isEmpty()) {
        addedNodes.addAll(newLiveNodes);
      }
    }
  }

  public static class TestEventMarkerAction extends TriggerActionBase {

    public TestEventMarkerAction() {
      actionConstructorCalled.countDown();
    }

    @Override
    public void process(TriggerEvent event, ActionContext actionContext) {
      boolean locked = lock.tryLock();
      if (!locked) {
        log.info("We should never have a tryLock fail because actions are never supposed to be executed concurrently");
        return;
      }
      try {
        events.add(event);
        getTriggerFiredLatch().countDown();
      } catch (Throwable t) {
        log.debug("--throwable", t);
        throw t;
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void init(Map<String, String> args) {
      log.info("TestEventMarkerAction init");
      actionInitCalled.countDown();
      super.init(args);
    }
  }
}
