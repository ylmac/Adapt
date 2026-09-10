package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.engine.QmlEngine;

import java.util.List;
import java.util.Map;

/**
 * qml4j 缓存恢复补丁辅助类（与 Loader 同包，随 qml4j jar 解包进 mod JAR）。
 *
 * <p>职责：在运行时首次编译根文档之前，把构建期缓存的 QML 组件字节码
 * （Component$N 等）全部 defineClass 进当前 QmlView 的类加载器，并把每个
 * 组件的根类注册进 Loader.importedTypes（按 sourcePath），同时把 componentCounter
 * 推进到 maxRestored+1，保证运行时编译根文档生成的类名不会与缓存类名冲突。
 *
 * <p>依赖顺序：缓存条目之间存在跨文件 superclass 依赖（例如
 * SongRow.qml 的 Component$11$Delegate$0 extends Component$12，而
 * Component$12 属于 SongContextMenu.qml）。restore 采用多轮扫描：
 * 每轮把"所有依赖已满足"的条目全部恢复，失败条目进入下一轮，直到收敛。
 * 重试时对失败条目做整条目重试：已 define 成功的孤儿类在下一轮会被
 * LinkageError 容忍（类已在加载器命名空间，直接 Class.forName 复用）。
 */
public final class RestoreHelper {

    private RestoreHelper() {}

    private static java.lang.reflect.Field fResources;
    private static java.lang.reflect.Field fEngine;
    private static java.lang.reflect.Field fImported;
    private static java.lang.reflect.Field fSingletons;
    private static java.lang.reflect.Field fCompilerField;
    private static java.lang.reflect.Field fCounterField;
    private static java.lang.reflect.Method mLoadQmldir;
    private static java.lang.reflect.Method mNewRoot;
    private static java.lang.reflect.Field fQmldirFile;
    private static java.lang.reflect.Field fQmldirSingleton;

    private static final java.util.Set<Loader> restoredLoaders =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

    /** 已做过 singleton 同步的 Loader（记录已同步过的前缀，避免重复） */
    private static final java.util.Map<Loader, java.util.Set<String>> singletonSynced =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    /**
     * 恢复全部缓存类。可能在两个时机被调用：
     * 1. QmlScreen/QplayerScreen 在 load 之前调用 restoreAllFor → restoreAll(loader, null)；
     * 2. 补丁后的 Loader.instantiate 解析完 import 后调用 restoreAll(loader, 真实前缀)。
     * 第一次调用做完整恢复（defineClass + 推进计数器）；之后带前缀再次调用时，
     * 只需做 singleton 同步（把 md3/Core 等前缀下的 singleton 组件注册进
     * singletonsByPrefix，供编译期 registerKnownSingletons 使用）。
     */
    public static void restoreAll(Loader loader, List<String> prefixes) {
        boolean first = restoredLoaders.add(loader);
        if (first) {
            restore(loader, null, prefixes);
        } else {
            syncSingletons(loader, prefixes);
        }
    }

    /**
     * 从 QmlView 反射取 Loader 并恢复全部缓存类。
     * 必须在 QmlView 创建后、首次 load() 之前调用，否则运行时编译根文档时
     * 子组件类未 define、componentCounter 未推进 → NoClassDefFoundError。
     */
    public static void restoreAllFor(QmlView view) {
        if (view == null) return;
        try {
            java.lang.reflect.Field f = QmlView.class.getDeclaredField("loader");
            f.setAccessible(true);
            Object l = f.get(view);
            if (l instanceof Loader) {
                restoreAll((Loader) l, null);
            }
        } catch (Throwable t) {
            System.out.println("[RH] restoreAllFor failed: " + t);
        }
    }

    public static void restore(Loader loader, String sourcePath, List<String> prefixes) {
        try {
            initReflection();
            ResourceLoader resources = (ResourceLoader) fResources.get(loader);
            QmlEngine engine = (QmlEngine) fEngine.get(loader);
            @SuppressWarnings("unchecked")
            Map<String, Class<? extends io.github.timer_err.qml4j.engine.QObject>> importedTypes =
                    (Map<String, Class<? extends io.github.timer_err.qml4j.engine.QObject>>) fImported.get(loader);

            String manifestPath = System.getProperty("qplayer.qmlcache.manifest");
            if (manifestPath == null) {
                System.out.println("[RH-DBG] No manifest property → skip restore");
                return;
            }
            java.nio.file.Path mf = java.nio.file.Path.of(manifestPath);
            if (!java.nio.file.Files.isRegularFile(mf)) {
                System.out.println("[RH-DBG] Manifest file not found: " + manifestPath + " → skip restore");
                return;
            }
            List<String> entries = java.nio.file.Files.readAllLines(mf, java.nio.charset.StandardCharsets.UTF_8);
            if (entries.isEmpty()) {
                System.out.println("[RH-DBG] Manifest empty → skip restore");
                return;
            }

            System.out.println("[RH-DBG] Manifest has " + entries.size() + " entries (restore order is dependency-driven)");

            // 缓存完整性校验：清单里每条都要能从 BytecodeCache 读出完整字节码，
            // 否则磁盘缓存不完整 → 清空重建（下次构建后自愈）。
            for (String full : entries) {
                byte[] src = resources.load(full);
                if (src == null) {
                    System.out.println("[RH-DBG] WARNING: resource not found for " + full);
                    continue;
                }
                if (Loader.bytecodeCache.load(full, src) == null) {
                    System.out.println("[RH] CACHE-INCOMPLETE " + full + ", 清空重建");
                    clearCacheDir(mf.getParent());
                    return;
                }
            }

            // 多轮扫描恢复：每轮处理所有"当前依赖已满足"的条目；
            // 失败（依赖缺失）的条目整体推迟到下一轮，避免孤儿类累积。
            java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>(entries);
            java.util.Set<String> done = new java.util.HashSet<>();
            if (sourcePath != null) done.add(sourcePath);
            java.util.List<String> retry = new java.util.ArrayList<>();
            int rounds = 0;
            int successCount = 0;
            int failCount = 0;
            while (!pending.isEmpty() || !retry.isEmpty()) {
                if (pending.isEmpty()) {
                    if (++rounds >= 16) {
                        System.out.println("[RH-DBG] Max retries (16) reached. " + successCount + " ok, " + failCount + " failed");
                        break;
                    }
                    System.out.println("[RH-DBG] Round " + rounds + " retry: " + retry.size() + " entries");
                    pending.addAll(retry);
                    retry.clear();
                }
                String full = pending.poll();
                if (!done.add(full)) continue;
                if (!restoreOne(loader, full, resources, engine, importedTypes, prefixes)) {
                    done.remove(full);
                    retry.add(full);
                    failCount++;
                    // 依赖预取：失败条目的字节码里可能引用其它缓存文件里的
                    // Component$N 超类（跨文件 super 依赖）。把它们所在的条目
                    // 插到 pending 队首，下一轮先恢复依赖，避免无意义的重试。
                    java.util.List<String> deps = missingSuperDependencies(resources, full);
                    for (int di = deps.size() - 1; di >= 0; --di) {
                        String dep = deps.get(di);
                        if (!done.contains(dep)) pending.addFirst(dep);
                    }
                } else {
                    successCount++;
                }
            }

            System.out.println("[RH-DBG] Restore done: " + successCount + " ok, " + failCount + " failed, " + rounds + " rounds");
            System.out.println("[RH-DBG] importedTypes has " + importedTypes.size() + " entries");
            for (Map.Entry<String, Class<? extends io.github.timer_err.qml4j.engine.QObject>> e : importedTypes.entrySet()) {
                System.out.println("[RH-DBG]   " + e.getKey() + " → " + e.getValue().getName());
            }

            advanceComponentCounter(loader, importedTypes);
        } catch (Throwable t) {
            System.out.println("[RH-DBG] restore() FAILED: " + t);
            t.printStackTrace();
        }
    }

    /**
     * singleton 同步：把清单里所有"属于给定前缀、且在 qmldir 里声明为 singleton"
     * 的组件注册进 Loader.singletonsByPrefix。完整恢复（restore）已在第一次调用时
     * 完成；这里的职责是把 importedTypes 里已有的组件按前缀登记为 singleton，
     * 这样 Loader.compileAndDefine → registerKnownSingletons 就能把 Theme 等
     * singleton 注册进编译期 TypeRegistry，根文档编译时 Theme.color.* 可解析。
     */
    @SuppressWarnings("unchecked")
    private static void syncSingletons(Loader loader, List<String> prefixes) {
        try {
            if (prefixes == null || prefixes.isEmpty()) return;
            initReflection();
            java.util.Set<String> synced = singletonSynced.computeIfAbsent(loader, k -> new java.util.HashSet<>());
            Map<String, Class<? extends io.github.timer_err.qml4j.engine.QObject>> importedTypes =
                    (Map<String, Class<? extends io.github.timer_err.qml4j.engine.QObject>>) fImported.get(loader);
            for (String p : prefixes) {
                if (p == null || p.isEmpty()) continue;
                if (!synced.add(p)) continue;
                Map<?, ?> qmldir = (Map<?, ?>) mLoadQmldir.invoke(loader, p);
                if (qmldir == null) continue;
                for (Object o : qmldir.entrySet()) {
                    Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                    Object entry = e.getValue();
                    if (entry == null || !Boolean.TRUE.equals(fQmldirSingleton.get(entry))) continue;
                    String typeName = (String) e.getKey();
                    // 组件文件通常是 <prefix>/<TypeName>.qml（如 md3/Core/Theme.qml）
                    String file = (String) fQmldirFile.get(entry);
                    String base = file != null ? p + "/" + file : p + "/" + typeName + ".qml";
                    if (base.startsWith("/")) base = base.substring(1);
                    // importedTypes 的 key 带 .qml（restoreOne 用 manifest 条目原样写入）
                    Class<? extends io.github.timer_err.qml4j.engine.QObject> clazz = importedTypes.get(base);
                    if (clazz == null && base.endsWith(".qml")) {
                        clazz = importedTypes.get(base.substring(0, base.length() - 4));
                    }
                    if (clazz == null) continue;
                    Map<String, Map<String, Class<? extends io.github.timer_err.qml4j.engine.QObject>>> s =
                            (Map<String, Map<String, Class<? extends io.github.timer_err.qml4j.engine.QObject>>>) fSingletons.get(loader);
                    s.computeIfAbsent(p, k -> new java.util.HashMap<>()).put(typeName, clazz);
                    System.out.println("[RH-DBG] syncSingleton: " + p + " → " + typeName + " = " + clazz.getName());
                }
            }
        } catch (Throwable t) {
            System.out.println("[RH] SYNC-SINGLETONS-FAIL: " + t);
        }
    }

    /** 清空缓存目录（含 manifest）：下次加载全量编译重建 */
    static void clearCacheDir(java.nio.file.Path cacheDir) {
        try {
            if (cacheDir == null || !java.nio.file.Files.isDirectory(cacheDir)) return;
            try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(cacheDir)) {
                s.filter(java.nio.file.Files::isRegularFile).forEach(p -> {
                    try { java.nio.file.Files.delete(p); } catch (java.io.IOException ignored) {}
                });
            }
        } catch (java.io.IOException ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void advanceComponentCounter(Loader loader, Map<String, Class<? extends io.github.timer_err.qml4j.engine.QObject>> importedTypes) {
        try {
            if (fCompilerField == null) {
                fCompilerField = Loader.class.getDeclaredField("compiler");
                fCompilerField.setAccessible(true);
            }
            Object compiler = fCompilerField.get(loader);
            if (compiler == null) return;
            if (fCounterField == null) {
                fCounterField = compiler.getClass().getDeclaredField("componentCounter");
                fCounterField.setAccessible(true);
            }
            Object counter = fCounterField.get(compiler);
            if (!(counter instanceof java.util.concurrent.atomic.AtomicInteger)) return;
            int maxN = -1;
            for (Class<?> c : importedTypes.values()) {
                int n = componentNum(c.getName());
                if (n >= 0 && n > maxN) maxN = n;
            }
            // 还扫描单例注册表（singletonsByPrefix）里 restore 时登记的类
            try {
                Object sMap = fSingletons.get(loader);
                if (sMap instanceof Map) {
                    for (Object v : ((Map<?, ?>) sMap).values()) {
                        if (v instanceof Map) {
                            for (Object cc : ((Map<?, ?>) v).values()) {
                                if (cc instanceof Class) {
                                    int n = componentNum(((Class<?>) cc).getName());
                                    if (n >= 0 && n > maxN) maxN = n;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            if (maxN >= 0) {
                java.util.concurrent.atomic.AtomicInteger ai = (java.util.concurrent.atomic.AtomicInteger) counter;
                int old = ai.get();
                ai.set(Math.max(old, maxN + 1));
                System.out.println("[RH-DBG] ComponentCounter: old=" + old + " maxRestored=" + maxN + " new=" + ai.get());
            } else {
                System.out.println("[RH-DBG] ComponentCounter: no restored components found, counter stays at " + counter);
            }
        } catch (Throwable ignored) {}
    }

    static int componentNum(String className) {
        int idx = className.lastIndexOf("Component$");
        if (idx < 0) return -1;
        int i = idx + "Component$".length();
        int num = 0;
        boolean any = false;
        while (i < className.length() && Character.isDigit(className.charAt(i))) {
            num = num * 10 + (className.charAt(i) - '0');
            any = true;
            i++;
        }
        return any ? num : -1;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean restoreOne(Loader loader, String full,
                                      ResourceLoader resources, QmlEngine engine,
                                      Map<String, Class<? extends io.github.timer_err.qml4j.engine.QObject>> importedTypes,
                                      List<String> prefixes) {
        try {
            if (importedTypes.containsKey(full)) return true;
            byte[] src = resources.load(full);
            if (src == null) return true;
            Map<String, byte[]> classes = Loader.bytecodeCache.load(full, src);
            if (classes == null) return true;
            String rootName = Loader.bytecodeCache.rootClassName(full, src);
            System.out.println("[RH-DBG] restoreOne: " + full + " rootClass=" + rootName + " totalClasses=" + classes.size());

            Class<?> root = null;
            boolean missing = false;
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                String className = e.getKey();
                String superName = readSuperClass(e.getValue());
                try {
                    Class<?> c = engine.backend().defineClass(className, e.getValue());
                    System.out.println("[RH-DBG]   defineClass OK: " + className + " (super: " + superName + ")");
                    if (className.equals(rootName)) root = c;
                } catch (NoClassDefFoundError ncdfe) {
                    missing = true;
                    System.out.println("[RH-DBG]   defineClass FAIL: " + className + " (super: " + superName + ") → NCDFE: " + ncdfe.getMessage());
                    break;
                } catch (LinkageError le) {
                    // 重试轮：该类已由上一轮 define 成功 → 直接复用命名空间里已有的类
                    try {
                        Class<?> existing = Class.forName(className, false,
                                ((io.github.timer_err.qml4j.engine.classloader.JvmClassLoaderBackend) engine.backend()).classLoader());
                        System.out.println("[RH-DBG]   LinkageError (dup), reused existing: " + className);
                        if (className.equals(rootName) && root == null) root = existing;
                    } catch (Throwable t2) {
                        System.out.println("[RH-DBG]   LinkageError (dup, no existing): " + className + " → " + t2);
                    }
                }
            }
            if (missing) return false;
            if (root == null || !io.github.timer_err.qml4j.engine.QObject.class.isAssignableFrom(root)) return true;
            @SuppressWarnings("unchecked")
            Class<? extends io.github.timer_err.qml4j.engine.QObject> rootQ =
                    (Class<? extends io.github.timer_err.qml4j.engine.QObject>) root;
            importedTypes.put(full, rootQ);
            registerSingletonIfNeeded(loader, full, rootQ, prefixes);
            return true;
        } catch (Throwable t) {
            System.out.println("[RH] FAIL " + full + ": " + t);
            return false;
        }
    }

    /**
     * 扫描一个缓存条目（可能含多字节码）里所有"超类是 io/github/timer_err/qml4j/generated/Component$N"
     * 的类，返回缺失依赖所在的缓存条目清单（在 manifest 中查找包含该类的条目）。
     * 用于跨文件 super 依赖预取：Component$8/$9 等属于别的 qml 文件。
     */
    private static java.util.List<String> missingSuperDependencies(ResourceLoader resources, String full) {
        java.util.List<String> deps = new java.util.ArrayList<>();
        try {
            byte[] src = resources.load(full);
            if (src == null) return deps;
            Map<String, byte[]> classes = Loader.bytecodeCache.load(full, src);
            if (classes == null) return deps;
            java.util.Set<String> missingNums = new java.util.LinkedHashSet<>();
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                String superName = readSuperClass(e.getValue());
                if (superName == null) continue;
                int idx = superName.lastIndexOf("Component$");
                if (idx < 0) continue;
                String num = superName.substring(idx + "Component$".length());
                // 去掉可能的内部类后缀（如 Component$9$Child$1 → 9）
                int cut = 0;
                while (cut < num.length() && Character.isDigit(num.charAt(cut))) cut++;
                if (cut == 0) continue;
                String n = num.substring(0, cut);
                // 只关心不在当前条目里的依赖（当前条目的内部类已由 defineClass 顺序保证）
                if (!classes.containsKey(superName.replace('/', '.'))) {
                    missingNums.add(n);
                }
            }
            if (missingNums.isEmpty()) return deps;
            // 在 manifest 里找包含这些 Component$N 的条目
            String manifestPath = System.getProperty("qplayer.qmlcache.manifest");
            if (manifestPath == null) return deps;
            java.nio.file.Path mf = java.nio.file.Path.of(manifestPath);
            if (!java.nio.file.Files.isRegularFile(mf)) return deps;
            for (String entry : java.nio.file.Files.readAllLines(mf, java.nio.charset.StandardCharsets.UTF_8)) {
                if (entry.equals(full)) continue;
                byte[] esrc = resources.load(entry);
                if (esrc == null) continue;
                Map<String, byte[]> eclasses = Loader.bytecodeCache.load(entry, esrc);
                if (eclasses == null) continue;
                for (String cn : eclasses.keySet()) {
                    int idx = cn.lastIndexOf("Component$");
                    if (idx < 0) continue;
                    String num = cn.substring(idx + "Component$".length());
                    int cut = 0;
                    while (cut < num.length() && Character.isDigit(num.charAt(cut))) cut++;
                    if (cut == 0) continue;
                    String n = num.substring(0, cut);
                    if (missingNums.contains(n)) {
                        deps.add(entry);
                        break;
                    }
                }
            }
            if (!deps.isEmpty()) {
                System.out.println("[RH-DBG] missingSuperDeps(" + full + ") → " + deps);
            }
        } catch (Throwable t) {
            System.out.println("[RH] missingSuperDependencies FAIL " + full + ": " + t);
        }
        return deps;
    }

    /** 从 class 文件字节码读取超类名（不加载类） */
    private static String readSuperClass(byte[] bytes) {
        if (bytes == null || bytes.length < 10) return "?";
        try {
            int idx = 8; // skip magic + version
            int cpCount = ((bytes[idx] & 0xFF) << 8) | (bytes[idx + 1] & 0xFF);
            idx += 2;
            // skip constant pool
            String[] utf8s = new String[cpCount];
            int[] classRefs = new int[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int tag = bytes[idx] & 0xFF;
                idx++;
                switch (tag) {
                    case 1: // CONSTANT_Utf8
                        int len = ((bytes[idx] & 0xFF) << 8) | (bytes[idx + 1] & 0xFF);
                        idx += 2;
                        utf8s[i] = new String(bytes, idx, len, java.nio.charset.StandardCharsets.UTF_8);
                        idx += len;
                        break;
                    case 3: case 4: // Integer/Float
                        idx += 4; break;
                    case 5: case 6: // Long/Double
                        idx += 8; i++; break;
                    case 7: // CONSTANT_Class
                        classRefs[i] = ((bytes[idx] & 0xFF) << 8) | (bytes[idx + 1] & 0xFF);
                        idx += 2; break;
                    case 8: // CONSTANT_String
                        idx += 2; break;
                    case 9: case 10: case 11: // Field/Method/InterfaceMethod
                        idx += 4; break;
                    case 12: // NameAndType
                        idx += 4; break;
                    case 15: // MethodHandle
                        idx += 3; break;
                    case 16: // MethodType
                        idx += 2; break;
                    case 17: // Dynamic
                        idx += 4; break;
                    case 18: // InvokeDynamic
                        idx += 4; break;
                    default:
                        return "? (unknown tag " + tag + " at cp[" + i + "]";
                }
            }
            // access_flags (2), this_class (2), super_class (2)
            idx += 2; // access flags
            idx += 2; // this_class
            int superClassIdx = ((bytes[idx] & 0xFF) << 8) | (bytes[idx + 1] & 0xFF);
            if (superClassIdx == 0) return "java/lang/Object";
            int utf8Idx = classRefs[superClassIdx];
            if (utf8Idx > 0 && utf8Idx < utf8s.length) return utf8s[utf8Idx];
            return "?";
        } catch (Throwable t) {
            return "?(" + t + ")";
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerSingletonIfNeeded(Loader loader, String full,
                                                  Class<? extends io.github.timer_err.qml4j.engine.QObject> rootQ,
                                                  List<String> prefixes) {
        try {
            if (prefixes == null || prefixes.isEmpty()) return;
            String typeName = full.substring(full.lastIndexOf('/') + 1);
            int dot = typeName.lastIndexOf('.');
            if (dot > 0) typeName = typeName.substring(0, dot);
            for (String p : prefixes) {
                if (p == null || p.isEmpty()) continue;
                Map<?, ?> qmldir = (Map<?, ?>) mLoadQmldir.invoke(loader, p);
                if (qmldir == null) continue;
                Object e = qmldir.get(typeName);
                if (e != null && Boolean.TRUE.equals(fQmldirSingleton.get(e))) {
                    Map<String, Map<String, Class<? extends io.github.timer_err.qml4j.engine.QObject>>> s =
                            (Map<String, Map<String, Class<? extends io.github.timer_err.qml4j.engine.QObject>>>) fSingletons.get(loader);
                    s.computeIfAbsent(p, k -> new java.util.HashMap<>()).put(typeName, rootQ);
                    break;
                }
            }
        } catch (Throwable t) {
            System.out.println("[RH] SINGLETON-FAIL " + full + ": " + t);
        }
    }

    private static void initReflection() throws Exception {
        if (fResources != null) return;
        fResources = Loader.class.getDeclaredField("resources");
        fResources.setAccessible(true);
        fEngine = Loader.class.getDeclaredField("engine");
        fEngine.setAccessible(true);
        fImported = Loader.class.getDeclaredField("importedTypes");
        fImported.setAccessible(true);
        fSingletons = Loader.class.getDeclaredField("singletonsByPrefix");
        fSingletons.setAccessible(true);
        mLoadQmldir = Loader.class.getDeclaredMethod("loadQmldir", String.class);
        mLoadQmldir.setAccessible(true);
        Class<?> entryClass = null;
        for (Class<?> c : Loader.class.getDeclaredClasses()) {
            if (c.getSimpleName().equals("QmldirEntry")) { entryClass = c; break; }
        }
        if (entryClass != null) {
            fQmldirFile = entryClass.getDeclaredField("file");
            fQmldirFile.setAccessible(true);
            fQmldirSingleton = entryClass.getDeclaredField("singleton");
            fQmldirSingleton.setAccessible(true);
        }
    }
}
