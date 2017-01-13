package com.joyent.manta.client;

import com.joyent.manta.config.ConfigContext;
import com.joyent.manta.config.IntegrationTestConfigContext;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.RandomUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.UUID;

@Test
public class MantaObjectOutputStreamIT {
    private static final Logger LOG = LoggerFactory.getLogger(MantaObjectOutputStreamIT.class);
    private static final String TEST_DATA = "EPISODEII_IS_BEST_EPISODE";

    private MantaClient mantaClient;

    private String testPathPrefix;

    @BeforeClass()
    @Parameters({"usingEncryption"})
    public void beforeClass(@Optional Boolean usingEncryption) throws IOException {

        // Let TestNG configuration take precedence over environment variables
        ConfigContext config = new IntegrationTestConfigContext(usingEncryption);

        mantaClient = new MantaClient(config);
        testPathPrefix = String.format("%s/stor/%s/",
                config.getMantaHomeDirectory(), UUID.randomUUID());
        mantaClient.putDirectory(testPathPrefix);
    }


    @AfterClass
    public void afterClass() throws IOException {
        if (mantaClient != null) {
            mantaClient.deleteRecursive(testPathPrefix);
            mantaClient.closeWithWarning();
        }
    }

    public void canUploadSmallString() throws IOException {
        String path = testPathPrefix + "uploaded-" + UUID.randomUUID() + ".txt";
        MantaObjectOutputStream out = mantaClient.putAsOutputStream(path);

        try {
            out.write(TEST_DATA.getBytes());
        } finally {
            out.close();
        }

        MantaObject uploaded = out.getObjectResponse();

        Assert.assertEquals(uploaded.getContentLength().longValue(), TEST_DATA.length(),
                "Uploaded content length doesn't match");

        Assert.assertTrue(mantaClient.existsAndIsAccessible(path),
                "File wasn't uploaded: " + path);

        String actual = mantaClient.getAsString(path);
        Assert.assertEquals(actual, TEST_DATA,
                "Uploaded bytes don't match");
    }

    public void canUploadMuchLargerFile() throws IOException {
        String path = testPathPrefix + "uploaded-" + UUID.randomUUID() + ".txt";
        MantaObjectOutputStream out = mantaClient.putAsOutputStream(path);

        MessageDigest md5Digest = DigestUtils.getMd5Digest();
        long totalBytes = 0;

        try {
            for (int i = 0; i < 100; i++) {
                int chunkSize = RandomUtils.nextInt(1, 131072);
                byte[] randomBytes = RandomUtils.nextBytes(chunkSize);
                md5Digest.update(randomBytes);
                totalBytes += randomBytes.length;
                out.write(randomBytes);

                // periodically flush
                if (i % 25 == 0) {
                    out.flush();
                }
            }
        } finally {
            out.close();
        }

        MantaObject uploaded = out.getObjectResponse();

        Assert.assertEquals(uploaded.getContentLength().longValue(), totalBytes,
                "Uploaded content length response doesn't match");

        Assert.assertEquals(uploaded.getMd5Bytes(),
                md5Digest.digest());

        Assert.assertTrue(mantaClient.existsAndIsAccessible(path),
                "File wasn't uploaded: " + path);
    }

    public void canUploadMuchLargerFileWithPeriodicWaits() throws IOException, InterruptedException {
        String path = testPathPrefix + "uploaded-" + UUID.randomUUID() + ".txt";
        MantaObjectOutputStream out = mantaClient.putAsOutputStream(path);

        MessageDigest md5Digest = DigestUtils.getMd5Digest();
        long totalBytes = 0;

        try {
            for (int i = 0; i < 100; i++) {
                int chunkSize = RandomUtils.nextInt(1, 131072);
                byte[] randomBytes = RandomUtils.nextBytes(chunkSize);
                md5Digest.update(randomBytes);
                totalBytes += randomBytes.length;
                out.write(randomBytes);

                // periodically wait
                if (i % 3 == 0) {
                    Thread.sleep(RandomUtils.nextLong(1L, 1000L));
                }
            }
        } finally {
            out.close();
        }

        MantaObject uploaded = out.getObjectResponse();

        Assert.assertEquals(uploaded.getContentLength().longValue(), totalBytes,
                "Uploaded content length response doesn't match");

        Assert.assertEquals(uploaded.getMd5Bytes(),
                md5Digest.digest());

        Assert.assertTrue(mantaClient.existsAndIsAccessible(path),
                "File wasn't uploaded: " + path);
    }
}
