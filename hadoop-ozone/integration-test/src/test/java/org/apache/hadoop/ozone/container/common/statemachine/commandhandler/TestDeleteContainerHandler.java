/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.ozone.container.common.statemachine.commandhandler;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.client.ReplicationFactor;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.client.StandaloneReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.conf.StorageUnit;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.scm.ScmConfig;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.node.NodeManager;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.utils.IOUtils;
import org.apache.hadoop.hdds.utils.db.BatchOperation;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.ozone.HddsDatanodeService;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.OzoneTestUtils;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneClientFactory;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.container.common.helpers.BlockData;
import org.apache.hadoop.ozone.container.common.helpers.ContainerMetrics;
import org.apache.hadoop.ozone.container.common.impl.ContainerData;
import org.apache.hadoop.ozone.container.common.interfaces.Container;
import org.apache.hadoop.ozone.container.common.interfaces.DBHandle;
import org.apache.hadoop.ozone.container.common.statemachine.DatanodeConfiguration;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainerData;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueHandler;
import org.apache.hadoop.ozone.container.keyvalue.helpers.BlockUtils;
import org.apache.hadoop.ozone.om.helpers.OmKeyArgs;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.protocol.commands.CloseContainerCommand;
import org.apache.hadoop.ozone.protocol.commands.DeleteContainerCommand;
import org.apache.hadoop.ozone.protocol.commands.SCMCommand;
import org.apache.ozone.test.GenericTestUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.rules.Timeout;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor.ONE;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_DATANODE_RATIS_VOLUME_FREE_SPACE_MIN;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_CONTAINER_SIZE;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_BLOCK_DELETING_SERVICE_INTERVAL;

/**
 * Tests DeleteContainerCommand Handler.
 */
public class TestDeleteContainerHandler {

  private static OzoneClient client;
  /**
    * Set a timeout for each test.
    */
  @Rule
  public Timeout timeout = Timeout.seconds(300);


  private static MiniOzoneCluster cluster;
  private static OzoneConfiguration conf;
  private static ObjectStore objectStore;
  private static String volumeName = UUID.randomUUID().toString();
  private static String bucketName = UUID.randomUUID().toString();

  @BeforeClass
  public static void setup() throws Exception {
    conf = new OzoneConfiguration();
    conf.set(OZONE_SCM_CONTAINER_SIZE, "1GB");
    conf.setStorageSize(OZONE_DATANODE_RATIS_VOLUME_FREE_SPACE_MIN,
        0, StorageUnit.MB);
    conf.setBoolean(HddsConfigKeys.HDDS_SCM_SAFEMODE_PIPELINE_CREATION, false);

    conf.setTimeDuration(OZONE_BLOCK_DELETING_SERVICE_INTERVAL, 100,
        TimeUnit.MILLISECONDS);
    DatanodeConfiguration datanodeConfiguration = conf.getObject(
        DatanodeConfiguration.class);
    datanodeConfiguration.setBlockDeletionInterval(Duration.ofMillis(100));
    conf.setFromObject(datanodeConfiguration);
    ScmConfig scmConfig = conf.getObject(ScmConfig.class);
    scmConfig.setBlockDeletionInterval(Duration.ofMillis(100));
    conf.setFromObject(scmConfig);

    cluster = MiniOzoneCluster.newBuilder(conf)
        .setNumDatanodes(1).build();
    cluster.waitForClusterToBeReady();
    cluster.waitForPipelineTobeReady(ONE, 30000);

    client = OzoneClientFactory.getRpcClient(conf);
    objectStore = client.getObjectStore();
    objectStore.createVolume(volumeName);
    objectStore.getVolume(volumeName).createBucket(bucketName);
  }

  @AfterClass
  public static void shutdown() {
    IOUtils.closeQuietly(client);
    if (cluster != null) {
      try {
        cluster.shutdown();
      } catch (Exception e) {
        // do nothing.
      }
    }
  }

  @Test(timeout = 60000)
  public void testDeleteNonEmptyContainerDir()
      throws Exception {
    // 1. Test if a non force deletion fails if chunks are still present with
    //    block count set to 0
    // 2. Test if a force deletion passes even if chunks are still present

    //the easiest way to create an open container is creating a key

    String keyName = UUID.randomUUID().toString();

    // create key
    createKey(keyName);

    // get containerID of the key
    ContainerID containerId = getContainerID(keyName);

    // We need to close the container because delete container only happens
    // on closed containers when force flag is set to false.

    HddsDatanodeService hddsDatanodeService =
        cluster.getHddsDatanodes().get(0);

    Assert.assertFalse(isContainerClosed(hddsDatanodeService,
        containerId.getId()));

    DatanodeDetails datanodeDetails = hddsDatanodeService.getDatanodeDetails();

    NodeManager nodeManager =
        cluster.getStorageContainerManager().getScmNodeManager();
    //send the order to close the container
    OzoneTestUtils.closeAllContainers(cluster.getStorageContainerManager()
            .getEventQueue(), cluster.getStorageContainerManager());

    ContainerMetrics metrics =
        hddsDatanodeService
            .getDatanodeStateMachine().getContainer().getMetrics();
    long beforeDeleteFailedCount = metrics.getContainerDeleteFailedNonEmpty();
    GenericTestUtils.waitFor(() ->
            isContainerClosed(hddsDatanodeService, containerId.getId()),
        500, 5 * 1000);

    //double check if it's really closed (waitFor also throws an exception)
    Assert.assertTrue(isContainerClosed(hddsDatanodeService,
        containerId.getId()));

    // Delete key, which will make isEmpty flag to true in containerData
    objectStore.getVolume(volumeName)
        .getBucket(bucketName).deleteKey(keyName);

    // Ensure isEmpty flag is true when key is deleted and container is empty
    GenericTestUtils.waitFor(() -> getContainerfromDN(
        hddsDatanodeService, containerId.getId())
        .getContainerData().isEmpty(),
        500,
        5 * 2000);

    Container containerInternalObj =
        hddsDatanodeService.
            getDatanodeStateMachine().
            getContainer().getContainerSet().getContainer(containerId.getId());

    // Write a file to the container chunks directory indicating that there
    // might be a discrepancy between block count as recorded in RocksDB and
    // what is actually on disk.
    File lingeringBlock =
        new File(containerInternalObj.
            getContainerData().getChunksPath() + "/1.block");
    lingeringBlock.createNewFile();

    // Check container exists before sending delete container command
    Assert.assertFalse(isContainerDeleted(hddsDatanodeService,
        containerId.getId()));

    // Set container blockCount to 0 to mock that it is empty as per RocksDB
    getContainerfromDN(hddsDatanodeService, containerId.getId())
        .getContainerData().setBlockCount(0);

    // send delete container to the datanode
    SCMCommand<?> command = new DeleteContainerCommand(containerId.getId(),
        false);

    // Send the delete command. It should fail as even though block count
    // is zero there is a lingering block on disk.
    command.setTerm(
        cluster.getStorageContainerManager().getScmContext().getTermOfLeader());
    nodeManager.addDatanodeCommand(datanodeDetails.getUuid(), command);

    // Check the log for the error message when deleting non-empty containers
    GenericTestUtils.LogCapturer logCapturer =
        GenericTestUtils.LogCapturer.captureLogs(
            LoggerFactory.getLogger(KeyValueHandler.class));
    GenericTestUtils.waitFor(() ->
            logCapturer.getOutput().
                contains("Files still part of the container on delete"),
        500,
        5 * 2000);
    Assert.assertTrue(!isContainerDeleted(hddsDatanodeService,
        containerId.getId()));
    Assert.assertTrue(beforeDeleteFailedCount <
        metrics.getContainerDeleteFailedNonEmpty());
    // Send the delete command. It should pass with force flag.
    // Deleting a non-empty container should pass on the DN when the force flag
    // is true
    long beforeForceCount = metrics.getContainerForceDelete();
    command = new DeleteContainerCommand(containerId.getId(), true);

    command.setTerm(
        cluster.getStorageContainerManager().getScmContext().getTermOfLeader());
    nodeManager.addDatanodeCommand(datanodeDetails.getUuid(), command);

    GenericTestUtils.waitFor(() ->
            isContainerDeleted(hddsDatanodeService, containerId.getId()),
        500, 5 * 1000);
    Assert.assertTrue(isContainerDeleted(hddsDatanodeService,
        containerId.getId()));
    Assert.assertTrue(beforeForceCount <
        metrics.getContainerForceDelete());
  }

  @Test(timeout = 60000)
  public void testDeleteNonEmptyContainerBlockTable()
      throws Exception {
    // 1. Test if a non force deletion fails if chunks are still present with
    //    block count set to 0
    // 2. Test if a force deletion passes even if chunks are still present
    //the easiest way to create an open container is creating a key
    String keyName = UUID.randomUUID().toString();
    // create key
    createKey(keyName);
    // get containerID of the key
    ContainerID containerId = getContainerID(keyName);
    ContainerInfo container = cluster.getStorageContainerManager()
        .getContainerManager().getContainer(containerId);
    Pipeline pipeline = cluster.getStorageContainerManager()
        .getPipelineManager().getPipeline(container.getPipelineID());

    // We need to close the container because delete container only happens
    // on closed containers when force flag is set to false.

    HddsDatanodeService hddsDatanodeService =
        cluster.getHddsDatanodes().get(0);

    Assert.assertFalse(isContainerClosed(hddsDatanodeService,
        containerId.getId()));

    DatanodeDetails datanodeDetails = hddsDatanodeService.getDatanodeDetails();

    NodeManager nodeManager =
        cluster.getStorageContainerManager().getScmNodeManager();
    //send the order to close the container
    SCMCommand<?> command = new CloseContainerCommand(
        containerId.getId(), pipeline.getId());
    command.setTerm(
        cluster.getStorageContainerManager().getScmContext().getTermOfLeader());
    nodeManager.addDatanodeCommand(datanodeDetails.getUuid(), command);

    Container containerInternalObj =
        hddsDatanodeService.
            getDatanodeStateMachine().
            getContainer().getContainerSet().getContainer(containerId.getId());

    // Write a file to the container chunks directory indicating that there
    // might be a discrepancy between block count as recorded in RocksDB and
    // what is actually on disk.
    File lingeringBlock =
        new File(containerInternalObj.
            getContainerData().getChunksPath() + "/1.block");
    lingeringBlock.createNewFile();
    ContainerMetrics metrics =
        hddsDatanodeService
            .getDatanodeStateMachine().getContainer().getMetrics();
    GenericTestUtils.waitFor(() ->
            isContainerClosed(hddsDatanodeService, containerId.getId()),
        500, 5 * 1000);

    //double check if it's really closed (waitFor also throws an exception)
    Assert.assertTrue(isContainerClosed(hddsDatanodeService,
        containerId.getId()));

    // Check container exists before sending delete container command
    Assert.assertFalse(isContainerDeleted(hddsDatanodeService,
        containerId.getId()));

    long containerDeleteFailedNonEmptyBlockDB =
        metrics.getContainerDeleteFailedNonEmpty();
    // send delete container to the datanode
    command = new DeleteContainerCommand(containerId.getId(), false);

    // Send the delete command. It should fail as even though isEmpty
    // flag is true, there is a lingering block on disk.
    command.setTerm(
        cluster.getStorageContainerManager().getScmContext().getTermOfLeader());
    nodeManager.addDatanodeCommand(datanodeDetails.getUuid(), command);


    // Check the log for the error message when deleting non-empty containers
    GenericTestUtils.LogCapturer logCapturer =
        GenericTestUtils.LogCapturer.captureLogs(
            LoggerFactory.getLogger(KeyValueHandler.class));
    GenericTestUtils.waitFor(() ->
            logCapturer.getOutput().
                contains("the container is not empty with blockCount"),
        500,
        5 * 2000);
    Assert.assertTrue(!isContainerDeleted(hddsDatanodeService,
        containerId.getId()));
    Assert.assertTrue(containerDeleteFailedNonEmptyBlockDB <
        metrics.getContainerDeleteFailedNonEmpty());

    // Now empty the container Dir and try with a non-empty block table
    Container containerToDelete = getContainerfromDN(
        hddsDatanodeService, containerId.getId());
    File chunkDir = new File(containerToDelete.
        getContainerData().getChunksPath());
    File[] files = chunkDir.listFiles();
    if (files != null) {
      for (File file : files) {
        FileUtils.delete(file);
      }
    }

    command = new DeleteContainerCommand(containerId.getId(), false);

    // Send the delete command.It should fail as still block table is non-empty
    command.setTerm(
        cluster.getStorageContainerManager().getScmContext().getTermOfLeader());
    nodeManager.addDatanodeCommand(datanodeDetails.getUuid(), command);
    Thread.sleep(5000);
    Assert.assertTrue(!isContainerDeleted(hddsDatanodeService,
        containerId.getId()));
    // Send the delete command. It should pass with force flag.
    long beforeForceCount = metrics.getContainerForceDelete();
    command = new DeleteContainerCommand(containerId.getId(), true);

    command.setTerm(
        cluster.getStorageContainerManager().getScmContext().getTermOfLeader());
    nodeManager.addDatanodeCommand(datanodeDetails.getUuid(), command);

    GenericTestUtils.waitFor(() ->
            isContainerDeleted(hddsDatanodeService, containerId.getId()),
        500, 5 * 1000);
    Assert.assertTrue(isContainerDeleted(hddsDatanodeService,
        containerId.getId()));
    Assert.assertTrue(beforeForceCount <
        metrics.getContainerForceDelete());
  }

  @Test(timeout = 60000)
  public void testContainerDeleteWithInvalidBlockCount()
      throws Exception {
    String keyName = UUID.randomUUID().toString();
    // create key
    createKey(keyName);
    // get containerID of the key
    ContainerID containerId = getContainerID(keyName);
    ContainerInfo container = cluster.getStorageContainerManager()
        .getContainerManager().getContainer(containerId);
    Pipeline pipeline = cluster.getStorageContainerManager()
        .getPipelineManager().getPipeline(container.getPipelineID());

    // We need to close the container because delete container only happens
    // on closed containers when force flag is set to false.
    HddsDatanodeService hddsDatanodeService =
        cluster.getHddsDatanodes().get(0);

    Assert.assertFalse(isContainerClosed(hddsDatanodeService,
        containerId.getId()));

    DatanodeDetails datanodeDetails = hddsDatanodeService.getDatanodeDetails();
    NodeManager nodeManager =
        cluster.getStorageContainerManager().getScmNodeManager();
    //send the order to close the container
    SCMCommand<?> command = new CloseContainerCommand(
        containerId.getId(), pipeline.getId());
    command.setTerm(
        cluster.getStorageContainerManager().getScmContext().getTermOfLeader());
    nodeManager.addDatanodeCommand(datanodeDetails.getUuid(), command);

    GenericTestUtils.waitFor(() ->
            isContainerClosed(hddsDatanodeService, containerId.getId()),
        500, 5 * 1000);

    //double check if it's really closed (waitFor also throws an exception)
    Assert.assertTrue(isContainerClosed(hddsDatanodeService,
        containerId.getId()));

    // Check container exists before sending delete container command
    Assert.assertFalse(isContainerDeleted(hddsDatanodeService,
        containerId.getId()));

    // Clear block table
    clearBlocksTable(getContainerfromDN(hddsDatanodeService,
        containerId.getId()));


    // Now empty the container Dir
    Container containerToDelete = getContainerfromDN(
        hddsDatanodeService, containerId.getId());
    File chunkDir = new File(containerToDelete.
        getContainerData().getChunksPath());
    File[] files = chunkDir.listFiles();
    if (files != null) {
      for (File file : files) {
        FileUtils.delete(file);
      }
    }

    // send delete container to the datanode, blockCount is still 1(Invalid)
    command = new DeleteContainerCommand(containerId.getId(), false);

    // Send the delete command. It should succeed as even though blockCount
    // is non-zero(Invalid).
    command.setTerm(
        cluster.getStorageContainerManager().getScmContext().getTermOfLeader());
    nodeManager.addDatanodeCommand(datanodeDetails.getUuid(), command);

    GenericTestUtils.waitFor(() ->
            isContainerDeleted(hddsDatanodeService, containerId.getId()),
        500, 5 * 1000);
    Assert.assertTrue(isContainerDeleted(hddsDatanodeService,
        containerId.getId()));

  }


  private void clearBlocksTable(Container container) throws IOException {
    try (DBHandle dbHandle
             = BlockUtils.getDB(
        (KeyValueContainerData) container.getContainerData(),
        conf)) {
      List<? extends Table.KeyValue<String, BlockData>>
          blocks = dbHandle.getStore().getBlockDataTable().getRangeKVs(
          ((KeyValueContainerData) container.getContainerData()).
              startKeyEmpty(),
          Integer.MAX_VALUE,
          ((KeyValueContainerData) container.getContainerData()).
              containerPrefix(),
          ((KeyValueContainerData) container.getContainerData()).
              getUnprefixedKeyFilter());
      try (BatchOperation batch = dbHandle.getStore().getBatchHandler()
          .initBatchOperation()) {
        for (Table.KeyValue<String, BlockData> kv : blocks) {
          String blk = kv.getKey();
          dbHandle.getStore().getBlockDataTable().deleteWithBatch(batch, blk);
        }
        dbHandle.getStore().getBatchHandler().commitBatchOperation(batch);
      }
    }
  }

  @Test(timeout = 60000)
  public void testDeleteContainerRequestHandlerOnClosedContainer()
      throws Exception {

    //the easiest way to create an open container is creating a key

    String keyName = UUID.randomUUID().toString();

    // create key
    createKey(keyName);

    // get containerID of the key
    ContainerID containerId = getContainerID(keyName);

    // We need to close the container because delete container only happens
    // on closed containers when force flag is set to false.

    HddsDatanodeService hddsDatanodeService =
        cluster.getHddsDatanodes().get(0);

    Assert.assertFalse(isContainerClosed(hddsDatanodeService,
        containerId.getId()));

    DatanodeDetails datanodeDetails = hddsDatanodeService.getDatanodeDetails();

    NodeManager nodeManager =
        cluster.getStorageContainerManager().getScmNodeManager();


    //send the order to close the container
    OzoneTestUtils.closeAllContainers(cluster.getStorageContainerManager()
        .getEventQueue(), cluster.getStorageContainerManager());

    GenericTestUtils.waitFor(() ->
            isContainerClosed(hddsDatanodeService, containerId.getId()),
        500, 5 * 1000);

    //double check if it's really closed (waitFor also throws an exception)
    Assert.assertTrue(isContainerClosed(hddsDatanodeService,
        containerId.getId()));

    // Check container exists before sending delete container command
    Assert.assertFalse(isContainerDeleted(hddsDatanodeService,
        containerId.getId()));

    // send delete container to the datanode
    SCMCommand<?> command = new DeleteContainerCommand(containerId.getId(),
        false);
    command.setTerm(
        cluster.getStorageContainerManager().getScmContext().getTermOfLeader());
    nodeManager.addDatanodeCommand(datanodeDetails.getUuid(), command);

    // Deleting a non-empty container should fail on DN when the force flag
    // is false.
    // Check the log for the error message when deleting non-empty containers
    GenericTestUtils.LogCapturer logCapturer =
        GenericTestUtils.LogCapturer.captureLogs(
            LoggerFactory.getLogger(DeleteContainerCommandHandler.class));
    GenericTestUtils.waitFor(() -> logCapturer.getOutput().contains("Non" +
            "-force deletion of non-empty container is not allowed"), 500,
        5 * 1000);
    ContainerMetrics metrics =
        hddsDatanodeService
            .getDatanodeStateMachine().getContainer().getMetrics();
    Assert.assertEquals(1,
        metrics.getContainerDeleteFailedNonEmpty());

    // Delete key, which will make isEmpty flag to true in containerData
    objectStore.getVolume(volumeName)
        .getBucket(bucketName).deleteKey(keyName);

    // Ensure isEmpty flag is true when key is deleted
    GenericTestUtils.waitFor(() -> getContainerfromDN(
            hddsDatanodeService, containerId.getId())
            .getContainerData().isEmpty(),
        500, 5 * 2000);

    // Send the delete command again. It should succeed this time.
    command.setTerm(
        cluster.getStorageContainerManager().getScmContext().getTermOfLeader());
    nodeManager.addDatanodeCommand(datanodeDetails.getUuid(), command);

    GenericTestUtils.waitFor(() ->
            isContainerDeleted(hddsDatanodeService, containerId.getId()),
        500, 5 * 1000);

    Assert.assertTrue(isContainerDeleted(hddsDatanodeService,
        containerId.getId()));
  }

  @Test
  public void testDeleteContainerRequestHandlerOnOpenContainer()
      throws Exception {

    //the easiest way to create an open container is creating a key
    String keyName = UUID.randomUUID().toString();

    // create key
    createKey(keyName);

    // get containerID of the key
    ContainerID containerId = getContainerID(keyName);

    HddsDatanodeService hddsDatanodeService =
        cluster.getHddsDatanodes().get(0);
    DatanodeDetails datanodeDetails =
        hddsDatanodeService.getDatanodeDetails();

    NodeManager nodeManager =
        cluster.getStorageContainerManager().getScmNodeManager();

    // Send delete container command with force flag set to false.
    SCMCommand<?> command = new DeleteContainerCommand(
        containerId.getId(), false);
    command.setTerm(
        cluster.getStorageContainerManager().getScmContext().getTermOfLeader());
    nodeManager.addDatanodeCommand(datanodeDetails.getUuid(), command);

    // Here it should not delete it, and the container should exist in the
    // containerset
    int count = 1;
    // Checking for 5 seconds, whether it is containerSet, as after command
    // is issued, giving some time for it to process.
    while (!isContainerDeleted(hddsDatanodeService, containerId.getId())) {
      Thread.sleep(1000);
      count++;
      if (count == 5) {
        break;
      }
    }

    Assert.assertFalse(isContainerDeleted(hddsDatanodeService,
        containerId.getId()));


    // Now delete container with force flag set to true. now it should delete
    // container
    command = new DeleteContainerCommand(containerId.getId(), true);
    command.setTerm(
        cluster.getStorageContainerManager().getScmContext().getTermOfLeader());
    nodeManager.addDatanodeCommand(datanodeDetails.getUuid(), command);

    GenericTestUtils.waitFor(() ->
            isContainerDeleted(hddsDatanodeService, containerId.getId()),
        500, 5 * 1000);

    Assert.assertTrue(isContainerDeleted(hddsDatanodeService,
        containerId.getId()));

  }

  /**
   * create a key with specified name.
   * @param keyName
   * @throws IOException
   */
  private void createKey(String keyName) throws IOException {
    OzoneOutputStream key = objectStore.getVolume(volumeName)
        .getBucket(bucketName)
        .createKey(keyName, 1024, ReplicationType.RATIS,
            ReplicationFactor.ONE, new HashMap<>());
    key.write("test".getBytes(UTF_8));
    key.close();
  }

  /**
   * Return containerID of the key.
   * @param keyName
   * @return ContainerID
   * @throws IOException
   */
  private ContainerID getContainerID(String keyName) throws IOException {
    OmKeyArgs keyArgs =
        new OmKeyArgs.Builder().setVolumeName(volumeName)
            .setBucketName(bucketName)
            .setReplicationConfig(StandaloneReplicationConfig.getInstance(ONE))
            .setKeyName(keyName)
            .build();

    OmKeyLocationInfo omKeyLocationInfo =
        cluster.getOzoneManager().lookupKey(keyArgs).getKeyLocationVersions()
            .get(0).getBlocksLatestVersionOnly().get(0);

    return ContainerID.valueOf(
        omKeyLocationInfo.getContainerID());
  }

  /**
   * Checks whether is closed or not on a datanode.
   * @param hddsDatanodeService
   * @param containerID
   * @return true - if container is closes, else returns false.
   */
  private Boolean isContainerClosed(HddsDatanodeService hddsDatanodeService,
      long containerID) {
    ContainerData containerData;
    containerData = getContainerfromDN(hddsDatanodeService, containerID)
        .getContainerData();
    return !containerData.isOpen();
  }

  /**
   * Checks whether container is deleted from the datanode or not.
   * @param hddsDatanodeService
   * @param containerID
   * @return true - if container is deleted, else returns false
   */
  private Boolean isContainerDeleted(HddsDatanodeService hddsDatanodeService,
      long containerID) {
    Container container;
    // if container is not in container set, it means container got deleted.
    container = getContainerfromDN(hddsDatanodeService, containerID);
    return container == null;
  }

  /**
   * Return the container for the given containerID from the given DN.
   */
  private Container getContainerfromDN(HddsDatanodeService hddsDatanodeService,
      long containerID) {
    return hddsDatanodeService.getDatanodeStateMachine().getContainer()
        .getContainerSet().getContainer(containerID);
  }
}
