package com.winlator.xconnector;

import java.io.File;

public class UnixSocketConfig {
    public static final String SYSVSHM_SERVER_PATH = "/tmp/.sysvshm/SM0";
    public static final String ALSA_SERVER_PATH = "/tmp/.sound/AS0";
    public static final String PULSE_SERVER_PATH = "/tmp/.sound/PS0";
    public static final String XSERVER_PATH = "/tmp/.X11-unix/X0";
    public static final String VIRGL_SERVER_PATH = "/tmp/.virgl/V0";
    public final String path;

    private UnixSocketConfig(String path) {
        this.path = path;
    }

    public static UnixSocketConfig createSocket(String rootPath, String relativePath) {
        File socketFile = new File(rootPath, relativePath);

        String dirname = getDirname(relativePath);
        if (dirname.lastIndexOf("/") > 0) {
            File socketDir = new File(rootPath, dirname);
            delete(socketDir);
            socketDir.mkdirs();
        }
        else socketFile.delete();

        return new UnixSocketConfig(socketFile.getPath());
    }

    // Fable: the two helpers below replace com.winlator.core.FileUtils.getDirname / delete,
    // which were not vendored.
    private static String getDirname(String path) {
        int index = path.lastIndexOf('/');
        return index > 0 ? path.substring(0, index) : (index == 0 ? "/" : "");
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.isDirectory() && !isSymlink(file) ? file.listFiles() : null;
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private static boolean isSymlink(File file) {
        try {
            return !file.getCanonicalFile().equals(file.getAbsoluteFile());
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
