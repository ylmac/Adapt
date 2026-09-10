package com.ylmac.adapt.ui;

import com.ylmac.adapt.QplayerModClient;

import com.ylmac.adapt.config.store.AppDirs;
import io.github.timer_err.qml4j.engine.BytecodeCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import io.github.timer_err.qml4j.render.RestoreHelper;

/**
 * qml4j 字节码磁盘缓存 + 构建期预置缓存预加载。
 *
 * 构建期（Gradle buildQmlCache task）会把 QML 编译产物按源文件哈希落盘到
 * assets/adaptclient/qml-cache/*.cache，打包进 JAR。
 * 运行时首次初始化时，若设置允许，QmlCacheManager 会从 JAR classpath 读取
 * 这些预置缓存文件装入内存，QML 加载时直接命中内存，零磁盘 I/O。
 * 同时也会把预置缓存复制到磁盘缓存目录（AppDirs.cacheBase()/qml-cache），
 * 供引擎补丁（RestoreHelper）按 manifest 恢复。
 */
public final class QmlCacheManager implements BytecodeCache {

    private static final int FORMAT_VERSION = 2;
    /** JAR 内预置缓存的 classpath 根路径 */
    private static final String PREBUILT_ROOT = "/assets/adaptclient/qml-cache/";

    private final Path cacheDir;
    private final Path manifestFile;
    private final java.util.List<String> manifest = new java.util.ArrayList<>();

    // 内存缓存：sourcePath → (rootClassName, Map<className, byte[]>)
    // 由 preloadBuiltin() 从 JAR 预置缓存填充，load() 优先命中
    // ★ static：所有 QmlCacheManager 实例共享同一份内存缓存。
    //   QmlScreen 每次打开屏幕都 new QmlCacheManager()，如果 memoryCache 是实例字段，
    //   Mod 启动时 preloadBuiltin 加载的 51 条缓存会随那个实例被 GC 而丢失，
    //   导致每次打开屏幕都要从磁盘重新读取（甚至 MISS）。
    private static final Map<String, PrebuiltEntry> memoryCache = new ConcurrentHashMap<>();

    private static final class PrebuiltEntry {
        final String rootClassName;
        final Map<String, byte[]> classes;
        PrebuiltEntry(String rootClassName, Map<String, byte[]> classes) {
            this.rootClassName = rootClassName;
            this.classes = classes;
        }
    }

    public QmlCacheManager() {
        this.cacheDir = Paths.get(AppDirs.cacheBase(), "qml-cache");
        this.manifestFile = cacheDir.resolve("manifest.txt");
        // 缓存清单路径暴露给引擎补丁（RestoreHelper 按清单恢复全部缓存文件）
        System.setProperty("qplayer.qmlcache.manifest", manifestFile.toString());
        loadManifest();
    }

    // ── 预置缓存预加载 ──────────────────────────────────────────────

    /**
     * 从 JAR classpath 读取预置缓存文件装入内存。
     * 由 QplayerModClient 在初始化时调用（设置中 qmlCachePreload 开启时）。
     *
     * @param copyToDisk  是否同时将预置缓存复制到磁盘缓存目录
     */
    public synchronized void preloadBuiltin(boolean copyToDisk) {
        try {
            java.util.List<String> entries = loadPrebuiltManifest();
            if (entries.isEmpty()) {
                QplayerModClient.LOGGER.warn("[QmlCache] prebuilt-manifest.txt not found in JAR at {}",
                    PREBUILT_ROOT + "prebuilt-manifest.txt");
                QplayerModClient.LOGGER.info("[QmlCache] Also checked resource: {} -> {}",
                    PREBUILT_ROOT + "prebuilt-manifest.txt",
                    getClass().getResource(PREBUILT_ROOT + "prebuilt-manifest.txt"));
                return;
            }
            QplayerModClient.LOGGER.info("[QmlCache] Found {} entries in prebuilt manifest", entries.size());

            // 清空旧磁盘缓存（旧版本可能写入过不完整的 manifest）→ 全量重建
            clearDiskCache();

            int loaded = 0;
            for (String entry : entries) {
                if (loadPrebuiltEntry(entry) != null) {
                    loaded++;
                    if (copyToDisk) copyToDisk(entry);
                    // 无论磁盘文件是否存在，都从文件名还原 sourcePath 写入 manifest
                    int hashAt = entry.lastIndexOf('_');
                    if (hashAt > 0) {
                        String sourcePath = entry.substring(0, hashAt).replace('_', '/');
                        noteSaved(sourcePath);
                    }
                }
            }
            QplayerModClient.LOGGER.info("[QmlCache] Preloaded {} cache entries from JAR into memory", loaded);
            QplayerModClient.LOGGER.info("[QmlCache] Disk manifest has {} entries", manifest.size());
        } catch (Exception e) {
            QplayerModClient.LOGGER.warn("[QmlCache] Preload failed: {}", e.toString());
        }
    }

    /** 清空磁盘缓存目录（含 manifest） */
    private void clearDiskCache() {
        try {
            if (!Files.isDirectory(cacheDir)) return;
            try (java.util.stream.Stream<java.nio.file.Path> s = Files.list(cacheDir)) {
                s.filter(Files::isRegularFile).forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
            }
            manifest.clear();
            Files.deleteIfExists(manifestFile);
        } catch (IOException ignored) {}
    }

    /** 读取 JAR 内预置缓存的清单文件（构建时自动生成） */
    private java.util.List<String> loadPrebuiltManifest() {
        java.util.List<String> list = new java.util.ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream(PREBUILT_ROOT + "prebuilt-manifest.txt")) {
            if (in == null) return list;
            java.util.Scanner sc = new java.util.Scanner(in, "UTF-8");
            while (sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                if (!line.isEmpty()) list.add(line);
            }
        } catch (Exception ignored) {}
        return list;
    }

    /** 加载单个预置缓存文件到内存 */
    private PrebuiltEntry loadPrebuiltEntry(String filename) {
        String path = PREBUILT_ROOT + filename;
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) return null;
            DataInputStream data = new DataInputStream(new BufferedInputStream(in));
            int version = data.readInt();
            if (version != FORMAT_VERSION) return null;
            String rootClassName = data.readUTF();
            int entryCount = data.readInt();
            Map<String, byte[]> classes = new LinkedHashMap<>();
            for (int i = 0; i < entryCount; i++) {
                String name = data.readUTF();
                int len = data.readInt();
                byte[] bytes = new byte[len];
                data.readFully(bytes);
                classes.put(name, bytes);
            }
            PrebuiltEntry entry = new PrebuiltEntry(rootClassName, classes);
            // 用 filename 作为 key（filename 就是 cacheFile 的命名）
            memoryCache.put(filename, entry);
            return entry;
        } catch (Exception e) {
            return null;
        }
    }

    /** 将预置缓存文件复制到磁盘缓存目录 */
    private void copyToDisk(String filename) {
        try {
            java.io.File target = new java.io.File(cacheDir.toFile(), filename);
            if (target.isFile()) return; // 已存在，跳过
            String path = PREBUILT_ROOT + filename;
            try (InputStream in = getClass().getResourceAsStream(path)) {
                if (in == null) return;
                Files.createDirectories(target.getParentFile().toPath());
                Files.copy(in, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // 还原真实 sourcePath：文件名 = safePath(':'/'→'_') + "_" + <sha64> + ".cache"
            // 旧的实现取第一个 '_' 截断，把 md3_Core_LinearProgress.qml_<sha>.cache 还原成
            // "md3"，写进磁盘 manifest；RestoreHelper 完整性校验按清单逐条 load 时必 MISS
            // → 整树缓存判 INCOMPLETE 清空 → 依赖类未恢复 → 打开界面 NoClassDefFoundError。
            // 正确做法：去掉末尾 "_<sha64>.cache" 后把 '_' 换回 '/'（sourcePath 还原）。
            String stem = filename;
            int hashAt = stem.lastIndexOf('_');
            if (hashAt > 0) {
                String sourcePath = stem.substring(0, hashAt).replace('_', '/');
                noteSaved(sourcePath);
            }
        } catch (Exception ignored) {}
    }

    /** 通过 sourcePath 查找内存缓存中对应的条目 */
    private PrebuiltEntry findInMemory(String sourcePath, byte[] sourceBytes) {
        if (sourceBytes == null) return null;
        String hash = sha256(sourceBytes);
        String safePath = sourcePath.replace('/', '_').replace('\\', '_');
        String key = safePath + "_" + hash + ".cache";
        return memoryCache.get(key);
    }

    // ── 原有磁盘缓存逻辑 ───────────────────────────────────────────

    private synchronized void loadManifest() {
        try {
            if (Files.isRegularFile(manifestFile)) {
                for (String line : Files.readAllLines(manifestFile, java.nio.charset.StandardCharsets.UTF_8)) {
                    if (!line.isEmpty()) manifest.add(line);
                }
            } else if (Files.isDirectory(cacheDir)) {
                // 不做删除：保留磁盘缓存文件。即使跨进程类名冲突，
                // 也只是加载时类型不匹配，不会破坏运行。
                // 构建期的 buildQmlCache 产生的缓存文件哈希一致可直接复用。
            }
        } catch (IOException ignored) {}
    }

    private synchronized void noteSaved(String sourcePath) {
        if (sourcePath == null || sourcePath.isEmpty() || manifest.contains(sourcePath)) return;
        manifest.add(sourcePath);
        try {
            Files.createDirectories(manifestFile.getParent());
            Files.write(manifestFile, manifest, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    // ── BytecodeCache 接口 ─────────────────────────────────────────

    @Override
    public Map<String, byte[]> load(String sourcePath, byte[] sourceBytes) {
        // 1. 优先内存缓存（预置缓存）
        PrebuiltEntry mem = findInMemory(sourcePath, sourceBytes);
        if (mem != null) {
            QplayerModClient.LOGGER.info("[QmlCache] HIT memory: {}", sourcePath);
            return Collections.unmodifiableMap(mem.classes);
        }
        // 2. 磁盘缓存
        Path entry = cacheFile(sourcePath, sourceBytes);
        if (!Files.isRegularFile(entry)) {
            QplayerModClient.LOGGER.info("[QmlCache] MISS for {} (no disk cache, memCacheSize={})", sourcePath, memoryCache.size());
            return null;
        }
        noteSaved(sourcePath);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(entry)))) {
            int version = in.readInt();
            if (version != FORMAT_VERSION) return null;
            in.readUTF();
            int entryCount = in.readInt();
            Map<String, byte[]> classes = new LinkedHashMap<>();
            for (int i = 0; i < entryCount; i++) {
                String name = in.readUTF();
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                classes.put(name, bytes);
            }
            return classes;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String rootClassName(String sourcePath, byte[] sourceBytes) {
        // 1. 内存缓存
        PrebuiltEntry mem = findInMemory(sourcePath, sourceBytes);
        if (mem != null) {
            QplayerModClient.LOGGER.info("[QmlCache] rootClassName HIT memory: {}", sourcePath);
            return mem.rootClassName;
        }
        // 2. 磁盘缓存
        Path entry = cacheFile(sourcePath, sourceBytes);
        if (!Files.isRegularFile(entry)) {
            QplayerModClient.LOGGER.info("[QmlCache] rootClassName MISS: {} (memCacheSize={})", sourcePath, memoryCache.size());
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(entry)))) {
            int version = in.readInt();
            if (version != FORMAT_VERSION) return null;
            return in.readUTF();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void save(String sourcePath, byte[] sourceBytes, String rootClassName,
                     Map<String, byte[]> allClassBytes) {
        Path entry = cacheFile(sourcePath, sourceBytes);
        try {
            Files.createDirectories(entry.getParent());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(entry)))) {
                out.writeInt(FORMAT_VERSION);
                out.writeUTF(rootClassName);
                out.writeInt(allClassBytes.size());
                for (Map.Entry<String, byte[]> e : allClassBytes.entrySet()) {
                    out.writeUTF(e.getKey());
                    out.writeInt(e.getValue().length);
                    out.write(e.getValue());
                }
            }
            noteSaved(sourcePath);
        } catch (IOException ignored) {}
    }

    private Path cacheFile(String sourcePath, byte[] sourceBytes) {
        String hash = sha256(sourceBytes);
        String safePath = sourcePath.replace('/', '_').replace('\\', '_');
        return cacheDir.resolve(safePath + "_" + hash + ".cache");
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }
}