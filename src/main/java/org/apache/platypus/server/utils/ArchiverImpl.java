/*
 *
 *  *
 *  *  Copyright 2019 Yelp Inc.
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 *  *  either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  *
 *
 *
 */

package org.apache.platypus.server.utils;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.google.inject.Inject;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public class ArchiverImpl implements Archiver {
    private static Logger logger = LoggerFactory.getLogger(ArchiverImpl.class);
    private static final String CURRENT_VERSION_NAME = "current";
    private static final String TMP_SUFFIX = ".tmp";


    private final AmazonS3 s3;
    private final String bucketName;
    private final Path archiverDirectory;
    private final Tar tar;
    private final VersionManager versionManger;

    @Inject
    public ArchiverImpl(final AmazonS3 s3, final String bucketName, final Path archiverDirectory, final Tar tar) {
        this.s3 = s3;
        this.bucketName = bucketName;
        this.archiverDirectory = archiverDirectory;
        this.tar = tar;
        this.versionManger = new VersionManager(s3, bucketName);
    }


    @Override
    public Path download(String serviceName, String resource) throws IOException {
        if (!Files.exists(archiverDirectory)) {
            throw new IOException("Archiver directory doesn't exist: " + archiverDirectory);
        }

        final String latestVersion = getVersionString(serviceName, resource, "_latest_version");
        final String versionHash = getVersionString(serviceName, resource, latestVersion);
        final Path resourceDestDirectory = archiverDirectory.resolve(resource);
        final Path versionDirectory = resourceDestDirectory.resolve(versionHash);
        final Path currentDirectory = resourceDestDirectory.resolve("current");
        final Path tempCurrentLink = resourceDestDirectory.resolve(getTmpName());
        final Path relativVersionDirectory = Paths.get(versionHash);
        logger.info("Downloading resource {} for service {} version {} to directory {}", resource, serviceName, versionHash, versionDirectory);
        getVersionContent(serviceName, resource, versionHash, versionDirectory);
        try {
            logger.info("Point current version symlink to new resource {}", resource);
            Files.createSymbolicLink(tempCurrentLink, relativVersionDirectory);
            Files.move(tempCurrentLink, currentDirectory, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (Files.exists(tempCurrentLink)) {
                FileUtils.deleteDirectory(tempCurrentLink.toFile());
            }
        }
        cleanupFiles(versionHash, resourceDestDirectory);
        return currentDirectory;
    }

    @Override
    public String upload(final String serviceName, final String resource, Path sourceDir) throws IOException {
        if (!Files.exists(sourceDir)) {
            throw new IOException(String.format("Source directory %s, for service %s, and resource %s does not exist", sourceDir, serviceName, resource));
        }
        Path destPath = archiverDirectory.resolve(getTmpName());
        tar.buildTar(sourceDir, destPath);
        String versionHash = UUID.randomUUID().toString();
        uploadTarWithMetadata(serviceName, resource, versionHash, destPath);
        Files.deleteIfExists(destPath);
        return versionHash;
    }

    private void uploadTarWithMetadata(String serviceName, String resource, String versionHash, Path path) {
        final String absoluteResourcePath = String.format("%s/%s/%s", serviceName, resource, versionHash);
        s3.putObject(bucketName, absoluteResourcePath, path.toFile());
    }

    @Override
    public boolean blessVersion(String serviceName, String resource, String resourceHash) throws IOException {
        return versionManger.blessVersion(serviceName, resource, resourceHash);
    }

    private String getVersionString(final String serviceName, final String resource, final String version) throws IOException {
        final String absoluteResourcePath = String.format("%s/_version/%s/%s", serviceName, resource, version);
        try (
                final S3Object s3Object = s3.getObject(bucketName, absoluteResourcePath);
        ) {
            final String versionPath = IOUtils.toString(s3Object.getObjectContent());
            return versionPath;
        }
    }

    private void getVersionContent(final String serviceName, final String resource, final String hash, final Path destDirectory) throws IOException {
        final String absoluteResourcePath = String.format("%s/%s/%s", serviceName, resource, hash);
        try (
                final S3Object s3Object = s3.getObject(bucketName, absoluteResourcePath);
                final S3ObjectInputStream s3ObjectInputStream = s3Object.getObjectContent();
                final GzipCompressorInputStream gzipCompressorInputStream = new GzipCompressorInputStream(s3ObjectInputStream, true);
                final TarArchiveInputStream tarArchiveInputStream = new TarArchiveInputStream(gzipCompressorInputStream);
        ) {
            if (Files.exists(destDirectory)) {
                logger.info("Directory {} already exists, not re-downloading from Archiver", destDirectory);
                return;
            }
            final Path parentDirectory = destDirectory.getParent();
            final Path tmpDirectory = parentDirectory.resolve(getTmpName());
            try {
                tar.extractTar(tarArchiveInputStream, tmpDirectory);
                Files.move(tmpDirectory, destDirectory);
            } finally {
                if (Files.exists(tmpDirectory)) {
                    FileUtils.deleteDirectory(tmpDirectory.toFile());
                }
            }
        }
    }

    private String getTmpName() {
        return UUID.randomUUID().toString() + TMP_SUFFIX;
    }


    private void cleanupFiles(final String versionHash, final Path resourceDestDirectory) throws IOException {
        final DirectoryStream.Filter<Path> filter = entry -> {
            final String fileName = entry.getFileName().toString();
            // Ignore the current version
            if (CURRENT_VERSION_NAME.equals(fileName)) {
                return false;
            }
            // Ignore the current version hash
            if (versionHash.equals(fileName)) {
                return false;
            }
            // Ignore non-directories
            if (!Files.isDirectory(entry)) {
                logger.warn("Unexpected non-directory entry found while cleaning up: {}", fileName);
                return false;
            }
            // Ignore version names that aren't hex encoded
            try {
                Hex.decodeHex(fileName.toCharArray());
            } catch (DecoderException e) {
                logger.warn("Not cleaning up directory because name doesn't match pattern: {}", fileName);
                return false;
            }
            return true;
        };
        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(resourceDestDirectory, filter)) {
            for (final Path entry : stream) {
                logger.info("Cleaning up old directory: {}", entry);
                FileUtils.deleteDirectory(entry.toFile());
            }
        }
    }
}