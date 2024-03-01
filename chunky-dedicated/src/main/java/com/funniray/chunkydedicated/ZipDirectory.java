package com.funniray.chunkydedicated;

import java.io.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipDirectory {
    private static AtomicLong totalSize = new AtomicLong(0);
    private static AtomicLong zippedSize = new AtomicLong(0);

    public static void main(String args) {
        String sourceFile = args;
        try (FileOutputStream fos = new FileOutputStream("world.zip");
             ZipOutputStream zipOut = new ZipOutputStream(fos)) {

            File fileToZip = new File(sourceFile);
            totalSize.set(getTotalSize(fileToZip));

            ForkJoinPool pool = new ForkJoinPool();
            pool.invoke(new ZipAction(fileToZip, fileToZip.getName(), zipOut));

            pool.shutdown();
            pool.awaitQuiescence(60, TimeUnit.SECONDS);

            System.gc();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class ZipAction extends RecursiveAction {
        private File fileToZip;
        private String fileName;
        private ZipOutputStream zipOut;

        ZipAction(File fileToZip, String fileName, ZipOutputStream zipOut) {
            this.fileToZip = fileToZip;
            this.fileName = fileName;
            this.zipOut = zipOut;
        }

        @Override
        protected void compute() {
            if (fileToZip.isHidden()) {
                return;
            }
            if (fileToZip.isDirectory()) {
                if (!fileName.endsWith("/")) {
                    fileName += "/";
                }
                synchronized (zipOut) {
                    try {
                        zipOut.putNextEntry(new ZipEntry(fileName));
                        zipOut.closeEntry();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                File[] children = fileToZip.listFiles();
                if (children != null) {
                    for (File childFile : children) {
                        ZipAction action = new ZipAction(childFile, fileName + childFile.getName(), zipOut);
                        action.fork();
                    }
                }
            } else {
                synchronized (zipOut) {
                    try (FileInputStream fis = new FileInputStream(fileToZip)) {
                        ZipEntry zipEntry = new ZipEntry(fileName);
                        zipOut.putNextEntry(zipEntry);
                        byte[] bytes = new byte[1024];
                        int length;
                        while ((length = fis.read(bytes)) >= 0) {
                            zipOut.write(bytes, 0, length);
                            zippedSize.addAndGet(length);
                            printProgress();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private static long getTotalSize(File file) {
        long size = 0;
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                size += getTotalSize(child);
            }
        } else {
            size = file.length();
        }
        return size;
    }

    private static void printProgress() {
        double progress = (double) zippedSize.get() / totalSize.get() * 100;
        System.out.printf("Progress: %.2f%%\n", progress);
    }
}