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

    AmazonS3 s3 = AmazonS3ClientBuilder.standard()
            .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration("https://" + getConfig().get("cloudflare-account-id") + ".r2.cloudflarestorage.com", "auto"))
            .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(getConfig().get("cloudflare-access-key").toString(), getConfig().get("cloudflare-secret-key").toString())))
            .build();
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
                getServer().shutdown();
            }
        }, 200L, 200L);

        chunky.onGenerationComplete(event -> {
            boolean allTasksFinished = isFinished();
            if (!allTasksFinished) {
                getLogger().info("Tasks are remaining, server won't close");
                return;
            }
            getLogger().info("All tasks finished, uploading files to r2 and closing server...");
            Objects.requireNonNull(Bukkit.getWorld("world")).save();
            try {
                ZipDirectory.main("world");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            multipartUploadWithS3Client("world.zip");
            getServer().shutdown();
        });
    }

    public void multipartUploadWithS3Client(String filePath) {

        InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(bucketName, String.valueOf(key));
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
                        .withKey(String.valueOf(key))
                        .withUploadId(initResponse.getUploadId())
                        .withPartNumber(i)
                        .withFileOffset(filePosition)
                        .withFile(file)
                        .withPartSize(partSize);


                UploadPartResult uploadResult = s3.uploadPart(uploadRequest);
                partETags.add(uploadResult.getPartETag());

                filePosition += partSize;
            }


            CompleteMultipartUploadRequest compRequest = new CompleteMultipartUploadRequest(getConfig().getString("bucket-name"), String.valueOf(key),
                    initResponse.getUploadId(), partETags);

            s3.completeMultipartUpload(compRequest);
        } catch (Exception e) {
            s3.abortMultipartUpload(new AbortMultipartUploadRequest(bucketName, String.valueOf(key), initResponse.getUploadId()));
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
