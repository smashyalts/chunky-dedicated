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

import java.io.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ChunkyDedicated extends JavaPlugin {
    String sourceFile = "world";
    FileOutputStream fos = new FileOutputStream("compressed.zip");
    ZipOutputStream zipOut = new ZipOutputStream(fos);
    ChunkyAPI chunky;

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
            getLogger().info("All tasks finished, closing server...");
            File fileToZip = new File(sourceFile);
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(fileToZip);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
            ZipEntry zipEntry = new ZipEntry(fileToZip.getName());
            try {
                zipOut.putNextEntry(zipEntry);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            byte[] bytes = new byte[1024];
            int length;
            while(true) {
                try {
                    if (!((length = fis.read(bytes)) >= 0)) break;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                try {
                    zipOut.write(bytes, 0, length);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            try {
                zipOut.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            try {
                fis.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            try {
                fos.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            AmazonS3 s3 = AmazonS3ClientBuilder.standard()
                    .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration("https://" + getConfig().get("cloudflare-account-id") + ".r2.cloudflarestorage.com", "auto"))
                    .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(getConfig().get("cloudflare-access-key").toString(), getConfig().get("cloudflare-secret-key").toString())))
                    .build();

            s3.putObject(getConfig().get("bucket-name").toString(), "compressed.zip", new File("compressed.zip"));
            getServer().shutdown();
        });
    }

    private boolean isFinished() {
        return getServer().getWorlds().stream().map(World::getName).noneMatch(chunky::isRunning);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
