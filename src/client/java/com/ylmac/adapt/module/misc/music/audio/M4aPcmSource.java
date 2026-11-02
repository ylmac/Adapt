package com.ylmac.adapt.module.misc.music.audio;

import com.ylmac.adapt.module.misc.music.util.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 通过 ffmpeg 子进程解码 M4A/MP4/AAC 格式为 raw PCM (s16le, 44100Hz, stereo)。
 * 需要系统安装 ffmpeg (macOS: brew install ffmpeg)。
 * Minecraft JVM 不继承 shell PATH, 会搜索常见安装路径。
 */
public class M4aPcmSource extends PcmSource {
    private final String filePath;
    private Process process;
    private InputStream pcmStream;

    private static final String FFMPEG = findExecutable("ffmpeg");
    private static final String FFPROBE = findExecutable("ffprobe");

    public M4aPcmSource(String path) throws IOException {
        this.filePath = path;
        sampleRate = 44100;
        channels = 2;
        durationMs = probeDuration(path);
        startFFmpeg(0);
    }

    /** 搜索 ffmpeg/ffprobe 的绝对路径 — Minecraft JVM 的 PATH 不含 /opt/homebrew/bin */
    private static String findExecutable(String name) {
        // 1. 系统 PATH
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File f = new File(dir, name);
                if (f.canExecute()) return f.getAbsolutePath();
            }
        }
        // 2. macOS 常见安装路径
        String[] commonPaths = {
            "/opt/homebrew/bin/" + name,     // macOS ARM homebrew
            "/usr/local/bin/" + name,        // macOS Intel homebrew
            "/usr/bin/" + name,              // Linux
            "/bin/" + name,
        };
        for (String p : commonPaths) {
            File f = new File(p);
            if (f.canExecute()) return p;
        }
        // 3. 兜底: 直接用名字, 让 OS 找
        return name;
    }

    private long probeDuration(String path) {
        if (FFPROBE == null) return 0;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    FFPROBE, "-v", "quiet", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", path);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (!out.isEmpty() && !out.equals("N/A")) {
                double sec = Double.parseDouble(out);
                return (long)(sec * 1000);
            }
        } catch (Exception e) {
            Logger.warn("M4aPcmSource: ffprobe failed for {}: {}", path, e.getMessage());
        }
        return 0;
    }

    private void startFFmpeg(double seekSec) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(FFMPEG);
        if (seekSec > 0) {
            cmd.add("-ss");
            cmd.add(String.valueOf(seekSec));
        }
        cmd.add("-i"); cmd.add(filePath);
        cmd.add("-f"); cmd.add("s16le");
        cmd.add("-ar"); cmd.add("44100");
        cmd.add("-ac"); cmd.add("2");
        cmd.add("-loglevel"); cmd.add("quiet");
        cmd.add("pipe:1");
        process = new ProcessBuilder(cmd).start();
        pcmStream = process.getInputStream();
    }

    @Override
    public long seek(long ms) throws IOException {
        closeProcess();
        try {
            startFFmpeg(ms / 1000.0);
        } catch (IOException e) {
            Logger.warn("M4aPcmSource: seek restart failed: {}", e.getMessage());
        }
        return ms;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (pcmStream == null) return -1;
        return pcmStream.read(b, off, len);
    }

    private void closeProcess() {
        if (pcmStream != null) {
            try { pcmStream.close(); } catch (Exception ignored) {}
            pcmStream = null;
        }
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
    }

    @Override
    public void close() {
        closeProcess();
    }
}
