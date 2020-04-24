// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.filedistribution;

import com.yahoo.config.FileReference;
import java.util.logging.Level;
import com.yahoo.vespa.config.ConnectionPool;
import com.yahoo.vespa.defaults.Defaults;

import java.io.File;
import java.time.Duration;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Handles downloads of files (file references only for now)
 *
 * @author hmusum
 */
public class FileDownloader {

    private final static Logger log = Logger.getLogger(FileDownloader.class.getName());
    public static File defaultDownloadDirectory = new File(Defaults.getDefaults().underVespaHome("var/db/vespa/filedistribution"));

    private final File downloadDirectory;
    private final Duration timeout;
    private final FileReferenceDownloader fileReferenceDownloader;

    public FileDownloader(ConnectionPool connectionPool) {
        this(connectionPool, defaultDownloadDirectory , defaultDownloadDirectory , Duration.ofMinutes(15), Duration.ofSeconds(10));
    }

    FileDownloader(ConnectionPool connectionPool, File downloadDirectory, File tmpDirectory, Duration timeout, Duration sleepBetweenRetries) {
        this.downloadDirectory = downloadDirectory;
        this.timeout = timeout;
        this.fileReferenceDownloader = new FileReferenceDownloader(downloadDirectory, tmpDirectory, connectionPool, timeout, sleepBetweenRetries);
    }

    public Optional<File> getFile(FileReference fileReference) {
        return getFile(new FileReferenceDownload(fileReference));
    }

    public Optional<File> getFile(FileReferenceDownload fileReferenceDownload) {
        try {
            return getFutureFile(fileReferenceDownload).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            log.log(Level.WARNING, "Failed downloading '" + fileReferenceDownload.fileReference().value() + "', removing from download queue: " + e.getMessage());
            fileReferenceDownloader.failedDownloading(fileReferenceDownload.fileReference());
            return Optional.empty();
        }
    }

    Future<Optional<File>> getFutureFile(FileReferenceDownload fileReferenceDownload) {
        FileReference fileReference = fileReferenceDownload.fileReference();
        Objects.requireNonNull(fileReference, "file reference cannot be null");

        Optional<File> file = getFileFromFileSystem(fileReference);
        return (file.isPresent())
                ? CompletableFuture.completedFuture(file)
                : download(fileReferenceDownload);
    }

    double downloadStatus(FileReference fileReference) {
        return fileReferenceDownloader.downloadStatus(fileReference.value());
    }

    public Map<FileReference, Double> downloadStatus() {
        return fileReferenceDownloader.downloadStatus();
    }

    File downloadDirectory() {
        return downloadDirectory;
    }

    // Files are moved atomically, so if file reference exists and is accessible we can use it
    private Optional<File> getFileFromFileSystem(FileReference fileReference) {
        File[] files = new File(downloadDirectory, fileReference.value()).listFiles();
        if (downloadDirectory.exists() && downloadDirectory.isDirectory() && files != null && files.length > 0) {
            File file = files[0];
            if (!file.exists()) {
                throw new RuntimeException("File reference '" + fileReference.value() + "' does not exist");
            } else if (!file.canRead()) {
                throw new RuntimeException("File reference '" + fileReference.value() + "'exists, but unable to read it");
            } else {
                log.log(Level.FINE, () -> "File reference '" + fileReference.value() + "' found: " + file.getAbsolutePath());
                fileReferenceDownloader.setDownloadStatus(fileReference, 1.0);
                return Optional.of(file);
            }
        }
        return Optional.empty();
    }

    private boolean alreadyDownloaded(FileReference fileReference) {
        try {
            return (getFileFromFileSystem(fileReference).isPresent());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Start a download, don't wait for result */
    public void downloadIfNeeded(FileReferenceDownload fileReferenceDownload) {
        FileReference fileReference = fileReferenceDownload.fileReference();
        if (alreadyDownloaded(fileReference)) return;

        download(fileReferenceDownload);
    }

    /** Download, the future returned will be complete()d by receiving method in {@link FileReceiver} */
    private synchronized Future<Optional<File>> download(FileReferenceDownload fileReferenceDownload) {
        FileReference fileReference = fileReferenceDownload.fileReference();
        FileReferenceDownload inProgress = fileReferenceDownloader.getDownloadInProgress(fileReference);
        return (inProgress == null) ? queueForDownload(fileReferenceDownload) : inProgress.future();
    }

    private Future<Optional<File>> queueForDownload(FileReferenceDownload fileReferenceDownload) {
        fileReferenceDownloader.addToDownloadQueue(fileReferenceDownload);
        return fileReferenceDownload.future();
    }

    public FileReferenceDownloader fileReferenceDownloader() {
        return fileReferenceDownloader;
    }

    public void close() {
        fileReferenceDownloader.close();
    }
}
