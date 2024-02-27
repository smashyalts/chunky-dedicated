package com.funniray.chunkydedicated;

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

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

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
                    getServer().shutdown();}, 18000L);


            }
        }, 200L, 200L);

        chunky.onGenerationComplete(event -> {
            boolean allTasksFinished = isFinished();
            if (!allTasksFinished) {
                getLogger().info("Tasks are remaining, server won't close");
                return;
            }
            getLogger().info("All tasks finished, uploading files to r2 and closing server...");
            try {
                ZipDirectory.main("world");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            multipartUploadWithS3Client("world.zip");
            Bukkit.getScheduler().runTaskLater(ChunkyDedicated.getPlugin(ChunkyDedicated.class), ()->{
                getServer().shutdown();}, 18000L);

        });
    }

    public void multipartUploadWithS3Client(String filePath) {
        AmazonS3 s3 = AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration("https://" + getConfig().getString("cloudflare-account-id") + ".r2.cloudflarestorage.com" + "/" + getConfig().getString("bucket-name"), "auto"))
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(getConfig().getString("cloudflare-access-key"), getConfig().getString("cloudflare-secret-key"))))
                .build();

        InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(bucketName, "World." + key + ".zip");
        InitiateMultipartUploadResult initResponse = s3.initiateMultipartUpload(initRequest);

        File file = new File(filePath);
        long contentLength = file.length();
        long partSize = 5 * 1024 * 1024;

        try {

            long filePosition = 0;
            List<PartETag> partETags = new ArrayList<PartETag>();
            for (int i = 1; filePosition < contentLength; i++) {

                partSize = Math.min(partSize, (contentLength - filePosition));


                UploadPartRequest uploadRequest = new UploadPartRequest()
                        .withBucketName(bucketName)
                        .withKey("World." + key + ".zip")
                        .withUploadId(initResponse.getUploadId())
                        .withPartNumber(i)
                        .withFileOffset(filePosition)
                        .withFile(file)
                        .withPartSize(partSize);


                UploadPartResult uploadResult = s3.uploadPart(uploadRequest);
                partETags.add(uploadResult.getPartETag());

                filePosition += partSize;
            }


            CompleteMultipartUploadRequest compRequest = new CompleteMultipartUploadRequest(getConfig().getString("bucket-name"), "World." + key + ".zip",
                    initResponse.getUploadId(), partETags);

            s3.completeMultipartUpload(compRequest);
            DiscordWebhook webhook = new DiscordWebhook(getConfig().getString("webhook"));
            webhook.setContent("https://maps.r2.game.smd.gg/"+ "World." + key + ".zip");
            webhook.setAvatarUrl("https://avatars.githubusercontent.com/u/108903815?s=400&u=80787b5c250845ab8ddbc4b9105c841714af3943&v=4");
            webhook.setUsername("Map Notifier");
            webhook.setTts(true);
            webhook.execute(); //Handle exception
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
    }
}
