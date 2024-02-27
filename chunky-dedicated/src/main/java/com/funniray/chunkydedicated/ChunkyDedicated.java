package com.funniray.chunkydedicated;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.Bucket;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.popcraft.chunky.api.ChunkyAPI;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ChunkyDedicated extends JavaPlugin {
    ChunkyAPI chunky;
    Random rand = new Random();
    int key = rand.nextInt(10000000);

    public ChunkyDedicated() throws FileNotFoundException {
    }

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
            try {
                ZipDirectory.main("world");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            AmazonS3 s3 = AmazonS3ClientBuilder.standard()
                    .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration("https://" + getConfig().get("cloudflare-account-id") + ".r2.cloudflarestorage.com", "auto"))
                    .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(getConfig().get("cloudflare-access-key").toString(), getConfig().get("cloudflare-secret-key").toString())))
                    .build();
            multipartUploadWithS3Client("world.zip");
            getServer().shutdown();
        });
    }

    public void multipartUploadWithS3Client(String filePath) {

        // Initiate the multipart upload.
        CreateMultipartUploadResponse createMultipartUploadResponse = S3Client.create().createMultipartUpload(b -> b
                .bucket(getConfig().getString("bucket-name"))
                .key(String.valueOf(key)));
        String uploadId = createMultipartUploadResponse.uploadId();

        // Upload the parts of the file.
        int partNumber = 1;
        List<CompletedPart> completedParts = new ArrayList<>();
        ByteBuffer bb = ByteBuffer.allocate(1024 * 1024 * 5); // 5 MB byte buffer

        try (RandomAccessFile file = new RandomAccessFile(filePath, "r")) {
            long fileSize = file.length();
            int position = 0;
            while (position < fileSize) {
                file.seek(position);
                int read = file.getChannel().read(bb);

                bb.flip(); // Swap position and limit before reading from the buffer.
                UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                        .bucket(getConfig().getString("bucket-name"))
                        .key(String.valueOf(key))
                        .uploadId(uploadId)
                        .partNumber(partNumber)
                        .build();

                UploadPartResponse partResponse = S3Client.create().uploadPart(
                        uploadPartRequest,
                        RequestBody.fromByteBuffer(bb));

                CompletedPart part = CompletedPart.builder()
                        .partNumber(partNumber)
                        .eTag(partResponse.eTag())
                        .build();
                completedParts.add(part);

                bb.clear();
                position += read;
                partNumber++;
            }
        } catch (IOException ignored) {
        }

        // Complete the multipart upload.
        S3Client.create().completeMultipartUpload(b -> b
                .bucket(getConfig().getString("bucket-name"))
                .key(String.valueOf(key))
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build()));
    }

    private boolean isFinished() {
        return getServer().getWorlds().stream().map(World::getName).noneMatch(chunky::isRunning);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
