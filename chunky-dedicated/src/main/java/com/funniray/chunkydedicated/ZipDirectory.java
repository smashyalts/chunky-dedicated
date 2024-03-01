package com.funniray.chunkydedicated;

import java.io.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipDirectory {
    public static void main(String args) throws IOException {
        String sourceFile = args;
        FileOutputStream fos = new FileOutputStream("world.zip");
        ZipOutputStream zipOut = new ZipOutputStream(fos);

        File fileToZip = new File(sourceFile);

        ForkJoinPool pool = new ForkJoinPool();
        pool.invoke(new ZipAction(fileToZip, fileToZip.getName(), zipOut));

        zipOut.close();
        fos.close();

        pool.shutdown(); // Initiates an orderly shutdown in which previously submitted tasks are executed, but no new tasks will be accepted
        pool.awaitQuiescence(60, TimeUnit.SECONDS); // Wait for all tasks to complete for up to 60 seconds

        // Suggest to the JVM that it's a good time to run the Garbage Collector
        System.gc();
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
                for (File childFile : children) {
                    ZipAction action = new ZipAction(childFile, fileName + childFile.getName(), zipOut);
                    action.fork(); // Start the action asynchronously
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
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}