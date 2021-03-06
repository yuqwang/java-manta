/*
 * Copyright (c) 2015-2020, Joyent, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.joyent.manta.client.jobs;

import com.joyent.manta.client.MantaClient;
import com.joyent.manta.config.ConfigContext;
import com.joyent.manta.config.IntegrationTestConfigContext;
import com.joyent.manta.exception.MantaClientHttpResponseException;
import com.joyent.test.util.ThreeTriesRetryAnalyzer;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.joyent.manta.exception.MantaErrorCode.RESOURCE_NOT_FOUND_ERROR;

/**
 * Tests the execution of Manta compute jobs.
 *
 * <p>
 * Since we want to make it possible to run this test without a code change, this test throws a {@link
 * org.testng.SkipException} if jobs are disabled for the "manta.user" account, the method
 * {@link MantaClientJobIT#createJobAndSkipIfUnsupported(MantaJob)} will skip this integration-test from
 * running.
 * </p>
 *
 * @author <a href="https://github.com/nairashwin952013">Ashwin A Nair</a>
 */
@Test(groups = { "expensive" }, retryAnalyzer = ThreeTriesRetryAnalyzer.class)
public class MantaClientJobIT {
    private static final Logger LOG = LoggerFactory.getLogger(MantaClientJobIT.class);

    private static final String TEST_DATA = "EPISODEII_IS_BEST_EPISODE";

    private String testPathPrefix;

    private AtomicInteger count = new AtomicInteger(0);

    private MantaClient mantaClient;


    @BeforeClass
    public void beforeClass() throws IOException {

        // Let TestNG configuration take precedence over environment variables
        ConfigContext config = new IntegrationTestConfigContext();

        mantaClient = new MantaClient(config);

        testPathPrefix = IntegrationTestConfigContext.generateBasePath(config, this.getClass().getSimpleName());
        mantaClient.putDirectory(testPathPrefix, true);
    }

    @AfterClass
    public void cleanup() throws IOException {
        IntegrationTestConfigContext.cleanupTestDirectory(mantaClient, testPathPrefix);
    }

    @Test(groups = { "expensive" })
    public void createJob() throws IOException {
        MantaJob job = buildJob();
        UUID jobId = createJobAndSkipIfUnsupported(job);
        Assert.assertNotNull(jobId);

        boolean accepted = mantaClient.cancelJob(jobId);
        Assert.assertTrue(accepted, "Cancel request was not accepted");
    }

    @Test(groups = { "expensive" }, dependsOnMethods = { "createJob" })
    public void getJob() throws IOException {
        MantaJob job = buildJob();
        UUID jobId = createJobAndSkipIfUnsupported(job);
        MantaJob jobResponse = mantaClient.getJob(jobId);

        Assert.assertEquals(jobResponse.getName(),
                job.getName());
        MantaJobPhase mapPhaseRequest = job.getMapPhases().get(0);
        MantaJobPhase mapPhaseResponse = jobResponse.getMapPhases().get(0);

        Assert.assertEquals(mapPhaseResponse.getType(),
                mapPhaseRequest.getType());
        Assert.assertEquals(mapPhaseResponse.getExec(),
                mapPhaseRequest.getExec());

        Assert.assertFalse(jobResponse.getCancelled());
        Assert.assertFalse(jobResponse.getInputDone());
        Assert.assertNotNull(jobResponse.getTimeCreated());
        Assert.assertNull(jobResponse.getTimeDone());

        mantaClient.cancelJob(jobId);
    }

    @Test(dependsOnMethods = { "createJob", "getJob" })
    public void cancelJob() throws IOException {
        MantaJob job = buildJob();
        UUID jobId = createJobAndSkipIfUnsupported(job);
        mantaClient.cancelJob(jobId);

        MantaJob jobResponse = mantaClient.getJob(jobId);

        Assert.assertEquals(jobResponse.getName(),
                job.getName());
        MantaJobPhase mapPhaseRequest = job.getMapPhases().get(0);
        MantaJobPhase mapPhaseResponse = jobResponse.getMapPhases().get(0);

        Assert.assertEquals(mapPhaseResponse.getType(),
                mapPhaseRequest.getType());
        Assert.assertEquals(mapPhaseResponse.getExec(),
                mapPhaseRequest.getExec());

        Assert.assertTrue(jobResponse.getCancelled());
        Assert.assertTrue(jobResponse.getInputDone());
        Assert.assertNotNull(jobResponse.getTimeCreated());
    }

    @Test(dependsOnMethods = {"createJob"}, successPercentage = 66)
    public void canAddAndGetInputsFromIterator() throws IOException {
        MantaJob job = buildJob();
        UUID jobId = createJobAndSkipIfUnsupported(job);

        List<String> inputs = new ArrayList<>();

        String objPath = String.format("%s%s", testPathPrefix, UUID.randomUUID());
        mantaClient.put(objPath, TEST_DATA);
        inputs.add(objPath);

        try {
            mantaClient.addJobInputs(jobId, inputs.iterator());
            boolean ended = mantaClient.endJobInput(jobId);
            Assert.assertTrue(ended, "Ending input wasn't accepted");
            List<String> inputsResponse = mantaClient.getJobInputs(jobId)
                    .collect(Collectors.toList());
            Assert.assertEquals(inputsResponse, inputs);
        } finally {
            mantaClient.cancelJob(jobId);
        }
    }

    @Test(dependsOnMethods = { "createJob" }, successPercentage = 66)
    public void canAddAndGetInputsFromStream() throws IOException {
        MantaJob job = buildJob();
        UUID jobId = createJobAndSkipIfUnsupported(job);

        List<String> inputs = new ArrayList<>();

        String objPath = String.format("%s%s", testPathPrefix, UUID.randomUUID());
        mantaClient.put(objPath, TEST_DATA);
        inputs.add(objPath);

        try (Stream<String> inputsStream = inputs.stream()){
            mantaClient.addJobInputs(jobId, inputsStream);
            boolean ended = mantaClient.endJobInput(jobId);
            Assert.assertTrue(ended, "Ending input wasn't accepted");
            List<String> inputsResponse = mantaClient.getJobInputs(jobId)
                    .collect(Collectors.toList());
            Assert.assertEquals(inputsResponse, inputs);
        } finally {
            mantaClient.cancelJob(jobId);
        }
    }

    @Test(dependsOnMethods = { "createJob", "getJob" }, groups = { "expensive" })
    public void canListAllJobIDs() throws IOException, InterruptedException {
        final MantaJob job1 = buildJob();
        final UUID job1id = createJobAndSkipIfUnsupported(job1);
        final MantaJob job2 = buildJob();
        final UUID job2id = createJobAndSkipIfUnsupported(job2);

        Assert.assertTrue(mantaClient.endJobInput(job1id));
        Assert.assertTrue(mantaClient.endJobInput(job2id));

        awaitJobCompletion(job1id);

        awaitJobCompletion(job2id);

        try (Stream<UUID> jobs = mantaClient.getAllJobIds()) {
            List<UUID> found = jobs.filter(id -> id.equals(job1id) || id.equals(job2id))
                                   .collect(Collectors.toList());

            Assert.assertEquals(found.size(), 2, "We should have found both jobs");
        }  catch (AssertionError e) {
            String msg = "Couldn't find job in job list, retry test a few times to verify";
            LOG.error(msg, e);
            throw new SkipException(msg, e);
        }
    }

    /* This test has been disabled due to its frequent failures with our build
     * and development environments. The code path for testing job lists is
     * tested elsewhere, but not the specific operation of listing ALL jobs.
     */
    @Test(dependsOnMethods = { "createJob", "getJob" }, groups = { "expensive" })
    public void canListAllJobs() throws IOException, InterruptedException {
        final MantaJob job1 = buildJob();
        final UUID job1id = createJobAndSkipIfUnsupported(job1);
        final MantaJob job2 = buildJob();
        final UUID job2id = createJobAndSkipIfUnsupported(job2);

        Assert.assertTrue(mantaClient.endJobInput(job1id));
        Assert.assertTrue(mantaClient.endJobInput(job2id));

        awaitJobCompletion(job1id);

        awaitJobCompletion(job2id);

        Predicate<? super MantaJob> findByJobIdFilter = job -> {
            Assert.assertNotNull(job);
            return job.getId().equals(job1id) || job.getId().equals(job2id);
        };

        try (Stream<MantaJob> jobs = mantaClient.getAllJobs();
             Stream<MantaJob> filtered = jobs.filter(findByJobIdFilter)) {
            List<MantaJob> found = filtered.collect(Collectors.toList());

            Assert.assertEquals(found.size(), 2, "We should have found both jobs");
        }  catch (AssertionError e) {
            String msg = "Couldn't find job in job list, retry test a few times to verify";
            LOG.error(msg, e);
            throw new SkipException(msg, e);
        }
    }

    @Test(dependsOnMethods = { "createJob", "getJob" }, groups = { "expensive" })
    public void canListAllRunningJobIDs() throws IOException, InterruptedException {
        final MantaJob job1 = buildJob();
        final UUID job1id = createJobAndSkipIfUnsupported(job1);
        final MantaJob job2 = buildJob();
        final UUID job2id = createJobAndSkipIfUnsupported(job2);
        final MantaJob job3 = buildJob();
        final UUID job3id = createJobAndSkipIfUnsupported(job3);

        Assert.assertTrue(mantaClient.endJobInput(job3id));

        awaitJobCompletion(job3id);

        List<UUID> searchIds = new ArrayList<>();
        searchIds.add(job1id);
        searchIds.add(job2id);
        searchIds.add(job3id);

        try (Stream<UUID> jobs = mantaClient.getJobIdsByState("running")) {
            List<UUID> found = jobs.filter(searchIds::contains)
                    .collect(Collectors.toList());

            Assert.assertEquals(found.size(), 2, "We should have found both jobs");
        } catch (AssertionError e) {
            String msg = "Couldn't find job in job list, retry test a few times to verify";
            LOG.error(msg, e);
            throw new SkipException(msg, e);
        } finally {
            mantaClient.cancelJob(job1id);
            mantaClient.cancelJob(job2id);
        }
    }

    @Test(dependsOnMethods = { "createJob", "getJob" }, groups = { "expensive" })
    public void canListAllRunningJobs() throws IOException, InterruptedException {
        final MantaJob job1 = buildJob();
        final UUID job1id = createJobAndSkipIfUnsupported(job1);
        final MantaJob job2 = buildJob();
        final UUID job2id = createJobAndSkipIfUnsupported(job2);
        final MantaJob job3 = buildJob();
        final UUID job3id = createJobAndSkipIfUnsupported(job3);

        Assert.assertTrue(mantaClient.endJobInput(job3id));

        awaitJobCompletion(job3id);

        List<UUID> searchIds = new ArrayList<>();
        searchIds.add(job1id);
        searchIds.add(job2id);
        searchIds.add(job3id);

        try (Stream<MantaJob> jobs = mantaClient.getJobsByState("running")) {
            List<MantaJob> found = jobs.filter(j -> searchIds.contains(j.getId()))
                    .collect(Collectors.toList());

            Assert.assertEquals(found.size(), 2, "We should have found both jobs");
        } catch (AssertionError e) {
            String msg = "Couldn't find job in job list, retry test a few times to verify";
            LOG.error(msg, e);
            throw new SkipException(msg, e);
        } finally {
            mantaClient.cancelJob(job1id);
            mantaClient.cancelJob(job2id);
        }
    }

    @Test(dependsOnMethods = { "createJob", "getJob" })
    public void canListJobIdsByName() throws IOException {
        final String name = String.format("by_name_%s", UUID.randomUUID());
        final MantaJob job1 = buildJob(name, "cat");
        final UUID job1id = createJobAndSkipIfUnsupported(job1);
        MantaJob job2 = buildJob(name, "cat");
        final UUID job2id = createJobAndSkipIfUnsupported(job2);

        try (Stream<UUID> jobs = mantaClient.getJobIdsByName(name)) {
            List<UUID> found = jobs.collect(Collectors.toList());

            Assert.assertEquals(found.size(), 2, "We should have found both jobs");
            Assert.assertTrue(found.contains(job1id));
            Assert.assertTrue(found.contains(job2id));
        } catch (AssertionError e) {
            String msg = "Couldn't find job in job list, retry test a few times to verify";
            LOG.error(msg, e);
            throw new SkipException(msg, e);
        } finally {
            mantaClient.cancelJob(job1id);
            mantaClient.cancelJob(job2id);
        }
    }

    @Test
    public void canListOutputsForJobWithNoInputs() throws IOException, InterruptedException {
        final MantaJob job = buildJob();
        final UUID jobId = createJobAndSkipIfUnsupported(job);
        Assert.assertTrue(mantaClient.endJobInput(jobId));

        awaitJobCompletion(jobId);

        List<String> outputs = mantaClient.getJobOutputs(jobId)
                .collect(Collectors.toList());

        Assert.assertEquals(outputs.size(), 0);
    }

    @Test
    public void canListOutputsForJobWithOneInput() throws IOException, InterruptedException {
        String path = String.format("%s%s", testPathPrefix, UUID.randomUUID());
        mantaClient.put(path, TEST_DATA);

        final MantaJob job = buildJob();
        final UUID jobId = createJobAndSkipIfUnsupported(job);

        List<String> inputs = new ArrayList<>();
        inputs.add(path);

        mantaClient.addJobInputs(jobId, inputs.iterator());
        Assert.assertTrue(mantaClient.endJobInput(jobId));

        awaitJobCompletion(jobId);

        List<String> outputs = mantaClient.getJobOutputs(jobId)
                .collect(Collectors.toList());

        Assert.assertEquals(outputs.size(), 1);

        String actual = mantaClient.getAsString(outputs.get(0));
        Assert.assertEquals(actual, TEST_DATA,
                "Output wasn't the same as input");
    }

    @Test
    public void canListOutputsForJobAsStreams() throws IOException, InterruptedException {
        String path1 = String.format("%s%s", testPathPrefix, UUID.randomUUID());
        mantaClient.put(path1, TEST_DATA);

        String path2 = String.format("%s%s", testPathPrefix, UUID.randomUUID());
        mantaClient.put(path2, TEST_DATA);

        final MantaJob job = buildJob();
        final UUID jobId = createJobAndSkipIfUnsupported(job);

        List<String> inputs = new ArrayList<>();
        inputs.add(path1);
        inputs.add(path2);

        mantaClient.addJobInputs(jobId, inputs.iterator());
        Assert.assertTrue(mantaClient.endJobInput(jobId));

        awaitJobCompletion(jobId);

        final AtomicInteger count = new AtomicInteger(0);

        mantaClient.getJobOutputsAsStreams(jobId)
                .forEach(o -> {
                    count.incrementAndGet();
                    try {
                        String content = IOUtils.toString(o, Charset.defaultCharset());
                        Assert.assertEquals(content, TEST_DATA);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

        Assert.assertEquals(count.get(), 2, "Missing both outputs");
    }

    @Test
    public void canListOutputsForJobAsStrings() throws IOException, InterruptedException {
        String path1 = String.format("%s%s", testPathPrefix, UUID.randomUUID());
        mantaClient.put(path1, TEST_DATA);

        String path2 = String.format("%s%s", testPathPrefix, UUID.randomUUID());
        mantaClient.put(path2, TEST_DATA);

        final MantaJob job = buildJob();
        final UUID jobId = createJobAndSkipIfUnsupported(job);

        List<String> inputs = new ArrayList<>();
        inputs.add(path1);
        inputs.add(path2);

        mantaClient.addJobInputs(jobId, inputs.iterator());
        Assert.assertTrue(mantaClient.endJobInput(jobId));

        awaitJobCompletion(jobId);

        final AtomicInteger count = new AtomicInteger(0);

        mantaClient.getJobOutputsAsStrings(jobId)
                .forEach(content -> {
                    count.incrementAndGet();
                    Assert.assertEquals(content, TEST_DATA);
                });

        Assert.assertEquals(count.get(), 2, "Missing both outputs");
    }

    @Test
    public void canListFailedJobs() throws IOException, InterruptedException {
        String path = String.format("%s%s", testPathPrefix, UUID.randomUUID());
        mantaClient.put(path, TEST_DATA);

        final MantaJob job = buildJob("failed_job", "grep foo");
        final UUID jobId = createJobAndSkipIfUnsupported(job);

        List<String> inputs = new ArrayList<>();
        inputs.add(path);

        mantaClient.addJobInputs(jobId, inputs.iterator());
        Assert.assertTrue(mantaClient.endJobInput(jobId));

        awaitJobCompletion(jobId);

        List<String> failures = mantaClient.getJobFailures(jobId)
                .collect(Collectors.toList());

        Assert.assertEquals(failures.size(), 1, "There should only be a single failure");
    }

    @Test
    public void canGetJobErrors() throws IOException, InterruptedException {
        String path1 = String.format("%s%s", testPathPrefix, UUID.randomUUID());
        mantaClient.put(path1, TEST_DATA);

        String path2 = String.format("%s%s", testPathPrefix, UUID.randomUUID());
        mantaClient.put(path2, TEST_DATA);

        final MantaJob job = buildJob("failed_job", "grep foo");
        final UUID jobId = createJobAndSkipIfUnsupported(job);

        List<String> inputs = new ArrayList<>();
        inputs.add(path1);
        inputs.add(path2);

        mantaClient.addJobInputs(jobId, inputs.iterator());
        Assert.assertTrue(mantaClient.endJobInput(jobId));

        awaitJobCompletion(jobId);

        List<MantaJobError> errors = mantaClient.getJobErrors(jobId)
                .collect(Collectors.toList());

        Assert.assertEquals(errors.size(), 2, "Expected two errors");
    }

    private MantaJob buildJob() {
        String name = String.format("integration_test_%d", count.incrementAndGet());
        return buildJob(name, "cat");
    }

    private MantaJob buildJob(final String name, final String exec) {
        List<MantaJobPhase> phases = new ArrayList<>();
        MantaJobPhase map = new MantaJobPhase()
            .setType("map")
            .setExec(exec);
        phases.add(map);

        return new MantaJob(name, phases);
    }

    private void awaitJobCompletion(final UUID jobId) throws IOException, InterruptedException {
        for (int checks = 0; checks < 20; checks++) {
            if (mantaClient.getJob(jobId).getState().equals("done")) {
                return;
            }

            Thread.sleep(1000);
        }

        throw new AssertionError("Waited too long (~20 seconds) for job state to become \"done\" ");
    }

    private UUID createJobAndSkipIfUnsupported(final MantaJob mantaJob) throws IOException {
        try {
            final UUID jobId = mantaClient.createJob(mantaJob);
            return jobId;

        } catch (MantaClientHttpResponseException e) {
            if (e.getServerCode().equals(RESOURCE_NOT_FOUND_ERROR)) {
                final String message =
                        "This integration-test class can't be run since Jobs" +
                                "have been disabled for this account";
                LOG.warn(message);
                throw new SkipException(message, e);
            }

            throw e;
        }
    }
}
