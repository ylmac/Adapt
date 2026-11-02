package com.ylmac.adapt.module.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.ylmac.adapt.module.render.CosmeticsModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 不连接 playerassets。路径可用 {@code -Dadapt.cosmetics=} 覆盖。
 * <p>
 * UV 固定除以 16（跟原版一样，不看 texture_size），再由渲染器套上
 * {@code renderOnHead} 的头部变换。
 */
public final class CosmeticLibrary {

    private static final Logger LOGGER = LoggerFactory.getLogger("adapt-cosmetics");
    private static final String ROOT = "assets/minecraft/client/cosmetics/";
    private static final String[] FACE_NAMES = {"down", "up", "north", "south", "west", "east"};

    private static JarFile jar;
    private static boolean opened;
    private static boolean missingLogged;

    private static final Map<String, Identifier> textures = new HashMap<>();
    private static final Map<String, HatModel> hats = new HashMap<>();
    private static final Map<String, List<String>> catalog = new HashMap<>();
    /** 只在客户端 tick、世界渲染开始前为真。实体提交里不许上传贴图。 */
    private static boolean preparing;

    private CosmeticLibrary() {}

    /**
 * 在客户端 tick 里把当前披风、翅膀、帽子的贴图传到 GPU。
 * 实体提交发生在一帧的命令缓冲还开着的时候，这时再 upload 会跟 MoltenVK 的同一条队列互相等。
 */
    public static void prepareFrame() {
        if (!RenderSystem.isOnRenderThread()) return;
        CosmeticsModule module = CosmeticsModule.INSTANCE;
        if (module == null || !module.isEnabled()) return;
        preparing = true;
        try {
            cape(module.cape());
            wings(module.wings());
            hat(module.hat());
        } finally {
            preparing = false;
        }
    }

    public static Identifier cape(String name) {
        String key = key(name);
        if (key.isEmpty()) return null;
        return texture("cape/" + key, ROOT + "capes/" + key + ".png");
    }

    public static Identifier wings(String name) {
        String key = key(name);
        if (key.isEmpty()) return null;
        return texture("wings/" + key, ROOT + "wings/" + key + ".png");
    }

    public static HatModel hat(String name) {
        String key = key(name);
        if (key.isEmpty()) return null;
        synchronized (CosmeticLibrary.class) {
            if (hats.containsKey(key)) return hats.get(key);
            if (!preparing) return null;
        }
        HatModel model = loadHat(key);
        synchronized (CosmeticLibrary.class) {
            hats.put(key, model);
        }
        return model;
    }

    /** 预览页用。kind 是 cape、wings、hat。结果按名字排序，并缓存。 */
    public static synchronized List<String> names(String kind) {
        if (!open()) return List.of();
        List<String> cached = catalog.get(kind);
        if (cached != null) return cached;
        String prefix;
        boolean hatsKind = "hat".equals(kind);
        if ("cape".equals(kind)) prefix = ROOT + "capes/";
        else if ("wings".equals(kind)) prefix = ROOT + "wings/";
        else prefix = ROOT + "hats/";
        TreeSet<String> found = new TreeSet<>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.startsWith(prefix)) continue;
            String rest = name.substring(prefix.length());
            if (hatsKind) {
                int slash = rest.indexOf('/');
                if (slash <= 0) continue;
                String folder = rest.substring(0, slash);
                if (rest.equals(folder + "/" + folder + ".json")) found.add(folder);
            } else if (rest.endsWith(".png") && rest.indexOf('/') < 0 && !rest.contains("thumbnail")) {
                found.add(rest.substring(0, rest.length() - 4));
            }
        }
        List<String> list = List.copyOf(found);
        catalog.put(kind, list);
        return list;
    }

    /** 预览页大图。帽子优先用缩略图。 */
    public static byte[] preview(String kind, String name) {
        String key = key(name);
        if (key.isEmpty()) return null;
        if ("cape".equals(kind)) return readEntry(ROOT + "capes/" + key + ".png");
        if ("wings".equals(kind)) return readEntry(ROOT + "wings/" + key + ".png");
        return readEntry(hatPreviewEntry(key));
    }

    /** 空、none、off、无 表示这个槽位不画。 */
    public static String key(String raw) {
        if (raw == null) return "";
        String name = raw.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.equals("none") || name.equals("off") || name.equals("无") || name.equals("-")) {
            return "";
        }
        StringBuilder safe = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            safe.append(ok ? c : '_');
        }
        return safe.toString();
    }

    private static Identifier texture(String idPath, String entry) {
        synchronized (CosmeticLibrary.class) {
            if (textures.containsKey(idPath)) return textures.get(idPath);
            if (!preparing) return null;
        }
        Identifier id = loadTexture(idPath, entry);
        synchronized (CosmeticLibrary.class) {
            textures.put(idPath, id);
        }
        return id;
    }

    private static Identifier loadTexture(String idPath, String entry) {
        byte[] bytes = readEntry(entry);
        if (bytes == null) {
            if (opened && jar != null) LOGGER.warn("饰品贴图不在包里: {}", entry);
            return null;
        }
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
            DynamicTexture texture = new ClampedTexture(() -> "adapt " + idPath, image);
            texture.upload();
            Identifier id = Identifier.fromNamespaceAndPath("adaptclient", "cosmetics/" + idPath);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            return id;
        } catch (Exception e) {
            LOGGER.warn("饰品贴图读取失败: {}", entry, e);
            return null;
        }
    }

    private static HatModel loadHat(String name) {
        String folder = ROOT + "hats/" + name + "/";
        byte[] bytes = readEntry(folder + name + ".json");
        if (bytes == null) {
            if (opened && jar != null) LOGGER.warn("帽子模型不在包里: {}", folder + name + ".json");
            return null;
        }
        try {
            return parseHat(folder, new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.warn("帽子模型读取失败: {}", name, e);
            return null;
        }
    }

    private static synchronized String hatPreviewEntry(String name) {
        if (!open()) return null;
        String folder = ROOT + "hats/" + name + "/";
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String entry = entries.nextElement().getName();
            if (!entry.startsWith(folder) || !entry.endsWith(".png") || !entry.contains("thumbnail")) continue;
            int rank = entry.contains("_red") ? 0 : entry.contains("_pink") ? 1 : 10 + entry.length();
            if (rank < bestRank) {
                best = entry;
                bestRank = rank;
            }
        }
        if (best != null) return best;
        entries = jar.entries();
        while (entries.hasMoreElements()) {
            String entry = entries.nextElement().getName();
            if (entry.startsWith(folder) && entry.endsWith(".png")) return entry;
        }
        return null;
    }

    private static HatModel parseHat(String folder, String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        float[] translation = new float[]{0f, 0f, 0f};
        float[] rotation = new float[]{0f, 0f, 0f};
        float[] scale = new float[]{1f, 1f, 1f};
        if (root.has("display") && root.getAsJsonObject("display").has("head")) {
            JsonObject head = root.getAsJsonObject("display").getAsJsonObject("head");
            translation = readVec(head, "translation", translation);
            rotation = readVec(head, "rotation", rotation);
            scale = readVec(head, "scale", scale);
            for (int i = 0; i < 3; i++) {
                translation[i] = Math.max(-80f, Math.min(80f, translation[i]));
                scale[i] = Math.max(-4f, Math.min(4f, scale[i]));
            }
        }
        Map<String, String> textureNames = new HashMap<>();
        if (root.has("textures")) {
            JsonObject texturesJson = root.getAsJsonObject("textures");
            for (Map.Entry<String, JsonElement> entry : texturesJson.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    textureNames.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        Map<String, Identifier> loaded = new HashMap<>();
        Map<Identifier, List<float[]>> grouped = new HashMap<>();
        JsonArray elements = root.getAsJsonArray("elements");
        if (elements != null) {
            for (JsonElement elementValue : elements) {
                parseElement(elementValue.getAsJsonObject(), folder, textureNames, loaded, grouped);
            }
        }
        if (grouped.isEmpty()) return null;
        List<HatModel.Mesh> meshes = new ArrayList<>();
        for (Map.Entry<Identifier, List<float[]>> entry : grouped.entrySet()) {
            meshes.add(new HatModel.Mesh(entry.getKey(), List.copyOf(entry.getValue())));
        }
        return new HatModel(translation, rotation, scale, List.copyOf(meshes));
    }

    private static void parseElement(JsonObject element, String folder, Map<String, String> textureNames,
                                     Map<String, Identifier> loaded, Map<Identifier, List<float[]>> grouped) {
        if (!element.has("from") || !element.has("to") || !element.has("faces")) return;
        float[] rawFrom = vec3(element.getAsJsonArray("from"));
        float[] rawTo = vec3(element.getAsJsonArray("to"));
        float[] from = new float[]{
                Math.min(rawFrom[0], rawTo[0]), Math.min(rawFrom[1], rawTo[1]), Math.min(rawFrom[2], rawTo[2])};
        float[] to = new float[]{
                Math.max(rawFrom[0], rawTo[0]), Math.max(rawFrom[1], rawTo[1]), Math.max(rawFrom[2], rawTo[2])};
        String axis = null;
        float angle = 0f;
        float[] origin = new float[]{8f, 8f, 8f};
        boolean rescale = false;
        if (element.has("rotation")) {
            JsonObject rotation = element.getAsJsonObject("rotation");
            axis = rotation.has("axis") ? rotation.get("axis").getAsString() : null;
            angle = rotation.has("angle") ? rotation.get("angle").getAsFloat() : 0f;
            origin = readVec(rotation, "origin", origin);
            rescale = rotation.has("rescale") && rotation.get("rescale").getAsBoolean();
        }
        JsonObject faces = element.getAsJsonObject("faces");
        for (String faceName : FACE_NAMES) {
            if (!faces.has(faceName)) continue;
            JsonObject face = faces.getAsJsonObject(faceName);
            if (!face.has("texture")) continue;
            String textureName = resolveTextureName(face.get("texture").getAsString(), textureNames);
            if (textureName == null) continue;
            Identifier texture = loaded.get(textureName);
            if (!loaded.containsKey(textureName)) {
                String png = resolvePng(folder, textureName);
                texture = png == null ? null : texture("hat/" + folderHash(folder) + "/" + key(textureName), png);
                loaded.put(textureName, texture);
            }
            if (texture == null) continue;
            // 这些模型的 UV 就写在 0–16 里，高清贴图只是同一张图放大。
            float[] uv = face.has("uv") ? vec4(face.getAsJsonArray("uv")) : defaultUv(faceName, from, to);
            float divU = 16f;
            float divV = 16f;
            int turn = face.has("rotation") ? Math.floorMod(face.get("rotation").getAsInt() / 90, 4) : 0;
            float[][] corners = faceVertices(faceName, from, to);
            float[][] uvs = new float[][]{
                    {uv[0], uv[1]}, {uv[0], uv[3]}, {uv[2], uv[3]}, {uv[2], uv[1]}};
            float[] packed = new float[23];
            for (int i = 0; i < 4; i++) {
                float[] rotated = rotateVertex(corners[i], axis, angle, origin, rescale);
                float[] cornerUv = uvs[(i + turn) % 4];
                packed[i * 5] = rotated[0] / 16f;
                packed[i * 5 + 1] = rotated[1] / 16f;
                packed[i * 5 + 2] = rotated[2] / 16f;
                packed[i * 5 + 3] = cornerUv[0] / divU;
                packed[i * 5 + 4] = cornerUv[1] / divV;
            }
            float[] normal = rotateVertex(faceNormal(faceName), axis, angle, null, false);
            packed[20] = normal[0];
            packed[21] = normal[1];
            packed[22] = normal[2];
            grouped.computeIfAbsent(texture, ignored -> new ArrayList<>()).add(packed);
        }
    }

    /** Blockbench 没写 uv 时，按方块自己的面在 16 格里的位置补。 */
    private static float[] defaultUv(String face, float[] from, float[] to) {
        return switch (face) {
            case "down" -> new float[]{from[0], 16f - to[2], to[0], 16f - from[2]};
            case "up" -> new float[]{from[0], from[2], to[0], to[2]};
            case "north" -> new float[]{16f - to[0], 16f - to[1], 16f - from[0], 16f - from[1]};
            case "south" -> new float[]{from[0], 16f - to[1], to[0], 16f - from[1]};
            case "west" -> new float[]{from[2], 16f - to[1], to[2], 16f - from[1]};
            default -> new float[]{16f - to[2], 16f - to[1], 16f - from[2], 16f - from[1]};
        };
    }

    private static float[][] faceVertices(String face, float[] from, float[] to) {
        float x0 = from[0], y0 = from[1], z0 = from[2];
        float x1 = to[0], y1 = to[1], z1 = to[2];
        return switch (face) {
            case "down" -> new float[][]{{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}};
            case "up" -> new float[][]{{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
            case "north" -> new float[][]{{x1, y1, z0}, {x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}};
            case "south" -> new float[][]{{x0, y1, z1}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}};
            case "west" -> new float[][]{{x0, y1, z0}, {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}};
            default -> new float[][]{{x1, y1, z1}, {x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}};
        };
    }

    private static float[] faceNormal(String face) {
        return switch (face) {
            case "down" -> new float[]{0f, -1f, 0f};
            case "up" -> new float[]{0f, 1f, 0f};
            case "north" -> new float[]{0f, 0f, -1f};
            case "south" -> new float[]{0f, 0f, 1f};
            case "west" -> new float[]{-1f, 0f, 0f};
            default -> new float[]{1f, 0f, 0f};
        };
    }

    private static float[] rotateVertex(float[] point, String axis, float degrees, float[] origin, boolean rescale) {
        if (axis == null || degrees == 0f) return point.clone();
        float ox = origin == null ? 0f : origin[0];
        float oy = origin == null ? 0f : origin[1];
        float oz = origin == null ? 0f : origin[2];
        double radians = Math.toRadians(degrees);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float stretch = rescale && origin != null ? 1f / Math.abs(cos) : 1f;
        float x = point[0] - ox;
        float y = point[1] - oy;
        float z = point[2] - oz;
        float nx = x;
        float ny = y;
        float nz = z;
        switch (axis) {
            case "x" -> {
                ny = (y * cos - z * sin) * stretch;
                nz = (y * sin + z * cos) * stretch;
            }
            case "y" -> {
                nx = (x * cos + z * sin) * stretch;
                nz = (-x * sin + z * cos) * stretch;
            }
            case "z" -> {
                nx = (x * cos - y * sin) * stretch;
                ny = (x * sin + y * cos) * stretch;
            }
            default -> {
                return point.clone();
            }
        }
        return new float[]{nx + ox, ny + oy, nz + oz};
    }

    private static String resolveTextureName(String ref, Map<String, String> names) {
        String current = ref;
        for (int i = 0; current != null && current.startsWith("#") && i < 8; i++) {
            current = names.get(current.substring(1));
        }
        if (current == null || current.isEmpty() || current.startsWith("#")) return null;
        int cut = Math.max(current.lastIndexOf('/'), current.lastIndexOf(':'));
        String file = current.substring(cut + 1);
        if (file.endsWith(".png")) file = file.substring(0, file.length() - 4);
        return file.isEmpty() ? null : file;
    }

    private static String resolvePng(String folder, String textureName) {
        String exact = folder + textureName + ".png";
        if (jar.getJarEntry(exact) != null) return exact;
        String needle = textureName.toLowerCase(Locale.ROOT);
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.startsWith(folder) || !name.endsWith(".png") || name.contains("thumbnail")) continue;
            String file = name.substring(name.lastIndexOf('/') + 1);
            String stem = file.substring(0, file.length() - 4).toLowerCase(Locale.ROOT);
            if (!stem.equals(needle) && !stem.contains(needle) && !needle.contains(stem)) continue;
            int rank = name.contains("_red") ? 0 : name.contains("_pink") ? 1 : 10 + name.length();
            if (rank < bestRank) {
                best = name;
                bestRank = rank;
            }
        }
        if (best != null) return best;
        entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(folder) && name.endsWith(".png") && !name.contains("thumbnail")) return name;
        }
        LOGGER.warn("帽子贴图对不上: {} {}", folder, textureName);
        return null;
    }

    private static String folderHash(String folder) {
        int slash = folder.lastIndexOf('/', folder.length() - 2);
        String name = slash >= 0 ? folder.substring(slash + 1, folder.length() - 1) : folder;
        return key(name);
    }

    private static float[] readVec(JsonObject object, String name, float[] fallback) {
        if (!object.has(name)) return fallback.clone();
        return vec3(object.getAsJsonArray(name));
    }

    private static float[] vec3(JsonArray array) {
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }

    private static float[] vec4(JsonArray array) {
        return new float[]{
                array.get(0).getAsFloat(), array.get(1).getAsFloat(),
                array.get(2).getAsFloat(), array.get(3).getAsFloat()
        };
    }

    private static synchronized byte[] readEntry(String entry) {
        if (entry == null || !open()) return null;
        JarEntry jarEntry = jar.getJarEntry(entry);
        if (jarEntry == null) return null;
        try (InputStream in = jar.getInputStream(jarEntry)) {
            return in.readAllBytes();
        } catch (Exception e) {
            LOGGER.warn("饰品读取失败: {}", entry, e);
            return null;
        }
    }

    private static synchronized boolean open() {
        if (opened) return jar != null;
        opened = true;
        Path path = jarPath();
        if (!Files.isRegularFile(path)) {
            if (!missingLogged) {
                missingLogged = true;
                LOGGER.warn("找不到饰品包 {}。披风、翅膀和帽子不会显示。", path);
            }
            return false;
        }
        try {
            jar = new JarFile(path.toFile());
            LOGGER.info("饰品包 {}", path);
            return true;
        } catch (Exception e) {
            LOGGER.warn("打不开饰品包 {}", path, e);
            return false;
        }
    }

    private static Path jarPath() {
        String override = System.getProperty("adapt.cosmetics");
        if (override != null && !override.isBlank()) return Path.of(override);
        return Path.of(System.getProperty("user.home"), "OpenCheatBreaker-main", "libs", "cosmetics-master.jar");
    }

    /**
 * 顶点已经是方块单位（除过 16）。每个面 23 个数：4 个角的 xyzuv，再加法线。
 * translation / rotation / scale 是 Blockbench display.head，渲染时再套。
 */
    public static final class HatModel {
        public final float[] translation;
        public final float[] rotation;
        public final float[] scale;
        public final List<Mesh> meshes;

        public HatModel(float[] translation, float[] rotation, float[] scale, List<Mesh> meshes) {
            this.translation = translation;
            this.rotation = rotation;
            this.scale = scale;
            this.meshes = meshes;
        }

        public static final class Mesh {
            public final Identifier texture;
            public final List<float[]> quads;

            public Mesh(Identifier texture, List<float[]> quads) {
                this.texture = texture;
                this.quads = quads;
            }
        }
    }

    /** 实体贴图用边缘钳制。动态贴图默认是重复寻址，帽子和披风的 UV 一越界就会卷到另一头。 */
    private static final class ClampedTexture extends DynamicTexture {
        ClampedTexture(java.util.function.Supplier<String> name, NativeImage image) {
            super(name, image);
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        }
    }
}
