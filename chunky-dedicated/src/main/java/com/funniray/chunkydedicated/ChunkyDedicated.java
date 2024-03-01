package com.funniray.chunkydedicated;
import java.util.concurrent.ForkJoinPool;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.popcraft.chunky.api.ChunkyAPI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
public final class ChunkyDedicated extends JavaPlugin {
    ChunkyAPI chunky;
    String bucketName = getConfig().getString("bucket-name");


    Random rand = new Random();
    int key = rand.nextInt(10000000);


    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Plugin startup logic
        chunky = getServer().getServicesManager().load(ChunkyAPI.class);
        if (chunky == null) {
            getLogger().severe("Chunky is not loaded, disabling...");
            getServer().getPluginManager().disablePlugin(this);

            return;
        }

        Bukkit.getScheduler().runTaskTimer(this, ()->{
            if (isFinished()) {
                getLogger().info("No tasks were registered, server closing...");
                Bukkit.getScheduler().runTaskLater(ChunkyDedicated.getPlugin(ChunkyDedicated.class), ()->{
                    getServer().shutdown();}, 288000L);


            }
        }, 200L, 200L);

        chunky.onGenerationComplete(event -> {
            boolean allTasksFinished = isFinished();
            if (!allTasksFinished) {
                getLogger().info("Tasks are remaining, server won't close");
                return;
            }
            getLogger().info("All tasks finished, uploading files to r2 and closing server...");
            ZipDirectory.main("world");
            DiscordWebhook webhook = new DiscordWebhook(getConfig().getString("webhook"));
            webhook.setContent("https://maps.r2.game.smd.gg/"+ "World." + key + ".zip");
            webhook.setAvatarUrl("https://avatars.githubusercontent.com/u/108903815?s=400&u=80787b5c250845ab8ddbc4b9105c841714af3943&v=4");
            webhook.setUsername("Map Notifier");
            webhook.setTts(true);
            try {
                webhook.execute();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            multipartUploadWithS3Client("world.zip");
            Bukkit.getScheduler().runTaskLater(ChunkyDedicated.getPlugin(ChunkyDedicated.class), ()->{
                getServer().shutdown();}, 288000L);

        });
    }

    public void multipartUploadWithS3Client(String filePath) {
        ClientConfiguration clientConfiguration = new ClientConfiguration();
        clientConfiguration.setSocketTimeout(60000); // 60-second socket timeout
        clientConfiguration.setConnectionTimeout(60000); // 60-second connection timeout

        AmazonS3 s3 = AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration("https://" + getConfig().getString("cloudflare-account-id") + ".r2.cloudflarestorage.com" + "/" + getConfig().getString("bucket-name"), "auto"))
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(getConfig().getString("cloudflare-access-key"), getConfig().getString("cloudflare-secret-key"))))
                .withClientConfiguration(clientConfiguration) // Pass the client configuration here
                .build();

        InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(bucketName, "World." + key + ".zip");
        InitiateMultipartUploadResult initResponse = s3.initiateMultipartUpload(initRequest);

        File file = new File(filePath);
        long contentLength = file.length();
        long partSize = 10 * 1024 * 1024; // Increased part size to 10MB

        ConcurrentLinkedQueue<PartETag> partETags = new ConcurrentLinkedQueue<>(); // Use ConcurrentLinkedQueue instead of synchronized list

        try {
            long filePosition = 0;
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 1; filePosition < contentLength; i++) {
                partSize = Math.min(partSize, (contentLength - filePosition));

                final int partNumber = i;
                final long partFilePosition = filePosition;
                final long partSizeFinal = partSize;

                // Submit a CompletableFuture for each part upload
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    // Retry logic
                    int retryCount = 0;
                    while (true) {
                        try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(file))) { // Use BufferedInputStream to read the file
                            inputStream.skip(partFilePosition); // Skip to the correct position in the file
getLogger().info("Part Number: " +partNumber);

                            UploadPartRequest uploadRequest = new UploadPartRequest()
                                    .withBucketName(bucketName)
                                    .withKey("World." + key + ".zip")
                                    .withUploadId(initResponse.getUploadId())
                                    .withPartNumber(partNumber)
                                    .withInputStream(inputStream) // Use the BufferedInputStream here
                                    .withPartSize(partSizeFinal);

                            UploadPartResult uploadResult = s3.uploadPart(uploadRequest);
                            partETags.add(uploadResult.getPartETag());
                            break;
                        } catch (Exception e) {
                            if (++retryCount > 10) { // Maximum of 10 retries
                                throw new RuntimeException(e); // If still failing after 10 retries, rethrow the exception
                            }
                            // Wait before retrying
                            try {
                                Thread.sleep(1000 * retryCount); // Wait for an increasing amount of time before retrying
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(ie);
                            }
                        }
                    }
                });

                futures.add(future);

                filePosition += partSize;
            }

            // Wait for all CompletableFuture to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            CompleteMultipartUploadRequest compRequest = new CompleteMultipartUploadRequest(getConfig().getString("bucket-name"), "World." + key + ".zip",
                    initResponse.getUploadId(), new ArrayList<>(partETags));

            s3.completeMultipartUpload(compRequest);
            getLogger().info("File uploaded successfully");
        } catch (Exception e) {
            s3.abortMultipartUpload(new AbortMultipartUploadRequest(bucketName, "World." + key + ".zip", initResponse.getUploadId()));
            getLogger().severe("File upload failed");
        }
    }


    private boolean isFinished() {
        return getServer().getWorlds().stream().map(World::getName).noneMatch(chunky::isRunning);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        getLogger().info("I'm taking a fucking break from this shit");
    }
}
