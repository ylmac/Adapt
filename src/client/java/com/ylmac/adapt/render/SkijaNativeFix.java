package com.ylmac.adapt.render;

import io.github.humbleui.skija.impl.Library;
import io.github.humbleui.types.IPoint;
import io.github.humbleui.types.IRange;
import io.github.humbleui.types.IRect;
import io.github.humbleui.types.Point;
import io.github.humbleui.types.Rect;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import com.ylmac.adapt.QplayerModClient;

public final class SkijaNativeFix {
    private static boolean loaded = false;

    private SkijaNativeFix() {}

    public static void preload() {
        if (loaded) return;
        loaded = true;
        try {
            ClassLoader modCl = SkijaNativeFix.class.getClassLoader();
            Thread.currentThread().setContextClassLoader(modCl);

            Class.forName("io.github.humbleui.types.IPoint", true, modCl);
            Class.forName("io.github.humbleui.types.IRange", true, modCl);
            Class.forName("io.github.humbleui.types.IRect", true, modCl);
            Class.forName("io.github.humbleui.types.Point", true, modCl);
            Class.forName("io.github.humbleui.types.Rect", true, modCl);

            Class.forName("io.github.humbleui.skija.impl.Library", true, modCl);
            Class.forName("io.github.humbleui.skija.impl.Native", true, modCl);
            Class.forName("io.github.humbleui.skija.impl.RefCnt", true, modCl);
            Class.forName("io.github.humbleui.skija.impl.Stats", true, modCl);
            Class.forName("io.github.humbleui.skija.Surface", true, modCl);
            Class.forName("io.github.humbleui.skija.Canvas", true, modCl);
            Class.forName("io.github.humbleui.skija.Paint", true, modCl);
            Class.forName("io.github.humbleui.skija.DirectContext", true, modCl);
            Class.forName("io.github.humbleui.skija.BackendRenderTarget", true, modCl);
            Class.forName("io.github.humbleui.skija.SurfaceOrigin", true, modCl);
            Class.forName("io.github.humbleui.skija.ColorType", true, modCl);
            Class.forName("io.github.humbleui.skija.ColorAlphaType", true, modCl);
            Class.forName("io.github.humbleui.skija.ImageInfo", true, modCl);
            Class.forName("io.github.humbleui.skija.Pixmap", true, modCl);

            File tempDir = new File(System.getProperty("java.io.tmpdir"), "skija-natives-" + System.nanoTime());
            tempDir.mkdirs();
            tempDir.deleteOnExit();

            String osName = System.getProperty("os.name").toLowerCase();
            String osArch = System.getProperty("os.arch").toLowerCase();
            String platDir;
            String libName;
            if (osName.contains("mac")) {
                platDir = osArch.contains("aarch64") ? "macos-arm64" : "macos-x64";
                libName = "libskija.dylib";
            } else if (osName.contains("win")) {
                platDir = "windows-x64";
                libName = "skija.dll";
            } else {
                platDir = osArch.contains("aarch64") ? "linux-arm64" : "linux-x64";
                libName = "libskija.so";
            }

            String resPath = "META-INF/jars/skija-" + platDir + "-0.143.16.jar";
            Path libPath = tempDir.toPath().resolve(libName);
            try (InputStream is = SkijaNativeFix.class.getClassLoader().getResourceAsStream(resPath)) {
                if (is != null) {
                    try (java.util.jar.JarInputStream jis = new java.util.jar.JarInputStream(is)) {
                        java.util.jar.JarEntry entry;
                        while ((entry = jis.getNextJarEntry()) != null) {
                            if (entry.getName().contains(libName)) {
                                Files.copy(jis, libPath, StandardCopyOption.REPLACE_EXISTING);
                                break;
                            }
                        }
                    }
                }
            }

            if (!libPath.toFile().exists()) {
                Path altPath = tempDir.toPath().resolve("skija-" + platDir).resolve(libName);
                altPath.toFile().getParentFile().mkdirs();
                try (InputStream is = SkijaNativeFix.class.getClassLoader().getResourceAsStream(
                        "META-INF/jars/skija-" + platDir + "-0.143.16.jar")) {
                    if (is != null) {
                        Path tmpJar = tempDir.toPath().resolve("tmp.jar");
                        Files.copy(is, tmpJar, StandardCopyOption.REPLACE_EXISTING);
                        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(tmpJar.toFile())) {
                            java.util.jar.JarEntry je = jf.stream()
                                    .filter(e -> e.getName().endsWith(libName))
                                    .findFirst().orElse(null);
                            if (je != null) {
                                try (InputStream jin = jf.getInputStream(je)) {
                                    Files.copy(jin, altPath, StandardCopyOption.REPLACE_EXISTING);
                                }
                            }
                        }
                        tmpJar.toFile().delete();
                        if (altPath.toFile().exists()) {
                            libPath = altPath;
                        }
                    }
                }
            }

            if (libPath.toFile().exists()) {
                libPath.toFile().deleteOnExit();
                System.setProperty("skija.library.path", tempDir.getAbsolutePath());
            }

            Method staticLoad = Library.class.getDeclaredMethod("staticLoad");
            staticLoad.setAccessible(true);
            staticLoad.invoke(null);

            QplayerModClient.LOGGER.info("[QPlayer] Skija native preload OK");
        } catch (Exception e) {
            QplayerModClient.LOGGER.error("[QPlayer] Skija native preload FAILED: {}", e.toString());
        }
    }
}