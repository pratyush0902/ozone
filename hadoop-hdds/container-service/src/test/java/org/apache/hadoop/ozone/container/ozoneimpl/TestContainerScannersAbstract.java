/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.ozone.container.ozoneimpl;

import org.apache.hadoop.ozone.container.common.ContainerTestUtils;
import org.apache.hadoop.ozone.container.common.impl.ContainerData;
import org.apache.hadoop.ozone.container.common.interfaces.Container;
import org.apache.hadoop.ozone.container.common.volume.HddsVolume;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.verification.VerificationMode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.hadoop.hdds.conf.OzoneConfiguration.newInstanceOf;
import static org.apache.hadoop.ozone.container.ozoneimpl.ContainerScannerConfiguration.CONTAINER_SCAN_MIN_GAP_DEFAULT;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * General testing guidelines for the various container scanners whose tests
 * subclass this one.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings("checkstyle:VisibilityModifier")
public abstract class TestContainerScannersAbstract {

  private static final AtomicLong CONTAINER_SEQ_ID = new AtomicLong(100);

  @Mock
  protected Container<ContainerData> healthy;

  @Mock
  protected Container<ContainerData> openContainer;

  @Mock
  protected Container<ContainerData> openCorruptMetadata;

  @Mock
  protected Container<ContainerData> corruptData;

  @Mock
  protected HddsVolume vol;

  protected ContainerScannerConfiguration conf;
  protected ContainerController controller;

  public void setup() {
    conf = newInstanceOf(ContainerScannerConfiguration.class);
    conf.setMetadataScanInterval(0);
    conf.setDataScanInterval(0);
    conf.setEnabled(true);
    controller = mockContainerController();
  }

  // ALL SCANNERS SHOULD TEST THESE THINGS

  @Test
  public abstract void testRecentlyScannedContainerIsSkipped() throws Exception;

  @Test
  public abstract void testPreviouslyScannedContainerIsScanned()
      throws Exception;

  @Test
  public abstract void testUnscannedContainerIsScanned() throws Exception;

  @Test
  public abstract void testUnhealthyContainersDetected() throws Exception;

  @Test
  public abstract void testScannerMetrics() throws Exception;

  @Test
  public abstract void testScannerMetricsUnregisters() throws Exception;

  @Test
  public abstract void testWithVolumeFailure() throws Exception;

  // HELPER METHODS

  protected void setScannedTimestampOld(Container<ContainerData> container) {
    // If the last scan time is before than the configured gap, the container
    // should be scanned.
    Instant oldLastScanTime = Instant.now()
        .minus(CONTAINER_SCAN_MIN_GAP_DEFAULT, ChronoUnit.MILLIS)
        .minus(10, ChronoUnit.MINUTES);
    Mockito.when(container.getContainerData().lastDataScanTime())
        .thenReturn(Optional.of(oldLastScanTime));
  }

  protected void setScannedTimestampRecent(Container<ContainerData> container) {
    // If the last scan time is within the configured gap, the container
    // should be skipped.
    Instant recentLastScanTime = Instant.now()
        .minus(CONTAINER_SCAN_MIN_GAP_DEFAULT, ChronoUnit.MILLIS)
        .plus(1, ChronoUnit.MINUTES);
    Mockito.when(container.getContainerData().lastDataScanTime())
        .thenReturn(Optional.of(recentLastScanTime));
  }

  protected void verifyContainerMarkedUnhealthy(
      Container<ContainerData> container, VerificationMode invocationTimes)
      throws Exception {
    Mockito.verify(controller, invocationTimes).markContainerUnhealthy(
        container.getContainerData().getContainerID());
  }

  private ContainerController mockContainerController() {
    // healthy container
    ContainerTestUtils.setupMockContainer(healthy,
        true, true, true, CONTAINER_SEQ_ID, vol);

    // Open container (only metadata can be scanned)
    ContainerTestUtils.setupMockContainer(openContainer,
        false, true, false, CONTAINER_SEQ_ID, vol);

    // unhealthy container (corrupt data)
    ContainerTestUtils.setupMockContainer(corruptData,
        true, true, false, CONTAINER_SEQ_ID, vol);

    // unhealthy container (corrupt metadata). To simulate container still
    // being open while metadata is corrupted, shouldScanData will return false.
    ContainerTestUtils.setupMockContainer(openCorruptMetadata,
        false, false, false, CONTAINER_SEQ_ID, vol);

    Collection<Container<?>> containers = Arrays.asList(
        healthy, corruptData, openCorruptMetadata);
    ContainerController mock = mock(ContainerController.class);
    when(mock.getContainers(vol)).thenReturn(containers.iterator());
    when(mock.getContainers()).thenReturn(containers);

    return mock;
  }
}
