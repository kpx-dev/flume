/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.flume.sink.hdfs;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.flume.Clock;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.instrumentation.SinkCounter;
import org.apache.flume.sink.FlumeFormatter;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import java.io.File;

public class TestBucketWriter {

  private static Logger logger =
      LoggerFactory.getLogger(TestBucketWriter.class);
  private Context ctx = new Context();

  private static ScheduledExecutorService timedRollerPool;

  @BeforeClass
  public static void setup() {
    timedRollerPool = Executors.newSingleThreadScheduledExecutor();
  }

  @AfterClass
  public static void teardown() throws InterruptedException {
    timedRollerPool.shutdown();
    timedRollerPool.awaitTermination(2, TimeUnit.SECONDS);
    timedRollerPool.shutdownNow();
  }

  @Test
  public void testEventCountingRoller() throws IOException, InterruptedException {
    int maxEvents = 100;
    MockHDFSWriter hdfsWriter = new MockHDFSWriter();
    HDFSTextFormatter formatter = new HDFSTextFormatter();
    BucketWriter bucketWriter = new BucketWriter(0, 0, maxEvents, 0, ctx,
        "/tmp", "file", "", ".tmp", null, null, SequenceFile.CompressionType.NONE,
        hdfsWriter, formatter, timedRollerPool, null,
        new SinkCounter("test-bucket-writer-" + System.currentTimeMillis()), 0,
        null, null);

    Event e = EventBuilder.withBody("foo", Charsets.UTF_8);
    for (int i = 0; i < 1000; i++) {
      bucketWriter.append(e);
    }

    logger.info("Number of events written: {}", hdfsWriter.getEventsWritten());
    logger.info("Number of bytes written: {}", hdfsWriter.getBytesWritten());
    logger.info("Number of files opened: {}", hdfsWriter.getFilesOpened());

    Assert.assertEquals("events written", 1000, hdfsWriter.getEventsWritten());
    Assert.assertEquals("bytes written", 3000, hdfsWriter.getBytesWritten());
    Assert.assertEquals("files opened", 10, hdfsWriter.getFilesOpened());
  }

  @Test
  public void testSizeRoller() throws IOException, InterruptedException {
    int maxBytes = 300;
    MockHDFSWriter hdfsWriter = new MockHDFSWriter();
    HDFSTextFormatter formatter = new HDFSTextFormatter();
    BucketWriter bucketWriter = new BucketWriter(0, maxBytes, 0, 0, ctx,
        "/tmp", "file", "", ".tmp", null, null, SequenceFile.CompressionType.NONE,
        hdfsWriter, formatter, timedRollerPool, null,
        new SinkCounter("test-bucket-writer-" + System.currentTimeMillis()),
        0, null, null);

    Event e = EventBuilder.withBody("foo", Charsets.UTF_8);
    for (int i = 0; i < 1000; i++) {
      bucketWriter.append(e);
    }

    logger.info("Number of events written: {}", hdfsWriter.getEventsWritten());
    logger.info("Number of bytes written: {}", hdfsWriter.getBytesWritten());
    logger.info("Number of files opened: {}", hdfsWriter.getFilesOpened());

    Assert.assertEquals("events written", 1000, hdfsWriter.getEventsWritten());
    Assert.assertEquals("bytes written", 3000, hdfsWriter.getBytesWritten());
    Assert.assertEquals("files opened", 10, hdfsWriter.getFilesOpened());
  }

  @Test
  public void testIntervalRoller() throws IOException, InterruptedException {
    final int ROLL_INTERVAL = 1; // seconds
    final int NUM_EVENTS = 10;

    MockHDFSWriter hdfsWriter = new MockHDFSWriter();
    HDFSTextFormatter formatter = new HDFSTextFormatter();
    BucketWriter bucketWriter = new BucketWriter(ROLL_INTERVAL, 0, 0, 0, ctx,
        "/tmp", "file", "", ".tmp", null, null, SequenceFile.CompressionType.NONE,
        hdfsWriter, formatter, timedRollerPool, null,
        new SinkCounter("test-bucket-writer-" + System.currentTimeMillis()),
        0, null, null);

    Event e = EventBuilder.withBody("foo", Charsets.UTF_8);
    long startNanos = System.nanoTime();
    for (int i = 0; i < NUM_EVENTS - 1; i++) {
      bucketWriter.append(e);
    }

    // sleep to force a roll... wait 2x interval just to be sure
    Thread.sleep(2 * ROLL_INTERVAL * 1000L);

    // write one more event (to reopen a new file so we will roll again later)
    bucketWriter.append(e);

    long elapsedMillis = TimeUnit.MILLISECONDS.convert(
        System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    long elapsedSeconds = elapsedMillis / 1000L;

    logger.info("Time elapsed: {} milliseconds", elapsedMillis);
    logger.info("Number of events written: {}", hdfsWriter.getEventsWritten());
    logger.info("Number of bytes written: {}", hdfsWriter.getBytesWritten());
    logger.info("Number of files opened: {}", hdfsWriter.getFilesOpened());
    logger.info("Number of files closed: {}", hdfsWriter.getFilesClosed());

    Assert.assertEquals("events written", NUM_EVENTS,
        hdfsWriter.getEventsWritten());
    Assert.assertEquals("bytes written", e.getBody().length * NUM_EVENTS,
        hdfsWriter.getBytesWritten());
    Assert.assertEquals("files opened", 2, hdfsWriter.getFilesOpened());

    // before auto-roll
    Assert.assertEquals("files closed", 1, hdfsWriter.getFilesClosed());

    logger.info("Waiting for roll...");
    Thread.sleep(2 * ROLL_INTERVAL * 1000L);

    logger.info("Number of files closed: {}", hdfsWriter.getFilesClosed());
    Assert.assertEquals("files closed", 2, hdfsWriter.getFilesClosed());
  }

  @Test
  public void testIntervalRollerBug() throws IOException, InterruptedException {
    final int ROLL_INTERVAL = 1; // seconds
    final int NUM_EVENTS = 10;

    HDFSWriter hdfsWriter = new HDFSWriter() {
      private volatile boolean open = false;

      public void configure(Context context) {

      }

      public void sync() throws IOException {
        if(!open) {
          throw new IOException("closed");
        }
      }

      public void open(String filePath, CompressionCodec codec,
          CompressionType cType, FlumeFormatter fmt) throws IOException {
        open = true;
      }

      public void open(String filePath, FlumeFormatter fmt) throws IOException {
        open = true;
      }

      public void close() throws IOException {
        open = false;
      }

      public void append(Event e, FlumeFormatter fmt) throws IOException {
        // we just re-open in append if closed
        open = true;
      }
    };
    HDFSTextFormatter formatter = new HDFSTextFormatter();
    File tmpFile = File.createTempFile("flume", "test");
    tmpFile.deleteOnExit();
    String path = tmpFile.getParent();
    String name = tmpFile.getName();

    BucketWriter bucketWriter = new BucketWriter(ROLL_INTERVAL, 0, 0, 0, ctx,
        path, name, "", ".tmp", null, null, SequenceFile.CompressionType.NONE, hdfsWriter,
        formatter, timedRollerPool, null,
        new SinkCounter("test-bucket-writer-" + System.currentTimeMillis()),
        0, null, null);

    Event e = EventBuilder.withBody("foo", Charsets.UTF_8);
    for (int i = 0; i < NUM_EVENTS - 1; i++) {
      bucketWriter.append(e);
    }

    // sleep to force a roll... wait 2x interval just to be sure
    Thread.sleep(2 * ROLL_INTERVAL * 1000L);

    bucketWriter.flush(); // throws closed exception
  }

  @Test
  public void testFileSuffixNotGiven() throws IOException, InterruptedException {
      final int ROLL_INTERVAL = 1000; // seconds. Make sure it doesn't change in course of test
      final String suffix = null;

      MockHDFSWriter hdfsWriter = new MockHDFSWriter();
      HDFSTextFormatter formatter = new HDFSTextFormatter();
      BucketWriter bucketWriter = new BucketWriter(ROLL_INTERVAL, 0, 0, 0, ctx,
          "/tmp", "file", "", ".tmp", suffix, null, SequenceFile.CompressionType.NONE, hdfsWriter,
          formatter, timedRollerPool, null,
          new SinkCounter("test-bucket-writer-" + System.currentTimeMillis()),
          0, null, null);

      // Need to override system time use for test so we know what to expect
      final long testTime = System.currentTimeMillis();
      Clock testClock = new Clock() {
          public long currentTimeMillis() {
              return testTime;
          }
      };
      bucketWriter.setClock(testClock);

      Event e = EventBuilder.withBody("foo", Charsets.UTF_8);
      bucketWriter.append(e);

      Assert.assertTrue("Incorrect suffix", hdfsWriter.getOpenedFilePath().endsWith(Long.toString(testTime+1) + ".tmp"));
  }

    @Test
    public void testFileSuffixGiven() throws IOException, InterruptedException {
        final int ROLL_INTERVAL = 1000; // seconds. Make sure it doesn't change in course of test
        final String suffix = ".avro";

        MockHDFSWriter hdfsWriter = new MockHDFSWriter();
        HDFSTextFormatter formatter = new HDFSTextFormatter();
        BucketWriter bucketWriter = new BucketWriter(ROLL_INTERVAL, 0, 0, 0, ctx,
            "/tmp", "file", "", ".tmp", suffix, null, SequenceFile.CompressionType.NONE, hdfsWriter,
            formatter, timedRollerPool, null,
            new SinkCounter("test-bucket-writer-" + System.currentTimeMillis()),
            0, null, null);

        // Need to override system time use for test so we know what to expect

        final long testTime = System.currentTimeMillis();

        Clock testClock = new Clock() {
            public long currentTimeMillis() {
                return testTime;
            }
        };
        bucketWriter.setClock(testClock);

        Event e = EventBuilder.withBody("foo", Charsets.UTF_8);
        bucketWriter.append(e);

        Assert.assertTrue("Incorrect suffix", hdfsWriter.getOpenedFilePath().endsWith(Long.toString(testTime+1) + suffix + ".tmp"));
    }

  @Test
  public void testInUsePrefix() throws IOException, InterruptedException {
    final int ROLL_INTERVAL = 1000; // seconds. Make sure it doesn't change in course of test
    final String PREFIX = "BRNO_IS_CITY_IN_CZECH_REPUBLIC";

    MockHDFSWriter hdfsWriter = new MockHDFSWriter();
    HDFSTextFormatter formatter = new HDFSTextFormatter();
    BucketWriter bucketWriter = new BucketWriter(ROLL_INTERVAL, 0, 0, 0, ctx,
        "/tmp", "file", PREFIX, ".tmp", null, null, SequenceFile.CompressionType.NONE, hdfsWriter,
        formatter, timedRollerPool, null,
        new SinkCounter("test-bucket-writer-" + System.currentTimeMillis()),
        0, null, null);

    Event e = EventBuilder.withBody("foo", Charsets.UTF_8);
    bucketWriter.append(e);

    Assert.assertTrue("Incorrect in use prefix", hdfsWriter.getOpenedFilePath().contains(PREFIX));
  }

  @Test
  public void testInUseSuffix() throws IOException, InterruptedException {
    final int ROLL_INTERVAL = 1000; // seconds. Make sure it doesn't change in course of test
    final String SUFFIX = "WELCOME_TO_THE_HELLMOUNTH";

    MockHDFSWriter hdfsWriter = new MockHDFSWriter();
    HDFSTextFormatter formatter = new HDFSTextFormatter();
    BucketWriter bucketWriter = new BucketWriter(ROLL_INTERVAL, 0, 0, 0, ctx,
        "/tmp", "file", "", SUFFIX, null, null, SequenceFile.CompressionType.NONE, hdfsWriter,
        formatter, timedRollerPool, null,
        new SinkCounter("test-bucket-writer-" + System.currentTimeMillis()),
        0, null, null);

    Event e = EventBuilder.withBody("foo", Charsets.UTF_8);
    bucketWriter.append(e);

    Assert.assertTrue("Incorrect in use suffix", hdfsWriter.getOpenedFilePath().contains(SUFFIX));
  }

}
