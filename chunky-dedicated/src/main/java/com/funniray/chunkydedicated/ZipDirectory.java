    package com.funniray.chunkydedicated;

    import java.io.*;
    import java.util.concurrent.*;
    import java.util.concurrent.atomic.AtomicLong;
    import java.util.zip.Deflater;
    import java.util.zip.ZipEntry;
    import java.util.zip.ZipOutputStream;

    public class ZipDirectory {
        private static AtomicLong totalSize = new AtomicLong(0);
        private static AtomicLong zippedSize = new AtomicLong(0);
        private static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        public static void main(String args) {
            String sourceFile = args;
            try (FileOutputStream fos = new FileOutputStream("world.zip");
                 ZipOutputStream zipOut = new ZipOutputStream(fos)) {
                zipOut.setLevel(Deflater.BEST_COMPRESSION);
                File fileToZip = new File(sourceFile);
                totalSize.set(getTotalSize(fileToZip));

                ForkJoinPool pool = new ForkJoinPool();
                pool.invoke(new ZipAction(fileToZip, fileToZip.getName(), zipOut));

                scheduler.scheduleAtFixedRate(ZipDirectory::printProgress, 0, 30, TimeUnit.SECONDS);

                pool.shutdown();
                pool.awaitQuiescence(60, TimeUnit.SECONDS);

                scheduler.shutdown();

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
                    File[] children = fileToZip.listFiles();
                    if (children != null) {
                        for (File childFile : children) {
                            ZipAction action = new ZipAction(childFile, fileName + childFile.getName(), zipOut);
                            action.compute(); // Call compute directly instead of forking
                        }
                    }
                } else {
                    try (FileInputStream fis = new FileInputStream(fileToZip)) {
                        ZipEntry zipEntry = new ZipEntry(fileName);
                        zipOut.putNextEntry(zipEntry);
                        byte[] bytes = new byte[1024];
                        int length;
                        while ((length = fis.read(bytes)) >= 0) {
                            zipOut.write(bytes, 0, length);
                            zippedSize.addAndGet(length);
                        }
                        zipOut.closeEntry(); // Close the entry after writing the file
                    } catch (IOException e) {
                        e.printStackTrace();
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