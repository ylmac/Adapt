package com.ylmac.adapt.config;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ylmac.adapt.features.music.cache.DiskCache;
import com.ylmac.adapt.features.music.customapi.CustomApiConfig;
import com.ylmac.adapt.features.music.lyric.skia.Fonts;
import com.ylmac.adapt.features.music.lyric.skia.LyricCompositor;
import com.ylmac.adapt.features.music.lyric.skia.LyricConfig;
import com.ylmac.adapt.config.store.AppDirs;
import io.github.timer_err.qml4j.engine.binding.Property;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.ylmac.adapt.features.music.PlayerController;
import com.ylmac.adapt.QplayerModClient;
import io.github.timer_err.qml4j.runtime.color.StyleManager;
import com.ylmac.adapt.render.WorldColorSampler;

public final class DesktopSettings implements LyricCompositor.SettingsBridge {

    // Appearance
    // darkMode: 0=跟随系统 1=浅色 2=深色 3=主题色(surface+primary混合, PixelPlayer风格)
    public final Property<Number> darkMode = new Property<>(0);
    public final Property<Boolean> monetEnabled = new Property<>(true);
    /** 莫奈取色来源：0=歌曲封面 1=世界背景（默认世界） */
    /**
     * 主题取色来源：0 = 音乐封面（**默认**）、1 = 世界采样。
     * 世界取色只在 Interface 模块的 Debug 打开时才生效（见 WorldColorSampler/CoverSeedSampler）。
     */
    public final Property<Number> monetSource = new Property<>(0);

    /**
     * Debug 模式：**详细/危险选项的总开关**（持久化）。
     * 目前包含：取色来源（世界取色，已废弃，仅调试可用）。
     * 真源在这里，InterfaceModule.isDebug() 读它。
     */
    public final Property<Boolean> debugMode = new Property<>(false);
    // 已移除 monetTransitionMs（颜色过渡时长）：过渡功能按用户要求关闭，
    // 新种子直接落地，见 WorldColorSampler.consumePendingSeed。旧配置文件里的
    // 这个键会被忽略（applyInt 找不到对应 Property 时不报错）。
    public final Property<Boolean> resolvedDark = new Property<>(true);

    // Audio
    public final Property<Number> musicVolume = new Property<>(0.8f);
    public final Property<Boolean> unblockEnabled = new Property<>(true);
    public final Property<Boolean> mirrorEnabled = new Property<>(true);
    /** 高音质播放：开启后请求网易云歌曲链接使用 exhigh（高音质）音源 */
    public final Property<Boolean> highQuality = new Property<>(false);

    // Lyrics
    public final Property<Number> lyricFontSize = new Property<>(28);
    public final Property<Number> lyricFontWeight = new Property<>(2);
    /** 歌词字体：""=内置 PingFang SC，"system"=系统默认字体，否则为已安装字体族名（FontPickerDialog 设置） */
    public final Property<String> fontSelection = new Property<>("");
    /** 已安装字体族列表（FontPickerDialog 显示用，启动时枚举一次，不随选择变化） */
    public final Property<List<String>> availableFontFamilies = new Property<>(Collections.emptyList());
    public final Property<Number> lyricLineSpacing = new Property<>(200);
    public final Property<Boolean> lyricSpring = new Property<>(true);
    public final Property<Boolean> lyricScale = new Property<>(true);
    public final Property<Boolean> lyricGlow = new Property<>(true);
    public final Property<Boolean> lyricEdgeBlur = new Property<>(false);
    public final Property<Boolean> lyricShadow = new Property<>(true);
    /** 非逐字歌词线性动画：关闭后无逐字时间的行整行一次性点亮 */
    public final Property<Boolean> lyricLinearAnim = new Property<>(true);
    /** 歌词页进度条样式：wavy=波浪 straight=直线 */
    public final Property<String> lyricProgressStyle = new Property<>("wavy");
    public final Property<Boolean> lyricBgStatic = new Property<>(false);

    // ── ClickGUI 毛玻璃外观（积分榜式玻璃的三个可调项）──
    /** 毛玻璃面板投影 */
    public final Property<Boolean> glassShadow = new Property<>(true);
    /** 毛玻璃面板发光（bloom，主题色） */
    public final Property<Boolean> glassBloom = new Property<>(false);
    /** ClickGUI 动态背景：模仿音乐小组件，在玻璃面板底下画封面流体背景 */
    public final Property<Boolean> guiFluidBg = new Property<>(true);
    public final Property<Boolean> showRomaji = new Property<>(true);
    public final Property<Boolean> showTranslation = new Property<>(true);

    // Cache
    public final Property<Number> maxCacheSizeMB = new Property<>(500);
    /** 构建期预置 QML 缓存预加载到内存（打开后 QML 加载零磁盘 I/O） */
    public final Property<Boolean> qmlCachePreload = new Property<>(true);

    // Storage
    public final Property<String> musicFolder = new Property<>("");
    public final Property<String> cacheFolder = new Property<>("");

    // 自定义 API 音源（第三方音乐 API 适配）：QML 直接绑定到
    // settings.customApiConfig.<字段> 的嵌套字段；字段级编辑不触发 Property
    // 通知（qml4j 对普通字段走反射直写），由 QML 侧在编辑后调用
    // saveCustomApiConfig() 持久化；PlayerController 构造时持有同一实例，
    // 字段级编辑实时生效。
    public final Property<CustomApiConfig> customApiConfig = new Property<>(new CustomApiConfig());

    // Layout
    public final Property<Number> topInset = new Property<>(0f);
    public final Property<Number> bottomInset = new Property<>(0f);
    /** 显示导航栏「本地」标签（关闭后隐藏底部/侧栏本地入口） */
    public final Property<Boolean> showLocalTab = new Property<>(true);
    /**
     * HUD 位置编辑器的布局（JSON：元素 id → [x, y]，GUI 坐标）。
     * 由 {@code HudEditor} 在按 ESC 退出编辑态时写入；模块设置本身不持久化，
     * 所以位置只能存这里。
     */
    public final Property<String> hudLayout = new Property<>("");

    // Runtime
    public final Property<Boolean> showLyrics = new Property<>(true);
    /** 世界内歌词对齐：0=左 1=中 2=右（默认右对齐，右下角面板观感） */
    public final Property<Number> worldLyricAlign = new Property<>(2);
    /** 世界内歌词字号（px，独立于桌面歌词，默认 28） */
    public final Property<Number> worldLyricFontSize = new Property<>(28);
    /** 世界内歌词字重：0=极细 1=细 2=常规 3=中等（默认 2） */
    public final Property<Number> worldLyricFontWeight = new Property<>(2);
    /** 世界内歌词描边颜色：0=无 1=黑 2=白 3=橙 4=红 5=金渐变（默认无，原版无描边） */
    public final Property<Number> worldLyricBorderColor = new Property<>(0);

    private final Path settingsFile;
    private final Gson gson = new Gson();
    private volatile boolean loading;

    public DesktopSettings() {
        settingsFile = Paths.get(AppDirs.base(), "settings.json");
        String home = System.getProperty("user.home", ".");
        musicFolder.set(home + "/Music");
        cacheFolder.set(home + "/.qplayer/cache");

        // darkMode 监听器必须在 load() 之前注册——load() 会调 applyInt → darkMode.set(savedValue)，
        // 如果监听器在 load 之后才注册，初始加载的值不会触发监听器 → isDarkTheme 永远是默认值 FALSE
        darkMode.addListener(mode -> {
            int m = mode == null ? 0 : mode.intValue();
            resolvedDark.set(m != 1);
            // darkMode=3 (主题色) → StyleManager 启用 themedSurface
            // → scheme 的 surface/background 全部替换为 primary 混合 → 所有 QML 自动跟随
            try {
                Object sm = io.github.timer_err.qml4j.runtime.color.StyleManager.__instance();
                // 必须在 Java 层设置 isDarkTheme——QML Binding 太晚（QML 加载后才触发）
                // 初始进游戏时 isDarkTheme=FALSE（默认），导致无论选什么都是浅色/黑色
                ((io.github.timer_err.qml4j.runtime.color.StyleManager) sm)
                    .isDarkTheme.set(resolvedDark.peek());
                ((io.github.timer_err.qml4j.runtime.color.StyleManager) sm)
                    .setThemedSurface(m == 3);
            } catch (Throwable ignored) {}
            autoSave();
        });

        loading = true;
        load();
        loading = false;

        addAutoSave(monetEnabled);
        addAutoSave(debugMode);
        addAutoSave(monetSource);
        // 切换颜色模式时立即采样/重分析，不等 1 秒周期
        monetEnabled.addListener(v -> {
            if (!loading && Boolean.TRUE.equals(v)) WorldColorSampler.get().forceRefresh();
        });
        monetSource.addListener(v -> {
            if (!loading) WorldColorSampler.get().forceRefresh();
        });
        addAutoSave(musicVolume);
        addAutoSave(unblockEnabled);
        addAutoSave(mirrorEnabled);
        addAutoSave(highQuality);
        addAutoSave(lyricFontSize);
        addAutoSave(lyricFontWeight);
        addAutoSave(lyricLineSpacing);
        addAutoSave(lyricSpring);
        addAutoSave(lyricScale);
        addAutoSave(lyricGlow);
        addAutoSave(lyricEdgeBlur);
        addAutoSave(lyricShadow);
        addAutoSave(lyricLinearAnim);
        addAutoSave(lyricProgressStyle);
        addAutoSave(lyricBgStatic);
        addAutoSave(glassShadow);
        addAutoSave(glassBloom);
        addAutoSave(guiFluidBg);
        addAutoSave(showRomaji);
        addAutoSave(showTranslation);
        addAutoSave(maxCacheSizeMB);
        addAutoSave(qmlCachePreload);
        addAutoSave(musicFolder);
        addAutoSave(cacheFolder);
        addAutoSave(worldLyricAlign);
        addAutoSave(worldLyricFontSize);
        addAutoSave(worldLyricFontWeight);
        addAutoSave(worldLyricBorderColor);
        addAutoSave(fontSelection);
        addAutoSave(showLocalTab);
        addAutoSave(hudLayout);
        addAutoSave(customApiConfig);

        // 已安装字体族列表（字体选择对话框用）：FontMgr 未就绪（QML 构建期）时静默为空
        try {
            availableFontFamilies.set(Collections.unmodifiableList(Arrays.asList(Fonts.listFamilies())));
        } catch (Throwable ignored) {
            availableFontFamilies.set(Collections.emptyList());
        }
        // 初始应用持久化的字体选择（load() 已在上面完成，listener 在 load 后注册，
        // 所以这里手动同步一次；Fonts.setSelection 内部对失败选择回退内置字体）
        applyFontSelection();
        fontSelection.addListener(v -> { if (!loading) applyFontSelection(); });

        syncLyricConfig();
        lyricFontSize.addListener(v -> { LyricConfig.instance.lyricFontSize.setValue(v == null ? 28 : v.intValue()); });
        lyricFontWeight.addListener(v -> { LyricConfig.instance.fontWeight.setValue(toFontWeight(v)); });
        lyricLineSpacing.addListener(v -> { LyricConfig.instance.lineSpacing.setValue(v == null ? 2.0f : v.floatValue() / 100f); });
        lyricSpring.addListener(v -> { LyricConfig.instance.springPhysics.setValue(v); });
        lyricScale.addListener(v -> { LyricConfig.instance.scaleEmphasis.setValue(v); });
        lyricGlow.addListener(v -> { LyricConfig.instance.glow.setValue(v); });
        lyricEdgeBlur.addListener(v -> { LyricConfig.instance.edgeBlur.setValue(v); });
        lyricShadow.addListener(v -> { LyricConfig.instance.lyricShadow.setValue(v); });
        lyricLinearAnim.addListener(v -> { LyricConfig.instance.lyricLinearAnim.setValue(v); });
        lyricProgressStyle.addListener(v -> { LyricConfig.instance.lyricProgressStyle.setValue(v); });
        showRomaji.addListener(v -> { LyricConfig.instance.showRomaji.setValue(v); });
        showTranslation.addListener(v -> { LyricConfig.instance.showTranslation.setValue(v); });
    }

    private void addAutoSave(Property<?> prop) {
        prop.addListener(v -> autoSave());
    }

    /** 把当前字体选择应用到 Skia 渲染器（Fonts.setSelection，实时生效）；
     *  构建期/无 Skia 原生库时静默忽略。 */
    private void applyFontSelection() {
        try {
            String sel = fontSelection.get();
            Fonts.setSelection(sel == null ? "" : sel);
        } catch (Throwable ignored) {}
    }

    private void autoSave() {
        if (loading) return;
        save();
    }

    private void save() {
        try {
            Map<String, Object> map = new HashMap<>();
            put(map, "darkMode", darkMode.get());
            put(map, "monetEnabled", monetEnabled.get());
            put(map, "debugMode", debugMode.get());
            put(map, "monetSource", monetSource.get());
            put(map, "musicVolume", musicVolume.get());
            put(map, "unblockEnabled", unblockEnabled.get());
            put(map, "mirrorEnabled", mirrorEnabled.get());
            put(map, "highQuality", highQuality.get());
            put(map, "lyricFontSize", lyricFontSize.get());
            put(map, "lyricFontWeight", lyricFontWeight.get());
            put(map, "lyricLineSpacing", lyricLineSpacing.get());
            put(map, "lyricSpring", lyricSpring.get());
            put(map, "glassShadow", glassShadow.get());
            put(map, "glassBloom", glassBloom.get());
            put(map, "guiFluidBg", guiFluidBg.get());
            put(map, "lyricScale", lyricScale.get());
            put(map, "lyricGlow", lyricGlow.get());
            put(map, "lyricEdgeBlur", lyricEdgeBlur.get());
            put(map, "lyricShadow", lyricShadow.get());
            put(map, "lyricLinearAnim", lyricLinearAnim.get());
            put(map, "lyricProgressStyle", lyricProgressStyle.get());
            put(map, "lyricBgStatic", lyricBgStatic.get());
            put(map, "showRomaji", showRomaji.get());
            put(map, "showTranslation", showTranslation.get());
            put(map, "maxCacheSizeMB", maxCacheSizeMB.get());
            put(map, "musicFolder", musicFolder.get());
            put(map, "cacheFolder", cacheFolder.get());
            put(map, "worldLyricAlign", worldLyricAlign.get());
            put(map, "worldLyricFontSize", worldLyricFontSize.get());
            put(map, "worldLyricFontWeight", worldLyricFontWeight.get());
            put(map, "worldLyricBorderColor", worldLyricBorderColor.get());
            put(map, "fontSelection", fontSelection.get());
            put(map, "showLocalTab", showLocalTab.get());
            put(map, "hudLayout", hudLayout.get());
            put(map, "customApiConfig", customApiConfig.get());
            Files.createDirectories(settingsFile.getParent());
            Files.write(settingsFile, gson.toJson(map).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            QplayerModClient.LOGGER.warn("[DesktopSettings] save failed: {}", e.getMessage());
        }
    }

    private static void put(Map<String, Object> map, String key, Object val) {
        if (val != null) map.put(key, val);
    }

    private void load() {
        if (!Files.exists(settingsFile)) return;
        try {
            byte[] data = Files.readAllBytes(settingsFile);
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> map = gson.fromJson(new String(data, StandardCharsets.UTF_8), type);
            if (map == null) return;
            applyInt(map, "darkMode", darkMode);
            applyBool(map, "monetEnabled", monetEnabled);
            applyBool(map, "debugMode", debugMode);
            applyInt(map, "monetSource", monetSource);
            applyFloat(map, "musicVolume", musicVolume);
            applyBool(map, "unblockEnabled", unblockEnabled);
            applyBool(map, "mirrorEnabled", mirrorEnabled);
            applyBool(map, "highQuality", highQuality);
            applyInt(map, "lyricFontSize", lyricFontSize);
            applyInt(map, "lyricFontWeight", lyricFontWeight);
            applyInt(map, "lyricLineSpacing", lyricLineSpacing);
            applyBool(map, "lyricSpring", lyricSpring);
            applyBool(map, "lyricScale", lyricScale);
            applyBool(map, "lyricGlow", lyricGlow);
            applyBool(map, "lyricEdgeBlur", lyricEdgeBlur);
            applyBool(map, "lyricShadow", lyricShadow);
            applyBool(map, "lyricLinearAnim", lyricLinearAnim);
            applyString(map, "lyricProgressStyle", lyricProgressStyle);
            applyBool(map, "lyricBgStatic", lyricBgStatic);
            applyBool(map, "glassShadow", glassShadow);
            applyBool(map, "glassBloom", glassBloom);
            applyBool(map, "guiFluidBg", guiFluidBg);
            applyBool(map, "showRomaji", showRomaji);
            applyBool(map, "showTranslation", showTranslation);
            applyInt(map, "maxCacheSizeMB", maxCacheSizeMB);
            applyString(map, "musicFolder", musicFolder);
            applyString(map, "cacheFolder", cacheFolder);
            applyInt(map, "worldLyricAlign", worldLyricAlign);
            applyInt(map, "worldLyricFontSize", worldLyricFontSize);
            applyInt(map, "worldLyricFontWeight", worldLyricFontWeight);
            applyInt(map, "worldLyricBorderColor", worldLyricBorderColor);
            applyString(map, "fontSelection", fontSelection);
            applyBool(map, "showLocalTab", showLocalTab);
            applyString(map, "hudLayout", hudLayout);
            Object cfgObj = map.get("customApiConfig");
            if (cfgObj instanceof Map && !((Map<?, ?>) cfgObj).isEmpty()) {
                CustomApiConfig cfg = gson.fromJson(gson.toJson(cfgObj), CustomApiConfig.class);
                if (cfg != null) customApiConfig.set(cfg);
            }
        } catch (Exception e) {
            QplayerModClient.LOGGER.warn("[DesktopSettings] load failed: {}", e.getMessage());
        }
    }

    private static void applyInt(Map<String, Object> map, String key, Property<Number> prop) {
        if (map.containsKey(key)) {
            Number val = map.get(key) instanceof Number ? (Number) map.get(key) : null;
            if (val != null) prop.set(val.intValue());
        }
    }

    private static void applyFloat(Map<String, Object> map, String key, Property<Number> prop) {
        if (map.containsKey(key)) {
            Number val = map.get(key) instanceof Number ? (Number) map.get(key) : null;
            if (val != null) prop.set(val.floatValue());
        }
    }

    private static void applyBool(Map<String, Object> map, String key, Property<Boolean> prop) {
        if (map.containsKey(key)) {
            Boolean val = map.get(key) instanceof Boolean ? (Boolean) map.get(key) : null;
            if (val != null) prop.set(val);
        }
    }

    private static void applyString(Map<String, Object> map, String key, Property<String> prop) {
        if (map.containsKey(key)) {
            String val = map.get(key) instanceof String ? (String) map.get(key) : null;
            if (val != null) prop.set(val);
        }
    }

    private void syncLyricConfig() {
        LyricConfig.instance.lyricFontSize.setValue(lyricFontSize.get() == null ? 28 : lyricFontSize.get().intValue());
        LyricConfig.instance.fontWeight.setValue(toFontWeight(lyricFontWeight.get()));
        LyricConfig.instance.lineSpacing.setValue(lyricLineSpacing.get() == null ? 2.0f : lyricLineSpacing.get().floatValue() / 100f);
        LyricConfig.instance.springPhysics.setValue(lyricSpring.get());
        LyricConfig.instance.scaleEmphasis.setValue(lyricScale.get());
        LyricConfig.instance.glow.setValue(lyricGlow.get());
        LyricConfig.instance.edgeBlur.setValue(lyricEdgeBlur.get());
        LyricConfig.instance.lyricShadow.setValue(lyricShadow.get());
        LyricConfig.instance.lyricLinearAnim.setValue(lyricLinearAnim.get());
        LyricConfig.instance.lyricProgressStyle.setValue(lyricProgressStyle.get());
        LyricConfig.instance.showRomaji.setValue(showRomaji.get());
        LyricConfig.instance.showTranslation.setValue(showTranslation.get());
    }

    private static LyricConfig.FontWeight toFontWeight(Number v) {
        int idx = v == null ? 2 : v.intValue();
        LyricConfig.FontWeight[] all = LyricConfig.FontWeight.values();
        if (idx < 0 || idx >= all.length) return LyricConfig.FontWeight.REGULAR;
        return all[idx];
    }

    @Override public float topInset() { return topInset.get() == null ? 0f : topInset.get().floatValue(); }
    @Override public boolean lyricBgStatic() { return lyricBgStatic.get(); }

    /** ClickGUI 毛玻璃：投影开关（QML 直接读 Property，这里给 Java 侧用） */
    public boolean glassShadow() { return glassShadow.get(); }

    /** ClickGUI 毛玻璃：发光（bloom）开关 */
    public boolean glassBloom() { return glassBloom.get(); }

    /** ClickGUI 动态背景（封面流体）开关 */
    public boolean guiFluidBg() { return guiFluidBg.get(); }
    @Override public boolean showLyrics() { return showLyrics.get(); }

    public boolean resolvedDark() {
        Number m = darkMode.get();
        int v = m == null ? 0 : m.intValue();
        // 0 currently falls back to dark, 1 is light, 2 is dark, 3 is themed dark.
        // Keep this getter consistent with the listener that updates StyleManager.
        return v != 1;
    }
    public float musicVolume() { return musicVolume.get() == null ? 0.8f : musicVolume.get().floatValue(); }
    public void setMusicVolume(float v) { musicVolume.set(v); }
    public void setShowLyrics(boolean v) { showLyrics.set(v); }

    /** 字体选择入口（FontPickerDialog 调用）：""=内置，"system"=系统默认，否则为字体族名 */
    public void setFontSelection(String family) { fontSelection.set(family == null ? "" : family); }

    /** 当前字体选择（方法式读取，与 fontSelection Property 等价；QML 读 Property 更利于绑定响应） */
    public String fontFamily() { return fontSelection.get() == null ? "" : fontSelection.get(); }

    /** QML 在编辑 settings.customApiConfig 的嵌套字段后调用：持久化当前配置
     *  （嵌套字段的普通反射直写不会触发 Property 监听 → 不会 autoSave）。 */
    public void saveCustomApiConfig() { save(); }
}
