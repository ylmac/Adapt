package com.ylmac.adapt.ui;
import com.ylmac.adapt.config.DesktopSettings;
import com.ylmac.adapt.features.music.util.Logger;
import com.ylmac.adapt.features.music.PlayerController;
import io.github.timer_err.qml4j.render.RestoreHelper;

/**
 * 构建期 QML 字节码缓存生成器。
 *
 * 在 Gradle build 时运行，使用 qml4j 引擎编译所有 QML 文件，
 * 将编译产物（.class 字节码）保存到指定输出目录，
 * 最终被打包进 JAR 作为 assets/adaptclient/qml-cache/*.cache。
 *
 * 运行方式（由 Gradle buildQmlCache task 自动调用）：
 *   java -cp <classpath> QmlCacheBuilder <qmlDir> <outputDir> <assetsDir>
 *
 * 参数：
 *   qmlDir    — QML 源文件根目录（如 build/resources/main/assets/adaptclient/qml）
 *   outputDir — 缓存输出目录（如 build/resources/main/assets/adaptclient/qml-cache）
 *   assetsDir — 资源目录用于加载字体（如 build/resources/main/assets）
 */
public class QmlCacheBuilder {

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger("qplayer-cache-builder");

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: QmlCacheBuilder <qmlDir> <outputDir> <assetsDir>");
            System.exit(1);
        }
        java.io.File qmlDir = new java.io.File(args[0]);
        java.io.File outputDir = new java.io.File(args[1]);
        java.io.File assetsDir = new java.io.File(args[2]);

        if (!qmlDir.isDirectory()) {
            System.err.println("QML dir not found: " + qmlDir);
            System.exit(1);
        }
        outputDir.mkdirs();

        // 设置资源加载路径，使 QplayerResourceLoader 能从 assetsDir 加载字体
        System.setProperty("qplayer.assets.dir", assetsDir.getAbsolutePath());

        LOG.info("Building QML cache from {} → {}", qmlDir, outputDir);

        // 1. 创建引擎 + 视图
        io.github.timer_err.qml4j.engine.QmlEngine engine =
            new io.github.timer_err.qml4j.engine.QmlEngine();
        io.github.timer_err.qml4j.render.QmlView view =
            io.github.timer_err.qml4j.render.QmlView.withStockTypes(engine);

        // 2. 资源加载器（从 qmlDir 读取 QML 文件）
        view.resources(new BuildTimeResourceLoader(qmlDir));

        // 3. 字节码缓存（写 JAR 资源目录）
        view.bytecodeCache(new BuildCache(outputDir));

        // 4. 上下文对象（QML 编译期需要它们解析标识符）
        com.ylmac.adapt.config.DesktopSettings settings =
            new com.ylmac.adapt.config.DesktopSettings();
        com.ylmac.adapt.features.music.PlayerController player =
            new com.ylmac.adapt.features.music.PlayerController(settings);
        view.context("settings", settings);
        view.context("player", player);
        view.context("gpuBackend", "build");
        view.context("gpuName", "build");
        view.context("gpuStatus", "build");
        // ClickGUI 上下文（ClickGuiScreen 也加载此 QML）
        view.context("bridge", com.ylmac.adapt.render.clickgui.ClickGuiBridge.INSTANCE);
        // ModuleList HUD 上下文（ModuleListOverlay 加载此 QML）
        view.context("moduleList", new com.ylmac.adapt.render.ModuleListBridge());

        // 5. 加载字体（QML 编译期需要字体信息）
        loadFonts(view);

        // 6. 编译每个 QML 文件
        compileAll(view, qmlDir);

        view.dispose();
        LOG.info("QML cache build complete at {}", outputDir);
    }

    private static void compileAll(io.github.timer_err.qml4j.render.QmlView view,
                                   java.io.File qmlDir) throws Exception {
        // Main.qml 是入口，它 import 所有页面和组件，引擎会递归编译依赖
        java.io.File mainQml = new java.io.File(qmlDir, "Main.qml");
        if (!mainQml.isFile()) {
            LOG.warn("Main.qml not found in {}", qmlDir);
            return;
        }
        String qml = new String(java.nio.file.Files.readAllBytes(mainQml.toPath()),
                                java.nio.charset.StandardCharsets.UTF_8);
        long t0 = System.nanoTime();
        boolean ok = attemptLoad(view, qml);
        if (!ok) {
            // 依赖子缓存通常已落盘（递归编译时各自 save）——再试一次走缓存命中路径，
            // 此时依赖类已由 RestoreHelper 定义，Main 根类即可正常实例化并写根缓存。
            LOG.info("Main.qml first instantiate deferred (deps caching) — retrying...");
            ok = attemptLoad(view, qml);
        }
        LOG.info("Compiled Main.qml {} ({}ms)", ok ? "OK" : "DEFERRED to runtime",
            (System.nanoTime() - t0) / 1_000_000);

        // 同样编译 ClickGui.qml（供 ClickGuiScreen 使用）
        java.io.File clickGuiQml = new java.io.File(qmlDir, "clickgui/ClickGui.qml");
        if (clickGuiQml.isFile()) {
            String clickGuiSrc = new String(java.nio.file.Files.readAllBytes(clickGuiQml.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
            long t1 = System.nanoTime();
            boolean clickOk = false;
            // baseDir 为文档所在目录，确保 import "." 可正确解析
            try {
                clickOk = loadAndConverge(view, clickGuiSrc, "clickgui");
            } catch (Throwable t) {
                LOG.warn("ClickGui.qml instantiate failed (retry/runtime): {}", t.toString());
                try {
                    clickOk = loadAndConverge(view, clickGuiSrc, "clickgui");
                } catch (Throwable t2) {
                    LOG.warn("ClickGui.qml second attempt also failed: {}", t2.toString());
                }
            }
            LOG.info("Compiled ClickGui.qml {} ({}ms)", clickOk ? "OK" : "DEFERRED to runtime",
                (System.nanoTime() - t1) / 1_000_000);
        } else {
            LOG.warn("clickgui/ClickGui.qml not found, skipping");
        }

        // 同样编译 ModuleList.qml（供 ModuleListOverlay HUD 使用）
        java.io.File moduleListQml = new java.io.File(qmlDir, "modulelist/ModuleList.qml");
        if (moduleListQml.isFile()) {
            String moduleListSrc = new String(java.nio.file.Files.readAllBytes(moduleListQml.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
            long t2 = System.nanoTime();
            boolean moduleListOk = false;
            try {
                view.load(moduleListSrc, "modulelist");
                moduleListOk = true;
            } catch (Throwable t) {
                LOG.warn("ModuleList.qml instantiate failed (retry/runtime): {}", t.toString());
                try {
                    view.load(moduleListSrc, "modulelist");
                    moduleListOk = true;
                } catch (Throwable t2b) {
                    LOG.warn("ModuleList.qml second attempt also failed: {}", t2b.toString());
                }
            }
            LOG.info("Compiled ModuleList.qml {} ({}ms)", moduleListOk ? "OK" : "DEFERRED to runtime",
                (System.nanoTime() - t2) / 1_000_000);
        } else {
            LOG.warn("modulelist/ModuleList.qml not found, skipping");
        }

        // 独立组件（没有任何场景引用它们，但要求可复用）——必须单独预编译，
        // 否则它们永远不进缓存、也从未被编译器验证过语法。
        // 加入方式：往 STANDALONE_COMPONENTS 里加相对 qmlDir 的路径。
        for (String rel : STANDALONE_COMPONENTS) {
            java.io.File f = new java.io.File(qmlDir, rel);
            if (!f.isFile()) {
                LOG.warn("standalone component {} not found, skipping", rel);
                continue;
            }
            try {
                String src = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                String baseDir = rel.contains("/")
                        ? rel.substring(0, rel.lastIndexOf('/')) : "";
                view.load(src, baseDir);
                LOG.info("Compiled standalone component {} OK", rel);
            } catch (Throwable t) {
                LOG.warn("standalone component {} failed: {}", rel, t.toString());
            }
        }

    }

    /**
     * 需要预编译但没被任何场景 import 的可复用组件（相对 qml 根目录）。
     * <p>
     * 引擎只递归编译场景根文档 import 到的文件，所以"库组件"如果暂时没人用，
     * 既不会进缓存、也不会被编译器检查 —— 等第一次真正用到时才在运行时暴露
     * 语法错误。列在这里可以提前验证 + 预热缓存。
     */
    private static final String[] STANDALONE_COMPONENTS = {
            "md3/Core/CornerHandle.qml",
    };

    private static boolean attemptLoad(io.github.timer_err.qml4j.render.QmlView view, String qml) {
        try {
            view.load(qml, "");
            return true;
        } catch (Throwable t) {
            // NoClassDefFoundError (跨文件全局类计数错位) 先于 Main 根缓存写出：
            // 子缓存已保存，重试可收敛；真实语法错误等会在重试后继续暴露。
            LOG.warn("Main.qml instantiate failed (retry/compile-on-runtime): {}", t.toString());
            return false;
        }
    }

    /**
     * 编译 QML + 构建期布局收敛（与 QmlScreen.warmupLayout 同款）。
     * <p>
     * 收敛的意义：qml4j 的 Repeater 生成 delegate 是异步（rebuildTask 排入 DirtyQueue），
     * 一轮 layout 只能实例化一层；动态内容（模块页面/设置行）的 implicitHeight 需要多轮
     * layout 才稳定。构建期收敛 30 轮 → 缓存里就是稳定布局 → 运行时打开 ClickGUI
     * 零收敛延迟（否则设置覆盖层打开时高度逐步增长 → "板子上移/拽不下来"）。
     */
    private static boolean loadAndConverge(io.github.timer_err.qml4j.render.QmlView view,
                                           String qml, String baseDir) {
        try {
            io.github.timer_err.qml4j.render.items.core.Item root = view.load(qml, baseDir);
            if (root == null) return false;
            io.github.timer_err.qml4j.engine.binding.DirtyQueue dq = new io.github.timer_err.qml4j.engine.binding.DirtyQueue();
            dq.install();
            try {
                for (int i = 0; i < 30; i++) {
                    dq.flush();
                    view.renderer().layoutOnly(root);
                    dq.flush();
                }
            } finally {
                dq.uninstall();
            }
            return true;
        } catch (Throwable t) {
            LOG.warn("ClickGui.qml load/converge failed: {}", t.toString());
            return false;
        }
    }

    private static void loadFonts(io.github.timer_err.qml4j.render.QmlView view) {
        String assetsDir = System.getProperty("qplayer.assets.dir");
        if (assetsDir == null) return;
        java.io.File base = new java.io.File(assetsDir,
            "assets/adaptclient/qml/fonts");
        if (!base.isDirectory()) return;
        try {
            byte[] regular = readFile(new java.io.File(base, "PingFangSC-Regular.otf"));
            byte[] medium  = readFile(new java.io.File(base, "PingFangSC-Medium.otf"));
            if (regular != null && medium != null) view.uiTypefaces(regular, medium);
            byte[] icon = readFile(new java.io.File(base, "MaterialSymbolsRounded.ttf"));
            if (icon != null) view.iconTypeface(icon);
        } catch (Exception e) {
            LOG.warn("Font load failed: {}", e.toString());
        }
    }

    private static byte[] readFile(java.io.File f) {
        if (!f.isFile()) return null;
        try {
            return java.nio.file.Files.readAllBytes(f.toPath());
        } catch (Exception e) {
            return null;
        }
    }

    // —— 构建期专用的 ResourceLoader（从 qmlDir 读，不从 classpath） ——

    private static final class BuildTimeResourceLoader
            implements io.github.timer_err.qml4j.render.ResourceLoader {
        private final java.io.File qmlDir;
        BuildTimeResourceLoader(java.io.File qmlDir) { this.qmlDir = qmlDir; }

        @Override
        public byte[] load(String source) {
            if (source == null) return null;
            // 先尝试从 qmlDir 直接读取
            java.io.File f = new java.io.File(qmlDir, source);
            if (f.isFile()) {
                try { return java.nio.file.Files.readAllBytes(f.toPath()); }
                catch (Exception ignored) {}
            }
            // 回退到 classpath（md3 qmldir 等）
            try (java.io.InputStream in = getClass().getResourceAsStream(
                    "/assets/adaptclient/qml/" + source)) {
                if (in == null) return null;
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return out.toByteArray();
            } catch (Exception e) {
                return null;
            }
        }
    }

    // —— 构建期专用的 BytecodeCache（写 outputDir，不写用户缓存目录） ——

    private static final class BuildCache
            implements io.github.timer_err.qml4j.engine.BytecodeCache {
        private final java.io.File outputDir;
        BuildCache(java.io.File outputDir) { this.outputDir = outputDir; }

        private java.io.File cacheFile(String sourcePath, byte[] sourceBytes) {
            String hash = sha256(sourceBytes);
            String safe = sourcePath.replace('/', '_').replace('\\', '_');
            return new java.io.File(outputDir, safe + "_" + hash + ".cache");
        }

        @Override
        public java.util.Map<String, byte[]> load(String sourcePath, byte[] sourceBytes) {
            java.io.File f = cacheFile(sourcePath, sourceBytes);
            if (!f.isFile()) {
                // 如果 sourcePath 包含路径分隔符，尝试用 "" 生成的 cache 文件名回退
                // （构建期 save 时可能以空 sourcePath 写入，运行时 load 则带路径）
                if (sourcePath.contains("/") || sourcePath.contains("\\")) {
                    java.io.File fallback = cacheFile("", sourceBytes);
                    if (fallback.isFile()) f = fallback;
                }
            }
            if (!f.isFile()) return null;
            try (java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.BufferedInputStream(
                        new java.io.FileInputStream(f)))) {
                int version = in.readInt();
                if (version != 2) return null;
                in.readUTF(); // skip rootClassName
                int count = in.readInt();
                java.util.Map<String, byte[]> map = new java.util.LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    String name = in.readUTF();
                    int len = in.readInt();
                    byte[] bytes = new byte[len];
                    in.readFully(bytes);
                    map.put(name, bytes);
                }
                return map;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public String rootClassName(String sourcePath, byte[] sourceBytes) {
            java.io.File f = cacheFile(sourcePath, sourceBytes);
            if (!f.isFile()) {
                if (sourcePath.contains("/") || sourcePath.contains("\\")) {
                    java.io.File fallback = cacheFile("", sourceBytes);
                    if (fallback.isFile()) f = fallback;
                }
            }
            if (!f.isFile()) return null;
            try (java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.BufferedInputStream(
                        new java.io.FileInputStream(f)))) {
                int version = in.readInt();
                if (version != 2) return null;
                return in.readUTF();
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void save(String sourcePath, byte[] sourceBytes,
                         String rootClassName,
                         java.util.Map<String, byte[]> allClassBytes) {
            java.io.File f = cacheFile(sourcePath, sourceBytes);
            f.getParentFile().mkdirs();
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(
                        new java.io.FileOutputStream(f)))) {
                out.writeInt(2); // FORMAT_VERSION
                out.writeUTF(rootClassName);
                out.writeInt(allClassBytes.size());
                for (java.util.Map.Entry<String, byte[]> e : allClassBytes.entrySet()) {
                    out.writeUTF(e.getKey());
                    out.writeInt(e.getValue().length);
                    out.write(e.getValue());
                }
            } catch (Exception ignored) {}
        }

        private static String sha256(byte[] data) {
            try {
                java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest(data);
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                return Integer.toHexString(java.util.Arrays.hashCode(data));
            }
        }
    }
}