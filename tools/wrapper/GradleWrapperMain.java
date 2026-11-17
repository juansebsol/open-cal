package org.gradle.wrapper;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/**
 * Minimal, auditable Gradle bootstrap for this source archive.
 * Not the upstream Gradle wrapper implementation.
 * It downloads a checksum-pinned official Gradle distribution on first run.
 */
public final class GradleWrapperMain {
    public static void main(String[] args) throws Exception {
        Path project = Paths.get(System.getProperty("user.dir"));
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(project.resolve("gradle/wrapper/gradle-wrapper.properties"))) {
            properties.load(in);
        }
        String distUrl = properties.getProperty("distributionUrl");
        String expected = properties.getProperty("distributionSha256Sum");
        if (!"https://services.gradle.org/distributions/gradle-8.9-bin.zip".equals(distUrl))
            throw new SecurityException("Unexpected Gradle distribution URL");
        if (!"d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab".equals(expected))
            throw new SecurityException("Unexpected distribution checksum");
        Path root = Paths.get(System.getProperty("user.home"),".gradle","wrapper","dists","room-calendar-8.9");
        Files.createDirectories(root);
        Path executable = root.resolve("gradle-8.9/bin/gradle");
        Path lockPath = root.resolve("download.lock");
        try (FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = lockChannel.lock()) {
            if (!Files.isRegularFile(executable)) {
                Path tmp = root.resolve("gradle.zip.download");
                System.out.println("Downloading official Gradle 8.9 (one-time download)...");
                URLConnection connection = new URL(distUrl).openConnection();
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(120000);
                try (InputStream in = connection.getInputStream()) { Files.copy(in,tmp,StandardCopyOption.REPLACE_EXISTING); }
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream in = Files.newInputStream(tmp)) {
                    byte[] buf = new byte[16384]; int n;
                    while ((n=in.read(buf))!=-1) md.update(buf,0,n);
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : md.digest()) hex.append(String.format("%02x",b&0xff));
                if (!expected.equals(hex.toString())) { Files.deleteIfExists(tmp); throw new SecurityException("Gradle checksum verification failed"); }
                try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(tmp))) {
                    ZipEntry entry;
                    while ((entry=zip.getNextEntry())!=null) {
                        Path destination = root.resolve(entry.getName()).normalize();
                        if (!destination.startsWith(root)) throw new SecurityException("Unsafe ZIP entry");
                        if (entry.isDirectory()) Files.createDirectories(destination);
                        else {
                            Files.createDirectories(destination.getParent());
                            Files.copy(zip,destination,StandardCopyOption.REPLACE_EXISTING);
                        }
                        zip.closeEntry();
                    }
                }
                Files.deleteIfExists(tmp);
                if (!Files.isRegularFile(executable)) throw new IOException("Gradle archive did not contain the expected executable");
            }
        }
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        List<String> cmd = new ArrayList<>();
        if (windows) { cmd.add("cmd"); cmd.add("/c"); cmd.add(root.resolve("gradle-8.9/bin/gradle.bat").toString()); }
        else { cmd.add("sh"); cmd.add(executable.toString()); }
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(project.toFile()).inheritIO();
        Process process = pb.start();
        System.exit(process.waitFor());
    }
}
