/**
 * Copyright (c) 2015 Bosch Software Innovations GmbH and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.hawkbit.artifact.repository;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.eclipse.hawkbit.artifact.repository.model.DbArtifact;
import org.eclipse.hawkbit.artifact.repository.model.DbArtifactHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.AmazonClientException;
import com.amazonaws.RequestClientOptions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.Headers;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;

/**
 * An {@link ArtifactRepository} implementation for the AWS S3 service. All
 * binaries are stored in single bucket using the configured name
 * {@link S3RepositoryProperties#getBucketName()}.
 * 
 * From the AWS S3 documentation:
 * <p>
 * There is no limit to the number of objects that can be stored in a bucket and
 * no difference in performance whether you use many buckets or just a few. You
 * can store all of your objects in a single bucket, or you can organize them
 * across several buckets.
 * </p>
 */
public class S3Repository implements ArtifactRepository {

    private static final Logger LOG = LoggerFactory.getLogger(S3Repository.class);

    private static final String TEMP_FILE_PREFIX = "tmp";
    private static final String TEMP_FILE_SUFFIX = "artifactrepo";

    private final AmazonS3 amazonS3;
    private final S3RepositoryProperties s3Properties;

    /**
     * Constructor.
     * 
     * @param amazonS3
     *            the amazonS3 client to use
     * @param s3Properties
     *            the properties which e.g. holds the name of the bucket to
     *            store in
     */
    public S3Repository(final AmazonS3 amazonS3, final S3RepositoryProperties s3Properties) {
        this.amazonS3 = amazonS3;
        this.s3Properties = s3Properties;
    }

    @Override
    public DbArtifact store(final InputStream content, final String filename, final String contentType) {
        return store(content, filename, contentType, null);
    }

    @Override
    // suppress warning, of not strong enough hashing algorithm, SHA-1 and MD5
    // is not used security related
    @SuppressWarnings("squid:S2070")
    public DbArtifact store(final InputStream content, final String filename, final String contentType,
            final DbArtifactHash hash) {
        final MessageDigest mdSHA1;
        final MessageDigest mdMD5;
        try {
            mdSHA1 = MessageDigest.getInstance("SHA1");
            mdMD5 = MessageDigest.getInstance("MD5");
        } catch (final NoSuchAlgorithmException e) {
            throw new ArtifactStoreException(e.getMessage(), e);
        }

        LOG.debug("Creating temporary file to store the inputstream to it");

        final File file = createTempFile();
        LOG.debug("Calculating sha1 and md5 hashes");
        try (final DigestOutputStream outputstream = openFileOutputStream(file, mdSHA1, mdMD5)) {
            ByteStreams.copy(content, outputstream);
            outputstream.flush();
            final String sha1Hash16 = BaseEncoding.base16().lowerCase().encode(mdSHA1.digest());
            final String md5Hash16 = BaseEncoding.base16().lowerCase().encode(mdMD5.digest());

            return store(sha1Hash16, md5Hash16, contentType, file, hash);
        } catch (final IOException e) {
            throw new ArtifactStoreException(e.getMessage(), e);
        } finally {
            if (!file.delete()) {
                LOG.error("Could not delete temp file {}", file);
            }
        }
    }

    private DbArtifact store(final String sha1Hash16, final String mdMD5Hash16, final String contentType,
            final File file, final DbArtifactHash hash) {
        final S3Artifact s3Artifact = createS3Artifact(sha1Hash16, mdMD5Hash16, contentType, file);
        checkHashes(s3Artifact, hash);

        LOG.info("Storing file {} with length {} to AWS S3 bucket {} as SHA1 {}", file.getName(), file.length(),
                s3Properties.getBucketName(), sha1Hash16);

        if (exists(sha1Hash16)) {
            LOG.debug("Artifact {} already exists on S3 bucket {}, don't need to upload twice", sha1Hash16,
                    s3Properties.getBucketName());
            return s3Artifact;
        }

        try (final InputStream inputStream = new BufferedInputStream(new FileInputStream(file),
                RequestClientOptions.DEFAULT_STREAM_BUFFER_SIZE)) {
            final ObjectMetadata objectMetadata = createObjectMetadata(mdMD5Hash16, contentType, file);
            amazonS3.putObject(s3Properties.getBucketName(), sha1Hash16, inputStream, objectMetadata);

            return s3Artifact;
        } catch (final IOException | AmazonClientException e) {
            throw new ArtifactStoreException(e.getMessage(), e);
        }
    }

    private S3Artifact createS3Artifact(final String sha1Hash16, final String mdMD5Hash16, final String contentType,
            final File file) {
        final S3Artifact s3Artifact = new S3Artifact(amazonS3, s3Properties, sha1Hash16);
        s3Artifact.setContentType(contentType);
        s3Artifact.setArtifactId(sha1Hash16);
        s3Artifact.setSize(file.length());
        s3Artifact.setContentType(contentType);
        s3Artifact.setHashes(new DbArtifactHash(sha1Hash16, mdMD5Hash16));
        return s3Artifact;
    }

    private ObjectMetadata createObjectMetadata(final String mdMD5Hash16, final String contentType, final File file) {
        final ObjectMetadata objectMetadata = new ObjectMetadata();
        final String mdMD5Hash64 = BaseEncoding.base64().encode(BaseEncoding.base16().lowerCase().decode(mdMD5Hash16));
        objectMetadata.setContentMD5(mdMD5Hash64);
        objectMetadata.setContentType(contentType);
        objectMetadata.setContentLength(file.length());
        objectMetadata.setHeader("x-amz-meta-md5chksum", mdMD5Hash64);
        if (s3Properties.isServerSideEncryption()) {
            objectMetadata.setHeader(Headers.SERVER_SIDE_ENCRYPTION, s3Properties.getServerSideEncryptionAlgorithm());
        }
        return objectMetadata;
    }

    @Override
    public void deleteBySha1(final String sha1Hash) {
        LOG.info("Deleting S3 object from bucket {} and key {}", s3Properties.getBucketName(), sha1Hash);
        amazonS3.deleteObject(new DeleteObjectRequest(s3Properties.getBucketName(), sha1Hash));
    }

    @Override
    public DbArtifact getArtifactBySha1(final String sha1) {
        LOG.info("Retrieving S3 object from bucket {} and key {}", s3Properties.getBucketName(), sha1);
        final S3Object s3Object = amazonS3.getObject(s3Properties.getBucketName(), sha1);
        if (s3Object == null) {
            return null;
        }

        final ObjectMetadata s3ObjectMetadata = s3Object.getObjectMetadata();

        final S3Artifact s3Artifact = new S3Artifact(amazonS3, s3Properties, sha1);
        s3Artifact.setArtifactId(sha1);
        s3Artifact.setSize(s3ObjectMetadata.getContentLength());
        // the MD5Content is stored in the ETag
        s3Artifact.setHashes(new DbArtifactHash(sha1,
                BaseEncoding.base16().lowerCase().encode(BaseEncoding.base64().decode(s3ObjectMetadata.getETag()))));
        s3Artifact.setContentType(s3ObjectMetadata.getContentType());
        return s3Artifact;
    }

    private static void checkHashes(final DbArtifact artifact, final DbArtifactHash hash) {
        if (hash == null) {
            return;
        }
        if (hash.getSha1() != null && !artifact.getHashes().getSha1().equals(hash.getSha1())) {
            throw new HashNotMatchException("The given sha1 hash " + hash.getSha1()
                    + " does not match with the calcualted sha1 hash " + artifact.getHashes().getSha1(),
                    HashNotMatchException.SHA1);
        }
        if (hash.getMd5() != null && !artifact.getHashes().getMd5().equals(hash.getMd5())) {
            throw new HashNotMatchException("The given md5 hash " + hash.getMd5()
                    + " does not match with the calcualted md5 hash " + artifact.getHashes().getMd5(),
                    HashNotMatchException.MD5);
        }
    }

    private static File createTempFile() {
        try {
            return File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);
        } catch (final IOException e) {
            throw new ArtifactStoreException("Cannot create tempfile", e);
        }
    }

    private static DigestOutputStream openFileOutputStream(final File file, final MessageDigest mdSHA1,
            final MessageDigest mdMD5) throws FileNotFoundException {
        return new DigestOutputStream(
                new DigestOutputStream(new BufferedOutputStream(new FileOutputStream(file)), mdMD5), mdSHA1);
    }

    private boolean exists(final String sha1) {
        return amazonS3.doesObjectExist(s3Properties.getBucketName(), sha1);
    }
}
