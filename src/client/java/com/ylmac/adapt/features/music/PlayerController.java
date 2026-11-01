package com.ylmac.adapt.features.music;

import com.ylmac.adapt.config.DesktopSettings;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;
import com.ylmac.adapt.features.music.audio.DesktopMetadataReader;
import com.ylmac.adapt.features.music.bridge.SearchRow;
import com.ylmac.adapt.features.music.cache.DiskCache;
import com.ylmac.adapt.features.music.cache.SongMetaIndex;
import com.ylmac.adapt.features.music.customapi.CustomApiClient;
import com.ylmac.adapt.features.music.customapi.CustomApiConfig;
import com.ylmac.adapt.features.music.customapi.CustomSong;
import com.ylmac.adapt.features.music.library.LibraryScanner;
import com.ylmac.adapt.features.music.lyric.LyricLine;
import com.ylmac.adapt.features.music.lyric.LyricParser;
import com.ylmac.adapt.features.music.lyric.LrcParser;
import com.ylmac.adapt.features.music.lyric.LyricsDownloader;
import com.ylmac.adapt.features.music.model.Track;
import com.ylmac.adapt.features.music.netease.NeteaseClient;
import com.ylmac.adapt.features.music.netease.dto.NeteaseLyric;
import com.ylmac.adapt.features.music.netease.dto.NeteasePlaylist;
import com.ylmac.adapt.features.music.netease.dto.NeteaseSong;
import com.ylmac.adapt.features.music.netease.dto.NeteaseUser;
import com.ylmac.adapt.features.music.netease.dto.TogetherRoom;
import com.ylmac.adapt.features.music.netease.dto.TogetherSnapshot;
import com.ylmac.adapt.features.music.unblock.SongUnblocker;
import com.ylmac.adapt.bind.Slot;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import com.ylmac.adapt.config.store.AppDirs;
import com.ylmac.adapt.render.GpuContextHelper;
import com.ylmac.adapt.AdaptClient;
import com.ylmac.adapt.render.WorldColorSampler;

public final class PlayerController {

    private static JsonElement parseJsonLenient(String json) {
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setLenient(true);
        return JsonParser.parseReader(reader);
    }

    // ---- 播放状态 ----
    public final Slot<Boolean> playing = new Slot<>(false);
    public final Slot<String> title = new Slot<>("");
    public final Slot<String> artist = new Slot<>("");
    public final Slot<String> album = new Slot<>("");
    public final Slot<String> coverPath = new Slot<>("");
    public final Slot<Long> durationMs = new Slot<>(0L);
    public final Slot<Long> positionMs = new Slot<>(0L);
    // positionMs 每帧被 pump() 写入（音频进度），但不参与布局——只有
    // text: fmt(player.positionMs) 读它，每秒才变一次文字。
    // 用 setPaintOnly 避免每帧 bump changeVersion → settleLayout 从 60Hz 降到 1Hz。
    { positionMs.setInterceptor((p, v) -> {
        p.setPaintOnly(v instanceof Number ? ((Number) v).longValue() : 0L);
    }); }
    public final Slot<Boolean> loading = new Slot<>(false);
    public final Slot<String> toast = new Slot<>("");
    public final Slot<Float> volume = new Slot<>(0.8f);
    public final Slot<Integer> playMode = new Slot<>(0);
    public final Slot<Integer> index = new Slot<>(-1);
    public final Slot<Integer> lyricIndex = new Slot<>(-1);
    public final Slot<Boolean> lyricsOpen = new Slot<>(false);
    public final Slot<Boolean> queueOpen = new Slot<>(false);
    public final Slot<Float> lyricSlide = new Slot<>(0f);
    // 下拉手势/滑块动画中写 lyricSlide 不 bump changeVersion（否则每帧触发全树重排）。
    // Paint-only set：绑定仍会重算（overlay 的 y/scale/opacity 实时更新），但
    // settleLayout 被跳过，帧率不会因拖拽动画而 drop。
    // 注意 QML 写的是 JS double（Number），不是 Float，所以用 raw WriteInterceptor
    // 避免泛型擦除后 lambda 的 checkcast 把 Double 强转 Float 崩溃。
    { lyricSlide.setInterceptor((p, v) -> {
        p.setPaintOnly(v instanceof Number ? ((Number) v).floatValue() : 0f);
    }); }
    /** 歌词页下拉手势进行中：宿主 LyricCompositor 暂停自动 lerp lyricSlide，服从 QML 写入 */
    public final Slot<Boolean> lyricDrag = new Slot<>(false);
    public final Slot<Boolean> loggedIn = new Slot<>(false);
    public final Slot<String> userName = new Slot<>("");
    public final Slot<String> userAvatar = new Slot<>("");
    public final Slot<String> gpuBackend = new Slot<>("");
    public final Slot<Integer> backTick = new Slot<>(0);
    public final Slot<Boolean> updateAvailable = new Slot<>(false);
    public final Slot<String> updateVersion = new Slot<>("");
    public final Slot<String> updateNotes = new Slot<>("");

    public final Slot<Double> lyricProgress = new Slot<>(0.0);
    /** UI 显示用播放进度（降频更新，每 3 帧 set 一次 → 减少布局触发） */
    public final Slot<Float> displayProgress = new Slot<>(0f);
    // displayProgress 每 3 帧被 pump() 写入（进度条位置），不参与布局——
    // 只有 LinearProgress 的 Canvas onPaint 和 width 绑定读它。
    // 用 setPaintOnly 避免每 3 帧 bump changeVersion → settleLayout 全树重测。
    { displayProgress.setInterceptor((p, v) -> {
        p.setPaintOnly(v instanceof Number ? ((Number) v).floatValue() : 0f);
    }); }
    public final Slot<List<LyricLine>> lyrics = new Slot<>(Collections.emptyList());
    public final Slot<Boolean> lyricsCoverOnly = new Slot<>(Boolean.TRUE);
    public final Slot<byte[]> coverBytes = new Slot<>(null);
    public final Slot<String> coverUrl = new Slot<>("");

    // ---- 设置页 ----
    public final Slot<Integer> cacheSizeMB = new Slot<>(0);
    public final Slot<String> appVersion = new Slot<>("1.0.0");
    public final Slot<Boolean> logVisible = new Slot<>(false);
    public final Slot<String> logText = new Slot<>("");

    // ---- 歌词偏移 ----
    public final Slot<Boolean> lyricOffsetPanelOpen = new Slot<>(false);
    public final Slot<Integer> lyricOffsetMs = new Slot<>(0);

    // ---- 队列 ----
    public final Slot<List<Map<String, Object>>> queueTracks = new Slot<>(Collections.emptyList());
    public final Slot<List<Map<String, Object>>> customPlaylistTracks = new Slot<>(Collections.emptyList());
    public final Slot<Boolean> currentLikeable = new Slot<>(false);
    public final Slot<Boolean> currentLiked = new Slot<>(false);

    /** 调试：QML 进度条拖拽 press/release 计数（WorldColorSampler 跟踪并打日志） */
    public final Slot<Integer> qmlDragDebug = new Slot<>(0);

    // ---- 搜索 ----
    public final Slot<List<String>> searchHistory = new Slot<>(Collections.emptyList());
    public final Slot<List<String>> hotSearches = new Slot<>(Collections.emptyList());
    public final Slot<List<Map<String, Object>>> searchResults = new Slot<>(Collections.emptyList());
    public final Slot<Boolean> searchHasMore = new Slot<>(false);
    public final Slot<Boolean> searchLoading = new Slot<>(false);
    public final Slot<List<SearchRow>> searchRows = new Slot<>(Collections.emptyList());

    // ---- 缓存管理 ----
    public final Slot<List<Map<String, Object>>> cachedSongs = new Slot<>(Collections.emptyList());
    public final Slot<Boolean> playlistOffline = new Slot<>(false);

    // ---- 本地 ----
    public final Slot<List<Map<String, Object>>> tracks = new Slot<>(Collections.emptyList());
    public final Slot<Integer> libraryCount = new Slot<>(0);

    // ---- 主页 ----
    public final Slot<List<Map<String, Object>>> recommendPlaylists = new Slot<>(Collections.emptyList());
    public final Slot<List<Map<String, Object>>> recommendations = new Slot<>(Collections.emptyList());

    // ---- 收藏/播放列表 ----
    public final Slot<List<Map<String, Object>>> myPlaylists = new Slot<>(Collections.emptyList());
    public final Slot<Boolean> playlistLoading = new Slot<>(false);
    public final Slot<String> playlistTitle = new Slot<>("");
    public final Slot<Boolean> playlistOwned = new Slot<>(false);
    public final Slot<Boolean> playlistSubscribed = new Slot<>(false);
    public final Slot<Long> openPlaylistId = new Slot<>(0L);
    public final Slot<List<Map<String, Object>>> playlistTracks = new Slot<>(Collections.emptyList());
    /** 心动推荐（智能模式）进行中：详情页按钮显示"推荐中…"并禁用 */
    public final Slot<Boolean> intelligenceLoading = new Slot<>(false);

    // ---- 用户资料 ----
    public final Slot<Integer> userVipType = new Slot<>(0);
    public final Slot<Integer> userLevel = new Slot<>(0);
    public final Slot<String> userSignature = new Slot<>("");
    public final Slot<Integer> playlistCount = new Slot<>(0);
    public final Slot<Integer> likedCount = new Slot<>(0);

    // ---- 最近 ----
    public final Slot<List<Map<String, Object>>> recentSongs = new Slot<>(Collections.emptyList());

    // ---- 登录 ----
    // qml4j 不支持 Java 原生数组的 .length 属性, 用 List<List<Boolean>> 代替 boolean[][]
    public final Slot<List<List<Boolean>>> qrImage = new Slot<>(null);
    public final Slot<String> qrKey = new Slot<>("");
    public final Slot<Integer> qrStatus = new Slot<>(0); // 0=获取中, 801=等待, 802=已扫码, 803=成功, 800=过期

    // ---- 一起听 ----
    public final Slot<Boolean> listenTogetherInRoom = new Slot<>(false);
    public final Slot<Boolean> listenTogetherBusy = new Slot<>(false);
    public final Slot<String> listenTogetherRoomId = new Slot<>("");
    public final Slot<String> listenTogetherMembers = new Slot<>("");
    public final Slot<String> listenTogetherStatusText = new Slot<>("");
    public final Slot<String> listenTogetherInvitation = new Slot<>("");

    // ---- 队列管理 ----
    private final java.util.Set<Long> customPlaylistIds = new java.util.HashSet<>();
    private final List<Track> customPlaylistTrackList = new ArrayList<>();

    // ---- 喜欢状态（内存缓存，登录后一次拉取） ----
    private final java.util.Set<Long> likedIds = new java.util.HashSet<>();

    // ---- 调试日志环形缓冲 ----
    private static final int LOG_MAX_LINES = 300;
    private final StringBuilder logBuf = new StringBuilder();

    private volatile boolean updateChecked = false;

    // ---- 内部状态 ----
    private final AudioBackend audioBackend;
    private final DiskCache diskCache;
    private final NeteaseClient netease = NeteaseClient.INSTANCE;
    private final LibraryScanner libraryScanner;
    private final DesktopSettings settings;
    private final List<Track> localTracks = new ArrayList<>();
    private final List<Track> queueTrackList = new ArrayList<>();
    private final List<Track> playlistTrackList = new ArrayList<>();
    private final List<Track> recommendTrackList = new ArrayList<>();
    private final List<Track> searchTrackList = new ArrayList<>();
    private final List<Track> recentTrackList = new ArrayList<>();
    private final List<Track> localSearchResultTrackList = new ArrayList<>();
    private final SongMetaIndex songMetaIndex = new SongMetaIndex(new java.io.File(com.ylmac.adapt.config.store.AppDirs.cacheBase()));
    private final List<Track> cachedSongTracks = new ArrayList<>();
    private final ExecutorService cacheWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qplayer-cache");
        t.setDaemon(true);
        return t;
    });

    // ---- 持久化序列化 ----
    private final Gson gson = new Gson();

    // ---- 歌词偏移 ----
    private static final int LYRIC_OFFSET_MIN = -5000;
    private static final int LYRIC_OFFSET_MAX = 5000;
    private static final int LYRIC_OFFSET_STEP = 50;
    private final Map<String, Integer> lyricOffsets = new HashMap<>();

    // ---- 音量淡入淡出 ----
    private static final long FADE_IN_MS = 700L;
    private static final long FADE_TICK_MS = 16L;
    private final java.util.concurrent.ScheduledExecutorService fadeWorker =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "qplayer-fade");
                t.setDaemon(true);
                return t;
            });
    private volatile java.util.concurrent.ScheduledFuture<?> fadeTask;
    private volatile float userVolume = 0.8f;
    private volatile boolean fadeEnabled = true;
    private volatile float fadeGain = 1f;
    private volatile int fadeGeneration = 0;
    private volatile float fadeStartGain = 0f;
    private volatile float fadeEndGain = 1f;
    private volatile long fadeStartMs = 0L;
    private volatile long fadeDurationMs = FADE_IN_MS;
    private volatile Runnable fadeOnComplete = null;

    // ---- 一起听内部状态 ----
    private volatile String togetherRoomId = "";
    private volatile long togetherInviterId = 0L;
    private volatile long togetherLastSongId = 0L;
    private volatile int togetherLastCommandTick = 0;
    private final java.util.concurrent.ScheduledExecutorService togetHereWorker =
            java.util.concurrent.Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "qplayer-together");
                t.setDaemon(true);
                return t;
            });

    // ---- 搜索缓存和分页 ----
    private final ExecutorService searchWorker = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), r -> {
                Thread t = new Thread(r, "qplayer-search");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final long SEARCH_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int SEARCH_PAGE_SIZE = 50;
    private static final int SEARCH_CACHE_MAX_SIZE = 20;
    private volatile String currentSearchKey = "";
    private volatile String currentSearchQuery = "";
    private volatile int searchNextOffset = 0;
    private volatile boolean searchPageInFlight = false;
    private final Map<String, CacheEntry> searchCache =
            Collections.synchronizedMap(new LinkedHashMap<String, CacheEntry>(
                    SEARCH_CACHE_MAX_SIZE + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > SEARCH_CACHE_MAX_SIZE;
                }
            });

    private static final class CacheEntry {
        final List<NeteaseSong> songs;
        final int nextOffset;
        final boolean hasMore;
        final long timestamp;
        CacheEntry(List<NeteaseSong> songs, int nextOffset, boolean hasMore) {
            this.songs = Collections.unmodifiableList(new ArrayList<>(songs));
            this.nextOffset = nextOffset;
            this.hasMore = hasMore;
            this.timestamp = System.currentTimeMillis();
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > SEARCH_CACHE_TTL_MS;
        }
    }

    // ---- 自定义 API 音源（第三方音乐 API 适配） ----
    // 用户配置的第三方音乐 API（独立于网易云音源）；由设置页经
    // setCustomApiConfig() 推入，或直接在构造时持有设置页编辑的同一实例。
    // 永不为 null。
    private volatile CustomApiConfig customApiConfig = new CustomApiConfig();
    // 与 currentSearchKey 相同的防串台守卫：searchCustom() 发出前记录 key，
    // 异步 worker 只发布 key 仍与当前一致的结果。
    private volatile String currentCustomSearchKey = "";
    private final List<CustomSong> customSearchResultList = new ArrayList<>();
    // 自定义 API 歌词内存缓存，key = customId
    private final Map<String, List<LyricLine>> customLyricMem =
            Collections.synchronizedMap(new HashMap<String, List<LyricLine>>());
    // 自定义 API 播放地址/歌词解析专用 worker：与 worker 分开，避免
    // head-of-line-blocking（与 master 的 customWorker 同理）。
    private final ExecutorService customWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qplayer-custom");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService customSearchWorker = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), r -> {
                Thread t = new Thread(r, "qplayer-custom-search");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());

    private long neteaseUid = 0L;

    private java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qplayer-net");
        t.setDaemon(true);
        return t;
    });
    private java.util.Queue<Runnable> uiQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile java.util.function.Consumer<Runnable> mainExecutor;
    private volatile Runnable exitListener;

    public PlayerController(DesktopSettings settings) {
        this.settings = settings;
        // 自定义 API 配置：直接持有设置页编辑的同一实例（QML 字段级编辑实时可见）；
        // 整体替换（如设置持久化加载）时经 listener 同步。
        customApiConfig = settings.customApiConfig.get();
        settings.customApiConfig.addListener(cfg -> {
            if (cfg != null) customApiConfig = cfg;
        });
        audioBackend = new QplayerAudioBackend();
        audioBackend.setOnComplete(() -> post(() -> next()));
        audioBackend.setOnError(() -> post(() -> {
            loading.set(false);
            toast.set("播放出错, 请重试");
        }));
        diskCache = new DiskCache(500);
        songMetaIndex.load();
        libraryScanner = new LibraryScanner(new DesktopMetadataReader());

        // 版本号：fabric.mod.json 里的 ${version} 展开值
        try {
            String v = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer(AdaptClient.MOD_ID)
                    .map(m -> m.getMetadata().getVersion().getFriendlyString())
                    .orElse(null);
            if (v != null && !v.isEmpty()) appVersion.set(v);
        } catch (Throwable ignored) {}

        // 缓存设置生效：上限 + 目录（DiskCache.setMaxSizeMB/setBaseDir 一直没被调用）
        applyCacheSettings();
        settings.maxCacheSizeMB.addListener(mb -> {
            if (mb != null && mb.intValue() > 0) diskCache.setMaxSizeMB(mb.intValue());
        });
        settings.cacheFolder.addListener(dir -> {
            if (dir != null && !dir.toString().trim().isEmpty()) {
                diskCache.setBaseDir(dir.toString().trim());
            }
        });

        // 音量初始化 + 设置页改动实时生效
        float initVol = settings.musicVolume();
        userVolume = initVol;
        volume.set(initVol);
        applyEffectiveVolume(fadeGain);
        settings.musicVolume.addListener(v -> {
            float fv = v == null ? 0.8f : v.floatValue();
            if (Math.abs(fv - volume.get()) > 0.001f) {
                userVolume = fv;
                volume.set(fv);
                applyEffectiveVolume(fadeGain);
            }
        });

        gpuBackend.set(GpuContextHelper.backendName);

        // 网易云网络错误回调 → 调试日志 + toast（去重由 NeteaseClient 内部做）
        netease.setErrorListener(msg -> post(() -> {
            appendLog("[netease] " + msg);
            toast.set("网络错误: " + msg);
        }));

        // 监听 musicFolder 变化，自动重新扫描
        settings.musicFolder.addListener(folder -> {
            AdaptClient.LOGGER.info("[PlayerController] musicFolder changed: {}", folder);
            scanLocalLibrary(String.valueOf(folder));
        });

        // 初始扫描
        scanLocalLibrary(settings.musicFolder.get());

        // 如果已登录，加载用户数据
        if (netease.isLoggedIn()) {
            post(() -> onLoginSuccess());
        }

        // 加载推荐
        loadRecommendations();

        // 启动后检查 GitHub 新版本（失败静默，不打扰）
        checkForUpdate();

        // 一起听心跳：每秒 tick
        togetHereWorker.scheduleAtFixedRate(() -> {
            try { listenTogetherTick(); } catch (Throwable ignored) {}
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);

        // 恢复持久化状态：队列/自定义播放列表/搜索历史/歌词偏移
        loadQueue();
        loadCustomPlaylist();
        loadSearchHistory();
        loadLyricOffsets();
    }

    public void setMainExecutor(java.util.function.Consumer<Runnable> executor) {
        this.mainExecutor = executor;
    }

    /** 应用设置页的缓存上限与目录（默认值也算，让 UI 显示与实际一致） */
    private void applyCacheSettings() {
        Number mb = settings.maxCacheSizeMB.get();
        if (mb != null && mb.intValue() > 0) diskCache.setMaxSizeMB(mb.intValue());
        String folder = settings.cacheFolder.get();
        if (folder != null && !folder.trim().isEmpty()) {
            diskCache.setBaseDir(folder.trim());
        }
    }

    /** 追加一行到调试日志面板（环形缓冲，上限 LOG_MAX_LINES 行） */
    private void appendLog(String line) {
        if (line == null || line.isEmpty()) return;
        synchronized (logBuf) {
            if (logBuf.length() > 0) logBuf.append('\n');
            logBuf.append(line);
            int count = 0;
            for (int i = 0; i < logBuf.length(); i++) {
                if (logBuf.charAt(i) == '\n' && ++count > LOG_MAX_LINES) {
                    logBuf.delete(0, i + 1);
                    break;
                }
            }
            logText.set(logBuf.toString());
        }
    }

    private Track currentTrack() {
        int i = index.get();
        if (i < 0 || i >= queueTrackList.size()) return null;
        return queueTrackList.get(i);
    }

    /** 拉取当前用户红心歌单 id 集合：初始化 likedCount + currentLiked */
    private void loadLikedSongs() {
        async(() -> {
            try {
                long uid = neteaseUid > 0 ? neteaseUid : netease.loginUid();
                if (uid == 0) return null;
                final java.util.Set<Long> ids = netease.likedSongIds(uid);
                return () -> {
                    likedIds.clear();
                    likedIds.addAll(ids);
                    likedCount.set(likedIds.size());
                    Track cur = currentTrack();
                    if (cur != null && cur.source == Track.Source.NETEASE) {
                        currentLiked.set(likedIds.contains(cur.neteaseId));
                    }
                    appendLog("[netease] 红心歌单加载完成: " + likedIds.size() + " 首");
                };
            } catch (Exception e) {
                AdaptClient.LOGGER.warn("[PlayerController] loadLikedSongs failed: {}", e.getMessage());
                return null;
            }
        });
    }

    /** 启动时静默检查 GitHub 最新 release；mirrorEnabled 时走 gh-proxy 镜像 */
    private void checkForUpdate() {
        if (updateChecked) return;
        updateChecked = true;
        async(() -> {
            try {
                String api = "https://api.github.com/repos/TIMER-err/qplayer/releases/latest";
                if (Boolean.TRUE.equals(settings.mirrorEnabled.get())) {
                    api = "https://mirror.ghproxy.com/" + api;
                }
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(api).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "qplayer/1.0");
                c.setRequestProperty("Accept", "application/vnd.github+json");
                int status = c.getResponseCode();
                if (status / 100 != 2) return null;
                byte[] data;
                try (java.io.InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    data = out.toByteArray();
                }
                c.disconnect();
                JsonObject obj = parseJsonLenient(new String(data, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                String tag = obj.has("tag_name") && !obj.get("tag_name").isJsonNull()
                        ? obj.get("tag_name").getAsString() : null;
                String body = obj.has("body") && !obj.get("body").isJsonNull()
                        ? obj.get("body").getAsString() : null;
                final String ftag = tag;
                final String fbody = body;
                return () -> {
                    if (ftag == null || ftag.isEmpty()) return;
                    if (ftag.equalsIgnoreCase(appVersion.get())) return;
                    updateVersion.set(ftag);
                    updateNotes.set(fbody != null && !fbody.isEmpty() ? fbody : "新版本 " + ftag + " 已发布");
                    updateAvailable.set(true);
                    appendLog("[update] 发现新版本 " + ftag);
                };
            } catch (Exception e) {
                AdaptClient.LOGGER.info("[PlayerController] update check skipped: {}", e.toString());
                return null;
            }
        });
    }

    public void setExitListener(Runnable listener) {
        this.exitListener = listener;
    }

    public void post(Runnable r) {
        uiQueue.add(r);
    }

    /** 在 worker 线程执行，完成后通过 post() 回到 UI 线程 */
    private void async(java.util.function.Supplier<Runnable> task) {
        worker.submit(() -> {
            try {
                Runnable uiTask = task.get();
                if (uiTask != null) post(uiTask);
            } catch (Throwable e) {
                AdaptClient.LOGGER.error("[PlayerController] async error", e);
                post(() -> toast.set("网络错误: " + e.getMessage()));
            }
        });
    }

    /** 播放请求序号：每次 playTrack 递增，异步链完成时校验，防止旧链覆盖新歌 */
    private final java.util.concurrent.atomic.AtomicLong playRequestSeq =
            new java.util.concurrent.atomic.AtomicLong();

    private long lastCacheSizeCalcMs = 0;
    private int displayProgressFrame = 0;

    public void pump() {
        Runnable r;
        while ((r = uiQueue.poll()) != null) {
            try { r.run(); } catch (Throwable t) {
                AdaptClient.LOGGER.error("[PlayerController] task error", t);
            }
        }
        if (playing.get() && audioBackend != null) {
            positionMs.set(audioBackend.position());
        }
        // 显示用进度降频（每 3 帧 set 一次 → 减少 QML 布局触发：进度条 width 绑定不再每帧变）
        if (++displayProgressFrame >= 3) {
            displayProgressFrame = 0;
            long dur = durationMs.peek();
            long pos = positionMs.peek();
            displayProgress.set(dur > 0 ? Math.min(1f, pos / (float) dur) : 0f);
        }
        // totalSize() 会遍历缓存目录所有文件（stat 系统调用），节流到 1 次/秒
        long now = System.currentTimeMillis();
        if (now - lastCacheSizeCalcMs > 1000L) {
            lastCacheSizeCalcMs = now;
            cacheSizeMB.set((int)(diskCache.totalSize() / (1024L * 1024L)));
        }
    }

    // ==================== 播放控制 ====================

    /** 播放音质档位：设置开启高音质 → exhigh（网易云高音质），否则 standard */
    private String playLevel() {
        return Boolean.TRUE.equals(settings.highQuality.get()) ? "exhigh" : "standard";
    }

    public void play() {
        if (index.get() >= 0 && index.get() < queueTrackList.size()) {
            playTrack(index.get());
        }
    }

    public void playPause() {
        if (playing.get()) {
            // 暂停前先淡出，避免突兀爆音
            startFadeOut(300L, () -> post(() -> {
                audioBackend.pause();
                playing.set(false);
                fadeGain = 1f;
                applyEffectiveVolume(1f);
            }));
        } else {
            audioBackend.resume();
            playing.set(true);
            startFadeIn();
        }
    }

    private void playTrack(int i) {
        if (i < 0 || i >= queueTrackList.size()) return;
        final long reqId = playRequestSeq.incrementAndGet();
        index.set(i);
        Track t = queueTrackList.get(i);
        loading.set(true);
        String path = t.playable();
        AdaptClient.LOGGER.info("[PlayerController] playTrack: i={} track='{}' source={} http={}",
                i, t.title, t.source, path != null && path.startsWith("http"));
        // 网易云歌曲的 streamUrl 是 HTTP 地址，音频后端（PcmSource）无法直接播；
        // 播过一次之后 streamUrl 已缓存，必须先转回本地缓存文件路径。
        if (path != null && path.startsWith("http") && t.source == Track.Source.NETEASE) {
            String cached = diskCache.audioPath(t.neteaseId);
            boolean cachedOk = cached != null && new java.io.File(cached).exists()
                    && new java.io.File(cached).length() > 0;
            if (!cachedOk && diskCache.hasAudioUnblocked(t.neteaseId)) {
                cached = diskCache.audioPathUnblocked(t.neteaseId);
                cachedOk = cached != null && new java.io.File(cached).exists()
                        && new java.io.File(cached).length() > 0;
            }
            if (cachedOk) {
                path = cached;
            } else {
                // 缓存没落地（比如 async 下载还在进行），回到 null 走重新获取流程
                t.streamUrl = null;
                path = null;
            }
        }
        if (path == null || path.isEmpty()) {
            // 网易云歌曲需要先获取 URL
            if (t.source == Track.Source.NETEASE && t.neteaseId > 0) {
                AdaptClient.LOGGER.info("[PlayerController] playTrack netease id={} title='{}' loggedIn={}",
                        t.neteaseId, t.title, netease.isLoggedIn());
                async(() -> {
                    try {
                        NeteaseClient.UrlInfo info = netease.songUrlInfo(t.neteaseId, playLevel());
                        boolean nativeOk = info != null && info.url != null && !info.trial;
                        // 原生不可用（VIP/灰色/试听）且解锁开启 → 尝试第三方音源
                        String unblockTag = "";
                        if (!nativeOk && settings.unblockEnabled.get()) {
                            if (reqId == playRequestSeq.get()) {
                                post(() -> appendLog("[unblock] id=" + t.neteaseId + " 原生音源不可用，尝试解锁音源"));
                            }
                            String alt = SongUnblocker.resolve(t.neteaseId, t.title, t.artist);
                            if (alt != null && !alt.isEmpty()) {
                                AdaptClient.LOGGER.info("[PlayerController] unblock resolved id={} url={}",
                                        t.neteaseId, alt.length() > 80 ? alt.substring(0, 80) + "…" : alt);
                                info = new NeteaseClient.UrlInfo(alt, false);
                                t.trial = false;
                                unblockTag = " (解锁音源)";
                            }
                        }
                        if (info != null && info.url != null) {
                            t.streamUrl = info.url;
                            t.trial = info.trial;
                            // 缓存查找顺序：原生缓存 → 解锁缓存 → 下载（解锁音源用独立 key，不覆盖原生）
                            String cachedPath = diskCache.audioPath(t.neteaseId);
                            boolean nativeExists = cachedPath != null && new java.io.File(cachedPath).exists()
                                    && new java.io.File(cachedPath).length() > 0;
                            if (!nativeExists) {
                                if (diskCache.hasAudioUnblocked(t.neteaseId)) {
                                    cachedPath = diskCache.audioPathUnblocked(t.neteaseId);
                                } else {
                                    cachedPath = null;
                                }
                            }
                            boolean needsDownload = cachedPath == null;
                            if (needsDownload) {
                                String urlPreview = info.url.length() > 80 ? info.url.substring(0, 80) + "…" : info.url;
                                AdaptClient.LOGGER.info("[PlayerController] downloading netease id={} url={}",
                                        t.neteaseId, urlPreview);
                                if (unblockTag.isEmpty()) {
                                    diskCache.cacheAudio(info.url, t.neteaseId);
                                    cachedPath = diskCache.audioPath(t.neteaseId);
                                } else {
                                    diskCache.cacheAudioUnblocked(info.url, t.neteaseId);
                                    cachedPath = diskCache.audioPathUnblocked(t.neteaseId);
                                }
                            }
                            // 在播放前预先下载封面，确保歌词页打开时 coverBytes 已就绪
                            byte[] preloadedCover = null;
                            if (t.coverUrl != null) {
                                try {
                                    java.net.URL curl = new java.net.URL(t.coverUrl);
                                    java.net.HttpURLConnection cc = (java.net.HttpURLConnection) curl.openConnection();
                                    cc.setConnectTimeout(10000);
                                    cc.setReadTimeout(10000);
                                    cc.setRequestProperty("User-Agent", "qplayer/1.0");
                                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                                    try (java.io.InputStream in = cc.getInputStream()) {
                                        byte[] buf = new byte[8192];
                                        int n;
                                        while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                                    }
                                    cc.disconnect();
                                    preloadedCover = baos.toByteArray();
                                    AdaptClient.LOGGER.info("[PlayerController] preloaded cover {} bytes for id={}",
                                            preloadedCover.length, t.neteaseId);
                                } catch (Exception e) {
                                    AdaptClient.LOGGER.warn("[PlayerController] preload cover failed for id={}: {}",
                                            t.neteaseId, e.getMessage());
                                }
                            } else {
                                AdaptClient.LOGGER.warn("[PlayerController] t.coverUrl IS NULL for id={} title='{}'",
                                        t.neteaseId, t.title);
                            }
                            // 加载网易云歌词
                            List<LyricLine> parsedLyricsTmp;
                            try {
                                NeteaseLyric nl = netease.lyric(t.neteaseId);
                                if (nl != null) {
                                    parsedLyricsTmp = LyricParser.fromNeteaseStrings(nl.yrc, nl.lrc, nl.tlyric, nl.romalrc);
                                    AdaptClient.LOGGER.info("[PlayerController] loaded netease lyric for id={} lines={}",
                                            t.neteaseId, parsedLyricsTmp.size());
                                } else {
                                    parsedLyricsTmp = Collections.emptyList();
                                }
                            } catch (Exception e) {
                                AdaptClient.LOGGER.warn("[PlayerController] load netease lyric failed for id={}: {}",
                                        t.neteaseId, e.getMessage());
                                parsedLyricsTmp = Collections.emptyList();
                            }
                            final List<LyricLine> parsedLyrics = parsedLyricsTmp;
                            if (cachedPath != null && new java.io.File(cachedPath).exists()
                                    && new java.io.File(cachedPath).length() > 0) {
                                final String playPath = cachedPath;
                                final byte[] cover = preloadedCover;
                                final String sourceTag = unblockTag;
                                AdaptClient.LOGGER.info("[PlayerController] playing cached file {} ({} bytes) for id={}",
                                        playPath, new java.io.File(playPath).length(), t.neteaseId);
                                post(() -> {
                                    if (reqId != playRequestSeq.get()) return;
                                    playBackend(playPath, 0);
                                    playing.set(true);
                                    loading.set(false);
                                    updateNowPlaying(t);
                                    if (!sourceTag.isEmpty()) {
                                        appendLog("[unblock] 已通过解锁音源播放 id=" + t.neteaseId);
                                        toast.set("已切换解锁音源播放");
                                    }
                                    if (cover != null) {
                                        coverBytes.set(cover);
                                        AdaptClient.LOGGER.info("[PlayerController] set preloaded cover {} bytes for id={}", cover.length, t.neteaseId);
                                    }
                                    lyrics.set(parsedLyrics);
                                    lyricsCoverOnly.set(parsedLyrics.isEmpty());
                                    AdaptClient.LOGGER.info("[PlayerController] audio playing started, volume={}", volume.get());
                                });
                            } else {
                                post(() -> {
                                    if (reqId != playRequestSeq.get()) return;
                                    loading.set(false);
                                    toast.set("下载失败, 请检查网络");
                                });
                            }
                        } else {
                            post(() -> {
                                if (reqId != playRequestSeq.get()) return;
                                loading.set(false);
                                if (!netease.isLoggedIn()) {
                                    toast.set("未登录, 部分歌曲无法播放, 请先扫码登录");
                                } else {
                                    toast.set("无法获取播放链接 (可能为VIP歌曲)");
                                }
                            });
                        }
                    } catch (Exception e) {
                        AdaptClient.LOGGER.error("[PlayerController] playTrack failed", e);
                        post(() -> {
                            if (reqId != playRequestSeq.get()) return;
                            loading.set(false);
                            toast.set("播放失败: " + e.getMessage());
                        });
                    }
                    return null;
                });
                return;
            }
            // 自定义 API 歌曲：按 customId 异步解析播放地址（streamUrl 为空时）
            if (t.source == Track.Source.CUSTOM_API) {
                resolveAndPlayCustom(t);
                return;
            }
            loading.set(false);
            toast.set("无法播放: " + t.title);
            return;
        }
        // 加载本地歌词（优先嵌入式/sidecar 文本 lyricText，回退到 lyricFilePath 缓存文件）
        List<LyricLine> localLyrics = Collections.emptyList();
        if (t.lyricText != null && !t.lyricText.isEmpty()) {
            try {
                localLyrics = LyricParser.fromString(t.lyricText, t.translationFilePath, t.romajiFilePath);
                AdaptClient.LOGGER.info("[PlayerController] loaded local lyric lines={} from embedded/sidecar text", localLyrics.size());
            } catch (Exception e) {
                AdaptClient.LOGGER.warn("[PlayerController] parse local lyric text failed: {}", e.getMessage());
            }
        } else if (t.lyricFilePath != null) {
            try {
                localLyrics = LyricParser.parse(t.lyricFilePath, t.translationFilePath, t.romajiFilePath);
                AdaptClient.LOGGER.info("[PlayerController] loaded local lyric lines={} from {}", localLyrics.size(), t.lyricFilePath);
            } catch (Exception e) {
                AdaptClient.LOGGER.warn("[PlayerController] parse local lyric failed: {}", e.getMessage());
            }
        }
        lyrics.set(localLyrics);
        lyricsCoverOnly.set(localLyrics.isEmpty());
        // 本地曲目没有任何嵌入式/同目录歌词时，后台从 LRCLIB 自动下载并缓存
        if (localLyrics.isEmpty()) {
            autoDownloadLyrics(t);
        }
        // 网易云歌曲：streamUrl 已缓存走本地分支时，歌词/封面也要异步加载
        // （第一次播放的异步分支会做；这里补上，否则切回时歌词页空、流体背景无源）。
        if (t.source == Track.Source.NETEASE) {
            loadNeteaseMetaAsync(t);
        } else if (t.source == Track.Source.CUSTOM_API) {
            loadCustomLyrics(t);
        }
        playBackend(path, 0);
        playing.set(true);
        loading.set(false);
        updateNowPlaying(t);
    }

    /** 本地曲目无歌词来源时，后台从 LRCLIB 拉取并缓存（兜底：已有磁盘缓存则直接应用）。 */
    private void autoDownloadLyrics(Track t) {
        if (t == null || t.filePath == null) return;
        final String title = t.title;
        final String artist = t.artist;
        final String album = t.album;
        if (title == null || title.trim().isEmpty()) return;
        final String key = "local:" + t.filePath;

        // 磁盘缓存命中：读取并在当前曲目仍是 t 时应用
        if (diskCache.hasLocalLyric(key)) {
            String path = diskCache.getLocalLyric(key);
            applyDownloadedLyrics(t, readLyricFile(path));
            return;
        }

        cacheWorker.submit(() -> {
            try {
                String lrc = LyricsDownloader.downloadLrc(title, artist, album);
                if (lrc == null || lrc.isEmpty()) return;
                List<LyricLine> lines = LrcParser.parse(lrc);
                if (lines.isEmpty()) return;
                diskCache.cacheLocalLyric(lrc.getBytes(java.nio.charset.StandardCharsets.UTF_8), key);
                AdaptClient.LOGGER.info("[PlayerController] downloaded local lyric lines={} for '{}'", lines.size(), title);
                post(() -> {
                    if (index.get() >= queueTrackList.size() || queueTrackList.get(index.get()) != t) return;
                    lyrics.set(lines);
                    lyricsCoverOnly.set(false);
                });
            } catch (Throwable e) {
                AdaptClient.LOGGER.warn("[PlayerController] auto-download lyric failed for '{}': {}", title, e.getMessage());
            }
        });
    }

    /** 在 UI 线程将已解析的下载歌词应用到当前曲目（仅当仍是 t 时）。 */
    private void applyDownloadedLyrics(Track t, String content) {
        if (content == null || content.isEmpty()) return;
        List<LyricLine> lines = LrcParser.parse(content);
        if (lines.isEmpty()) return;
        post(() -> {
            if (index.get() >= queueTrackList.size() || queueTrackList.get(index.get()) != t) return;
            lyrics.set(lines);
            lyricsCoverOnly.set(false);
        });
    }

    /** 网易云歌曲切回（streamUrl 已缓存）时异步补歌词 + 封面。
     *  仅在用户仍停留在该曲目时应用，避免切歌后旧回调覆盖。 */
    private void loadNeteaseMetaAsync(final Track t) {
        final long id = t.neteaseId;
        if (id <= 0) return;
        async(() -> {
            try {
                NeteaseLyric nl = netease.lyric(id);
                List<LyricLine> parsed = nl != null
                        ? LyricParser.fromNeteaseStrings(nl.yrc, nl.lrc, nl.tlyric, nl.romalrc)
                        : Collections.emptyList();
                byte[] cover = null;
                if (t.coverUrl != null) {
                    try {
                        java.net.URL curl = new java.net.URL(t.coverUrl);
                        java.net.HttpURLConnection cc = (java.net.HttpURLConnection) curl.openConnection();
                        cc.setConnectTimeout(10000);
                        cc.setReadTimeout(10000);
                        cc.setRequestProperty("User-Agent", "qplayer/1.0");
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        try (java.io.InputStream in = cc.getInputStream()) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                        }
                        cc.disconnect();
                        cover = baos.toByteArray();
                    } catch (Exception ignored) {}
                }
                final List<LyricLine> lines = parsed;
                final byte[] cov = cover;
                return () -> {
                    if (index.get() < 0 || index.get() >= queueTrackList.size()
                            || queueTrackList.get(index.get()) != t) return;
                    lyrics.set(lines);
                    lyricsCoverOnly.set(lines.isEmpty());
                    if (cov != null) coverBytes.set(cov);
                };
            } catch (Exception e) {
                AdaptClient.LOGGER.warn("[PlayerController] loadNeteaseMetaAsync failed for id={}: {}", id, e.getMessage());
                return null;
            }
        });
    }

    /** 解析并播放自定义 API 曲目：在 customWorker 上拉播放地址，回到主线程
     *  播放（用户已切走则丢弃）。失败 toast 提示，与网易云解析失败一致。 */
    private void resolveAndPlayCustom(Track t) {
        final String id = t.customId;
        final CustomApiConfig cfg = customApiConfig;
        customWorker.submit(() -> {
            try {
                String url = CustomApiClient.resolveUrl(cfg, id);
                final String fUrl = url;
                post(() -> {
                    if (index.get() < 0 || index.get() >= queueTrackList.size()
                            || queueTrackList.get(index.get()) != t) return;
                    if (fUrl == null) {
                        AdaptClient.LOGGER.warn("[PlayerController] custom-api song {} has no url", id);
                        loading.set(false);
                        toast.set("自定义源解析失败");
                        return;
                    }
                    t.streamUrl = fUrl;
                    AdaptClient.LOGGER.info("[PlayerController] play custom-api: {} — {}",
                            t.title, fUrl.length() > 80 ? fUrl.substring(0, 80) + "…" : fUrl);
                    playBackend(fUrl, 0);
                    playing.set(true);
                    loading.set(false);
                    updateNowPlaying(t);
                    loadCustomLyrics(t);
                });
            } catch (Throwable e) {
                AdaptClient.LOGGER.warn("[PlayerController] custom resolve failed for {}: {}", id, e.getMessage());
                post(() -> {
                    if (index.get() >= 0 && index.get() < queueTrackList.size()
                            && queueTrackList.get(index.get()) == t) {
                        loading.set(false);
                        toast.set("自定义源解析失败");
                    }
                });
            }
        });
    }

    /** 自定义 API 歌词：仅当用户配置了歌词接口时拉取（可选功能，该适配器
     *  面向的自托管 API 不一定提供歌词）。运行在 {@link #customWorker}，
     *  与 {@link #resolveAndPlayCustom} 共用队列以避免 head-of-line-blocking。 */
    private void loadCustomLyrics(Track t) {
        final String id = t.customId;
        if (id == null || id.isEmpty()) return;
        List<LyricLine> mem = customLyricMem.get(id);
        if (mem != null) {
            post(() -> {
                if (index.get() >= 0 && index.get() < queueTrackList.size()
                        && queueTrackList.get(index.get()) == t) {
                    lyrics.set(mem);
                    lyricsCoverOnly.set(mem.isEmpty());
                }
            });
            return;
        }
        final CustomApiConfig cfg = customApiConfig;
        customWorker.submit(() -> {
            List<LyricLine> ly = Collections.emptyList();
            try {
                String lrc = CustomApiClient.resolveLyric(cfg, id);
                if (lrc != null && !lrc.isEmpty()) {
                    ly = LyricParser.fromNeteaseStrings(null, lrc, null, null);
                    if (!ly.isEmpty()) customLyricMem.put(id, ly);
                }
            } catch (Throwable e) {
                AdaptClient.LOGGER.warn("[PlayerController] custom lyric fetch failed for {}: {}", id, e.getMessage());
            }
            final List<LyricLine> lines = ly;
            post(() -> {
                if (index.get() >= 0 && index.get() < queueTrackList.size()
                        && queueTrackList.get(index.get()) == t) {
                    lyrics.set(lines);
                    lyricsCoverOnly.set(lines.isEmpty());
                }
            });
        });
    }

    private static String readLyricFile(String path) {
        if (path == null) return null;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            String utf8 = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (utf8.indexOf('\uFFFD') < 0) return utf8;
            return new String(bytes, java.nio.charset.Charset.forName("GBK"));
        } catch (Throwable e) {
            return null;
        }
    }

    private void updateNowPlaying(Track t) {
        applyTrackLyricOffset(t);
        title.set(t.title != null ? t.title : "");
        artist.set(t.artist != null ? t.artist : "");
        album.set(t.album != null ? t.album : "");
        durationMs.set(t.durationMs > 0 ? t.durationMs : audioBackend.duration());
        currentLikeable.set(t.source == Track.Source.NETEASE && t.neteaseId > 0);
        // 切歌时刷新喜欢状态（内存集合；登录后 loadLikedSongs 已填充）
        currentLiked.set(t.source == Track.Source.NETEASE && likedIds.contains(t.neteaseId));
        // 重置封面状态，避免上一首的封面残留
        coverBytes.set(null);
        coverUrl.set("");
        coverPath.set("");
        if (t.coverLocalPath != null) {
            try {
                byte[] data = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(t.coverLocalPath));
                coverBytes.set(data);
                AdaptClient.LOGGER.info("[PlayerController] loaded local cover {} bytes from {}", data.length, t.coverLocalPath);
            } catch (Exception e) {
                AdaptClient.LOGGER.warn("[PlayerController] failed to read coverLocalPath {}: {}", t.coverLocalPath, e.getMessage());
            }
            coverPath.set(t.coverLocalPath);
        } else if (t.coverThumbPath != null && !t.coverThumbPath.startsWith("http")) {
            coverPath.set(t.coverThumbPath);
            try {
                byte[] data = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(t.coverThumbPath));
                coverBytes.set(data);
            } catch (Exception ignored) {}
        }
        if (t.coverUrl != null) {
            coverUrl.set(t.coverUrl);
        }
        updateQueueTracks();
    }

    /** 从本地文件路径播放（旧接口，LocalPage 直接传 index） */
    public void playAt(int i) {
        if (i < 0 || i >= queueTrackList.size()) return;
        playTrack(i);
    }

    public void next() {
        if (queueTrackList.isEmpty()) return;
        int mode = playMode.get();
        int nextIdx;
        if (mode == 1 && queueTrackList.size() > 1) {
            nextIdx = new java.util.Random().nextInt(queueTrackList.size());
        } else if (mode == 2) {
            nextIdx = index.get();
        } else {
            nextIdx = (index.get() + 1) % queueTrackList.size();
        }
        AdaptClient.LOGGER.info("[PlayerController] next: idx={} nextIdx={} queueSize={} mode={}",
                index.get(), nextIdx, queueTrackList.size(), mode);
        playTrack(nextIdx);
    }

    public void prev() {
        if (queueTrackList.isEmpty()) return;
        int prevIdx = (index.get() - 1 + queueTrackList.size()) % queueTrackList.size();
        AdaptClient.LOGGER.info("[PlayerController] prev: idx={} prevIdx={} queueSize={}",
                index.get(), prevIdx, queueTrackList.size());
        playTrack(prevIdx);
    }

    public void toggle() { playPause(); }
    public void seek(long ms) {
        if (audioBackend != null) audioBackend.seek(ms);
    }

    public void cyclePlayMode() {
        playMode.set((playMode.get() + 1) % 3);
        toast.set(playMode.get() == 1 ? "随机播放" : (playMode.get() == 2 ? "单曲循环" : "列表循环"));
    }

    public void setVolume(float v) {
        userVolume = v;
        volume.set(v);
        applyEffectiveVolume(fadeGain);
        // 回写设置（持久化 + 下一次启动生效）
        if (Math.abs(settings.musicVolume() - v) > 0.001f) {
            settings.setMusicVolume(v);
        }
    }

    public void setPlayMode(int mode) { playMode.set(mode); }
    public void setQueueOpen(boolean open) { queueOpen.set(open); }
    public void setLyricsOpen(boolean open) {
        AdaptClient.LOGGER.info("[PlayerController] setLyricsOpen({})", open);
        lyricsOpen.set(open);
    }

    // ==================== 歌词偏移 ====================

    public void setLyricOffsetPanelOpen(boolean open) {
        lyricOffsetPanelOpen.set(open);
    }

    /** 设置当前歌曲歌词偏移：就近 50ms 取整，夹在 ±5000ms，并持久化 */
    public void setLyricOffset(int valueMs) {
        int rounded = Math.round(valueMs / (float) LYRIC_OFFSET_STEP) * LYRIC_OFFSET_STEP;
        rounded = Math.max(LYRIC_OFFSET_MIN, Math.min(LYRIC_OFFSET_MAX, rounded));
        lyricOffsetMs.set(rounded);
        Track t = currentTrack();
        if (t != null) {
            lyricOffsets.put(trackOffsetKey(t), rounded);
        }
        saveLyricOffsets();
    }

    public void resetLyricOffset() {
        lyricOffsetMs.set(0);
        Track t = currentTrack();
        if (t != null) {
            lyricOffsets.put(trackOffsetKey(t), 0);
        }
        saveLyricOffsets();
    }

    /** 切歌时应用该曲目保存的歌词偏移（无记录则重置为 0） */
    public void applyTrackLyricOffset(Track t) {
        if (t == null) {
            lyricOffsetMs.set(0);
            return;
        }
        Integer off = lyricOffsets.get(trackOffsetKey(t));
        lyricOffsetMs.set(off != null ? off : 0);
    }

    private static String trackOffsetKey(Track t) {
        if (t.source == Track.Source.NETEASE && t.neteaseId > 0) {
            return "netease:" + t.neteaseId;
        }
        return "local:" + (t.filePath != null ? t.filePath : (t.contentUri != null ? t.contentUri : ""));
    }

    private void loadLyricOffsets() {
        try {
            java.nio.file.Path f = java.nio.file.Paths.get(com.ylmac.adapt.config.store.AppDirs.base(), "lyric-offsets.json");
            if (!java.nio.file.Files.exists(f)) return;
            Map<String, Object> m = gson.fromJson(
                    new String(java.nio.file.Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Object>>() {}.getType());
            if (m == null) return;
            lyricOffsets.clear();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getValue() instanceof Number) {
                    lyricOffsets.put(e.getKey(), ((Number) e.getValue()).intValue());
                }
            }
        } catch (Exception e) {
            AdaptClient.LOGGER.warn("[PlayerController] loadLyricOffsets failed: {}", e.getMessage());
        }
    }

    private void saveLyricOffsets() {
        try {
            java.nio.file.Path f = java.nio.file.Paths.get(com.ylmac.adapt.config.store.AppDirs.base(), "lyric-offsets.json");
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.write(f, gson.toJson(lyricOffsets).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            AdaptClient.LOGGER.warn("[PlayerController] saveLyricOffsets failed: {}", e.getMessage());
        }
    }

    // ==================== 音量淡入淡出 ====================

    public void setFadeEnabled(boolean enabled) {
        fadeEnabled = enabled;
        if (!enabled) {
            fadeGeneration++;
            java.util.concurrent.ScheduledFuture<?> f = fadeTask;
            fadeTask = null;
            if (f != null) f.cancel(false);
            fadeGain = 1f;
            fadeOnComplete = null;
            applyEffectiveVolume(1f);
        }
    }

    /** 播放淡入：增益 0 → 1，历时 FADE_IN_MS */
    public void startFadeIn() {
        final int gen = ++fadeGeneration;
        fadeStartGain = 0f;
        fadeEndGain = 1f;
        fadeStartMs = System.currentTimeMillis();
        fadeDurationMs = FADE_IN_MS;
        fadeOnComplete = null;
        if (!fadeEnabled) {
            fadeGain = 1f;
            applyEffectiveVolume(1f);
            return;
        }
        fadeGain = 0f;
        applyEffectiveVolume(0f);
        scheduleFadeTicks(gen);
    }

    /** 暂停淡出：当前增益 → 0，历时 durationMs，完成后回调 */
    public void startFadeOut(long durationMs, Runnable onComplete) {
        final int gen = ++fadeGeneration;
        java.util.concurrent.ScheduledFuture<?> old = fadeTask;
        fadeTask = null;
        if (old != null) old.cancel(false);
        if (!fadeEnabled) {
            fadeGain = 0f;
            applyEffectiveVolume(0f);
            if (onComplete != null) onComplete.run();
            return;
        }
        float startGain = fadeGain;
        fadeStartGain = startGain;
        fadeEndGain = 0f;
        fadeStartMs = System.currentTimeMillis();
        fadeDurationMs = Math.max(0L, durationMs);
        fadeOnComplete = onComplete;
        if (fadeDurationMs <= 0) {
            fadeGain = 0f;
            applyEffectiveVolume(0f);
            Runnable cb = fadeOnComplete;
            fadeOnComplete = null;
            if (cb != null) cb.run();
            return;
        }
        scheduleFadeTicks(gen);
    }

    private void scheduleFadeTicks(final int gen) {
        java.util.concurrent.ScheduledFuture<?> old = fadeTask;
        fadeTask = null;
        if (old != null) old.cancel(false);
        fadeTask = fadeWorker.scheduleAtFixedRate(() -> tickVolumeFade(gen), 0, FADE_TICK_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** 16ms 线性爬坡：超出目标或代次过期自行终止 */
    private void tickVolumeFade(int generation) {
        if (generation != fadeGeneration || !fadeEnabled) return;
        long elapsed = System.currentTimeMillis() - fadeStartMs;
        long dur = fadeDurationMs;
        if (dur <= 0) {
            fadeGain = fadeEndGain;
        } else {
            float t = elapsed / (float) dur;
            if (t > 1f) t = 1f;
            fadeGain = fadeStartGain + (fadeEndGain - fadeStartGain) * t;
        }
        applyEffectiveVolume(fadeGain);
        if (elapsed >= dur) {
            Runnable cb = fadeOnComplete;
            fadeOnComplete = null;
            java.util.concurrent.ScheduledFuture<?> f = fadeTask;
            fadeTask = null;
            if (f != null) f.cancel(false);
            if (cb != null) cb.run();
        }
    }

    private void applyEffectiveVolume(float gain) {
        if (audioBackend != null) audioBackend.setVolume(userVolume * gain);
    }

    /** 启动后端播放并淡入 */
    private void playBackend(String path, long startMs) {
        if (audioBackend != null) audioBackend.play(path, startMs);
        startFadeIn();
    }

    // ==================== 持久化状态 ====================

    public void saveSessionState() {
        saveQueue();
        saveCustomPlaylist();
        saveSearchHistory();
        saveLyricOffsets();
    }

    private void saveQueue() {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("playIndex", index.get());
            state.put("positionMs", playing.get() && audioBackend != null ? audioBackend.position() : positionMs.get());
            state.put("playMode", playMode.get());
            List<Map<String, Object>> tracks = new ArrayList<>();
            for (Track t : queueTrackList) tracks.add(trackForState(t));
            state.put("tracks", tracks);
            java.nio.file.Path f = java.nio.file.Paths.get(com.ylmac.adapt.config.store.AppDirs.base(), "queue.json");
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.write(f, gson.toJson(state).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            AdaptClient.LOGGER.warn("[PlayerController] saveQueue failed: {}", e.getMessage());
        }
    }

    private void loadQueue() {
        try {
            java.nio.file.Path f = java.nio.file.Paths.get(com.ylmac.adapt.config.store.AppDirs.base(), "queue.json");
            if (!java.nio.file.Files.exists(f)) return;
            Map<String, Object> state = gson.fromJson(
                    new String(java.nio.file.Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Object>>() {}.getType());
            if (state == null) return;
            Object tracksObj = state.get("tracks");
            if (tracksObj instanceof List) {
                queueTrackList.clear();
                for (Object o : (List<?>) tracksObj) {
                    if (o instanceof Map) {
                        Track t = trackFromState((Map<String, Object>) o);
                        if (t != null) queueTrackList.add(t);
                    }
                }
                updateQueueTracks();
            }
            Number pi = state.get("playIndex") instanceof Number ? (Number) state.get("playIndex") : null;
            Number pos = state.get("positionMs") instanceof Number ? (Number) state.get("positionMs") : null;
            Number pm = state.get("playMode") instanceof Number ? (Number) state.get("playMode") : null;
            if (pm != null) playMode.set(pm.intValue());
            if (pos != null) positionMs.set(pos.longValue());
            final int restoredIndex = pi != null ? pi.intValue() : -1;
            final long savedPos = pos != null ? pos.longValue() : 0L;
            if (restoredIndex >= 0 && restoredIndex < queueTrackList.size() && !queueTrackList.isEmpty()) {
                post(() -> {
                    if (restoredIndex >= 0 && restoredIndex < queueTrackList.size()) {
                        playTrack(restoredIndex);
                        if (savedPos > 0 && audioBackend != null) audioBackend.seek(savedPos);
                    }
                });
            }
        } catch (Exception e) {
            AdaptClient.LOGGER.warn("[PlayerController] loadQueue failed: {}", e.getMessage());
        }
    }

    public void saveCustomPlaylist() {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            List<Map<String, Object>> tracks = new ArrayList<>();
            for (Track t : customPlaylistTrackList) tracks.add(trackForState(t));
            state.put("tracks", tracks);
            java.nio.file.Path f = java.nio.file.Paths.get(com.ylmac.adapt.config.store.AppDirs.base(), "custom-playlist.json");
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.write(f, gson.toJson(state).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            AdaptClient.LOGGER.warn("[PlayerController] saveCustomPlaylist failed: {}", e.getMessage());
        }
    }

    private void loadCustomPlaylist() {
        try {
            java.nio.file.Path f = java.nio.file.Paths.get(com.ylmac.adapt.config.store.AppDirs.base(), "custom-playlist.json");
            if (!java.nio.file.Files.exists(f)) return;
            Map<String, Object> state = gson.fromJson(
                    new String(java.nio.file.Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Object>>() {}.getType());
            if (state == null) return;
            customPlaylistTrackList.clear();
            customPlaylistIds.clear();
            Object tracksObj = state.get("tracks");
            if (tracksObj instanceof List) {
                for (Object o : (List<?>) tracksObj) {
                    if (o instanceof Map) {
                        Track t = trackFromState((Map<String, Object>) o);
                        if (t != null) {
                            customPlaylistTrackList.add(t);
                            if (t.neteaseId > 0) customPlaylistIds.add(t.neteaseId);
                        }
                    }
                }
            }
            updateCustomPlaylistTracks();
        } catch (Exception e) {
            AdaptClient.LOGGER.warn("[PlayerController] loadCustomPlaylist failed: {}", e.getMessage());
        }
    }

    public void saveSearchHistory() {
        try {
            java.nio.file.Path f = java.nio.file.Paths.get(com.ylmac.adapt.config.store.AppDirs.base(), "search-history.json");
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.write(f, gson.toJson(searchHistory.get()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            AdaptClient.LOGGER.warn("[PlayerController] saveSearchHistory failed: {}", e.getMessage());
        }
    }

    private void loadSearchHistory() {
        try {
            java.nio.file.Path f = java.nio.file.Paths.get(com.ylmac.adapt.config.store.AppDirs.base(), "search-history.json");
            if (!java.nio.file.Files.exists(f)) return;
            List<String> hist = gson.fromJson(
                    new String(java.nio.file.Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8),
                    new TypeToken<List<String>>() {}.getType());
            if (hist != null) searchHistory.set(hist);
        } catch (Exception e) {
            AdaptClient.LOGGER.warn("[PlayerController] loadSearchHistory failed: {}", e.getMessage());
        }
    }

    private Map<String, Object> trackForState(Track t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", t.source == Track.Source.NETEASE ? "netease"
                : (t.source == Track.Source.CUSTOM_API ? "custom" : "local"));
        m.put("neteaseId", t.neteaseId);
        m.put("customId", t.customId);
        m.put("filePath", t.filePath);
        m.put("contentUri", t.contentUri);
        m.put("streamUrl", t.streamUrl);
        m.put("trial", t.trial);
        m.put("title", t.title);
        m.put("artist", t.artist);
        m.put("album", t.album);
        m.put("durationMs", t.durationMs);
        m.put("coverUrl", t.coverUrl);
        m.put("coverThumbPath", t.coverThumbPath);
        m.put("coverLocalPath", t.coverLocalPath);
        m.put("lyricFilePath", t.lyricFilePath);
        m.put("translationFilePath", t.translationFilePath);
        m.put("romajiFilePath", t.romajiFilePath);
        return m;
    }

    private Track trackFromState(Map<String, Object> m) {
        if (m == null) return null;
        Object src = m.get("source");
        Track t = new Track();
        t.source = "netease".equals(src) ? Track.Source.NETEASE
                : ("custom".equals(src) ? Track.Source.CUSTOM_API : Track.Source.LOCAL);
        t.neteaseId = asLong(m.get("neteaseId"));
        t.customId = asStr(m.get("customId"));
        t.filePath = asStr(m.get("filePath"));
        t.contentUri = asStr(m.get("contentUri"));
        t.streamUrl = asStr(m.get("streamUrl"));
        if (m.get("trial") instanceof Boolean) t.trial = (Boolean) m.get("trial");
        t.title = asStr(m.get("title"));
        t.artist = asStr(m.get("artist"));
        t.album = asStr(m.get("album"));
        t.durationMs = asLong(m.get("durationMs"));
        t.coverUrl = asStr(m.get("coverUrl"));
        t.coverThumbPath = asStr(m.get("coverThumbPath"));
        t.coverLocalPath = asStr(m.get("coverLocalPath"));
        t.lyricFilePath = asStr(m.get("lyricFilePath"));
        t.translationFilePath = asStr(m.get("translationFilePath"));
        t.romajiFilePath = asStr(m.get("romajiFilePath"));
        return t;
    }

    private static long asLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private static String asStr(Object o) {
        return o != null ? o.toString() : null;
    }

    // ==================== 队列管理 ====================

    /** 设置当前播放队列并更新 QML 绑定 */
    private void setQueue(List<Track> list, int startIndex) {
        queueTrackList.clear();
        queueTrackList.addAll(list);
        updateQueueTracks();
        if (startIndex >= 0 && startIndex < queueTrackList.size()) {
            playTrack(startIndex);
        }
    }

    private void updateQueueTracks() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < queueTrackList.size(); i++) {
            Track t = queueTrackList.get(i);
            out.add(trackToMap(t, i == index.get()));
        }
        queueTracks.set(out);
    }

    private Map<String, Object> trackToMap(Track t) {
        return trackToMap(t, false);
    }

    private Map<String, Object> trackToMap(Track t, boolean current) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", t.title != null ? t.title : "");
        m.put("name", t.title != null ? t.title : "");
        m.put("artist", t.artist != null ? t.artist : "");
        m.put("album", t.album != null ? t.album : "");
        m.put("coverThumbPath", t.coverThumbPath != null ? t.coverThumbPath : "");
        m.put("coverUrl", t.coverUrl != null ? t.coverUrl : "");
        m.put("coverLocalPath", t.coverLocalPath != null ? t.coverLocalPath : "");
        m.put("filePath", t.filePath != null ? t.filePath : "");
        m.put("durationMs", t.durationMs);
        m.put("id", t.neteaseId);
        m.put("customId", t.customId != null ? t.customId : "");
        m.put("source", t.source == Track.Source.LOCAL ? "local"
                : (t.source == Track.Source.CUSTOM_API ? "custom" : "netease"));
        m.put("current", current);
        return m;
    }

    public void addToQueue(String path) {
        Track t = new Track();
        t.source = Track.Source.LOCAL;
        t.filePath = path;
        t.title = new java.io.File(path).getName().replaceFirst("\\.[^.]+$", "");
        queueTrackList.add(t);
        updateQueueTracks();
        if (index.get() < 0) playTrack(0);
    }

    public void addFile(String path) {
        addToQueue(path);
        toast.set("已添加到队列: " + new java.io.File(path).getName());
    }

    public void requestExit() {
        saveSessionState();
        if (exitListener != null) exitListener.run();
    }

    public void handleBack() { backTick.set(backTick.get() + 1); }

    // ==================== 本地音乐扫描 ====================

    private void scanLocalLibrary(String folder) {
        if (folder == null || folder.isEmpty()) {
            AdaptClient.LOGGER.warn("[PlayerController] musicFolder is empty, skip scan");
            return;
        }
        async(() -> {
            AdaptClient.LOGGER.info("[PlayerController] scanning local library: {}", folder);
            List<Track> scanned = libraryScanner.scan(folder);
            return () -> {
                localTracks.clear();
                localTracks.addAll(scanned);
                List<Map<String, Object>> out = new ArrayList<>();
                for (Track t : scanned) {
                    out.add(trackToMap(t));
                }
                tracks.set(out);
                libraryCount.set(scanned.size());
                AdaptClient.LOGGER.info("[PlayerController] local scan done: {} tracks", scanned.size());
            };
        });
    }

    /** LocalPage.qml 调用: 播放本地歌曲 */
    public void play(int idx) {
        if (idx < 0 || idx >= localTracks.size()) return;
        setQueue(localTracks, idx);
    }

    // ==================== 设置页 ====================

    public void clearDiskCache() {
        diskCache.clearAll();
        toast.set("缓存已清除");
    }

    public void openExternalUrl(String url) {
        AdaptClient.LOGGER.info("[PlayerController] openExternalUrl: {}", url);
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception e) {
            AdaptClient.LOGGER.warn("[PlayerController] openExternalUrl failed: {}", e.getMessage());
        }
    }

    public void setLogVisible(boolean visible) { logVisible.set(visible); }

    /** 推送当前自定义 API 音源配置（设置页/持久化加载整体替换时调用）；
     *  {@code null} 重置为不可用的默认配置。 */
    public void setCustomApiConfig(CustomApiConfig cfg) {
        customApiConfig = cfg != null ? cfg : new CustomApiConfig();
    }

    public void clearLog() {
        synchronized (logBuf) {
            logBuf.setLength(0);
            logText.set("");
        }
    }

    // ==================== 搜索 ====================

    public void search(String query) {
        AdaptClient.LOGGER.info("[PlayerController] search: {}", query);
        if (query == null || query.trim().isEmpty()) return;
        final String q = query.trim();
        final String key = q.toLowerCase(Locale.ROOT);
        currentSearchKey = key;
        currentSearchQuery = q;
        // 快速路径：缓存命中
        CacheEntry entry = searchCache.get(key);
        if (entry != null && !entry.isExpired()) {
            publishSearchResults(entry.songs, entry.nextOffset, entry.hasMore);
            AdaptClient.LOGGER.info("[PlayerController] search cache hit: {}", key);
            return;
        }
        searchPageInFlight = true;
        searchLoading.set(true);
        searchHasMore.set(false);
        searchWorker.submit(() -> {
            try {
                if (!key.equals(currentSearchKey)) return;
                // 二次检查：可能等待期间已经有缓存了
                CacheEntry existing = searchCache.get(key);
                if (existing != null && !existing.isExpired()) {
                    if (!key.equals(currentSearchKey)) return;
                    post(() -> {
                        if (!key.equals(currentSearchKey)) return;
                        publishSearchResults(existing.songs, existing.nextOffset, existing.hasMore);
                    });
                    return;
                }
                NeteaseClient.SongSearchPage page =
                        netease.searchSongsPage(q, SEARCH_PAGE_SIZE, 0);
                if (!key.equals(currentSearchKey)) return;
                List<NeteaseSong> r = page.songs;
                fillMissingCovers(r);
                int nextOffset = page.consumed;
                boolean hasMore = page.hasMore(0, SEARCH_PAGE_SIZE);
                searchCache.put(key, new CacheEntry(r, nextOffset, hasMore));
                post(() -> {
                    if (!key.equals(currentSearchKey)) return;
                    publishSearchResults(r, nextOffset, hasMore);
                });
            } catch (Exception e) {
                AdaptClient.LOGGER.warn("[PlayerController] search failed: {}", e.getMessage());
                post(() -> {
                    if (!key.equals(currentSearchKey)) return;
                    searchPageInFlight = false;
                    searchLoading.set(false);
                    searchHasMore.set(false);
                    rebuildSearchRows();
                    toast.set("搜索失败: " + e.getMessage());
                });
            }
        });
    }

    private void publishSearchResults(List<NeteaseSong> songs, int nextOffset, boolean hasMore) {
        searchTrackList.clear();
        List<Map<String, Object>> out = new ArrayList<>();
        for (NeteaseSong s : songs) {
            Track t = neteaseSongToTrack(s);
            searchTrackList.add(t);
            out.add(trackToMap(t));
        }
        searchResults.set(out);
        searchNextOffset = nextOffset;
        searchPageInFlight = false;
        searchLoading.set(false);
        searchHasMore.set(hasMore);
        rebuildSearchRows();
        AdaptClient.LOGGER.info("[PlayerController] search results: {}", songs.size());
    }

    public void prepareSearch(String keyword) {
        String query = keyword == null ? "" : keyword.trim();
        currentSearchKey = query.toLowerCase(Locale.ROOT);
        currentSearchQuery = query;
        currentCustomSearchKey = query;
        searchNextOffset = 0;
        searchPageInFlight = false;
        searchResults.set(Collections.emptyList());
        searchTrackList.clear();
        localSearchResultTrackList.clear();
        customSearchResultList.clear();
        searchLoading.set(false);
        searchHasMore.set(false);
        rebuildSearchRows();
    }

    public void loadMoreSearch() {
        if (searchPageInFlight || !Boolean.TRUE.equals(searchHasMore.peek())) return;
        final String query = currentSearchQuery;
        final String key = currentSearchKey;
        final int offset = searchNextOffset;
        if (query.isEmpty() || key.isEmpty()) return;

        final List<Track> baseTracks = new ArrayList<>(searchTrackList);

        searchPageInFlight = true;
        searchLoading.set(true);
        searchWorker.submit(() -> {
            try {
                if (!key.equals(currentSearchKey) || offset != searchNextOffset) return;
                NeteaseClient.SongSearchPage page =
                        netease.searchSongsPage(query, SEARCH_PAGE_SIZE, offset);
                if (!key.equals(currentSearchKey) || offset != searchNextOffset) return;

                List<NeteaseSong> additions = page.songs;
                fillMissingCovers(additions);
                // 合并去重
                Set<Long> existingIds = new HashSet<>();
                for (Track t : baseTracks) existingIds.add(t.neteaseId);
                for (NeteaseSong s : additions) {
                    if (existingIds.contains(s.id)) continue;
                    existingIds.add(s.id);
                    baseTracks.add(neteaseSongToTrack(s));
                }
                int nextOffset = offset + page.consumed;
                boolean hasMore = page.hasMore(offset, SEARCH_PAGE_SIZE) && page.consumed > 0;
                // 重建缓存条目
                List<NeteaseSong> mergedSongs = new ArrayList<>();
                for (Track t : baseTracks) {
                    NeteaseSong s = new NeteaseSong();
                    s.id = t.neteaseId;
                    s.name = t.title;
                    s.artist = t.artist;
                    s.album = t.album;
                    s.coverUrl = t.coverUrl;
                    s.coverThumbPath = t.coverThumbPath;
                    s.durationMs = t.durationMs;
                    mergedSongs.add(s);
                }
                searchCache.put(key, new CacheEntry(mergedSongs, nextOffset, hasMore));
                post(() -> {
                    if (!key.equals(currentSearchKey) || offset != searchNextOffset) return;
                    searchTrackList.clear();
                    searchTrackList.addAll(baseTracks);
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (Track t : baseTracks) out.add(trackToMap(t));
                    searchResults.set(out);
                    searchNextOffset = nextOffset;
                    searchPageInFlight = false;
                    searchLoading.set(false);
                    searchHasMore.set(hasMore);
                    rebuildSearchRows();
                    AdaptClient.LOGGER.info("[PlayerController] loadMoreSearch: total={}", baseTracks.size());
                });
            } catch (Exception e) {
                AdaptClient.LOGGER.warn("[PlayerController] loadMoreSearch failed at offset {}: {}", offset, e.getMessage());
                post(() -> {
                    if (!key.equals(currentSearchKey) || offset != searchNextOffset) return;
                    searchPageInFlight = false;
                    searchLoading.set(false);
                });
            }
        });
    }

    public void searchLocal(String keyword) {
        String q = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            localSearchResultTrackList.clear();
            rebuildSearchRows();
            return;
        }
        localSearchResultTrackList.clear();
        for (Track t : localTracks) {
            if (containsIgnoreCase(t.title, q) || containsIgnoreCase(t.artist, q)
                    || containsIgnoreCase(t.album, q)) {
                localSearchResultTrackList.add(t);
            }
        }
        rebuildSearchRows();
    }

    /** 搜索用户配置的自定义 API 音源并发布到 {#customSearchResultList}。
     *  独立于 {@link #search(String)}：无缓存层（自托管代理通常低延迟），
     *  音源未配置/未启用时静默发布空列表。 */
    public void searchCustom(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            customSearchResultList.clear();
            rebuildSearchRows();
            return;
        }
        final CustomApiConfig cfg = customApiConfig;
        if (!cfg.isUsable()) {
            customSearchResultList.clear();
            rebuildSearchRows();
            return;
        }
        final String key = keyword.trim();
        currentCustomSearchKey = key;
        customSearchWorker.submit(() -> {
            try {
                List<CustomSong> r = CustomApiClient.search(cfg, key);
                if (!key.equals(currentCustomSearchKey)) return;
                post(() -> {
                    if (key.equals(currentCustomSearchKey)) {
                        customSearchResultList.clear();
                        customSearchResultList.addAll(r);
                        rebuildSearchRows();
                    }
                });
            } catch (Throwable e) {
                AdaptClient.LOGGER.warn("[PlayerController] custom search failed: {}", e.getMessage());
            }
        });
    }

    private static boolean containsIgnoreCase(String s, String needleLower) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    void rebuildSearchRows() {
        List<SearchRow> rows = new ArrayList<>();
        // 网易云结果
        for (int i = 0; i < searchTrackList.size(); i++) {
            Track t = searchTrackList.get(i);
            SearchRow row = new SearchRow();
            row.kind = "netease";
            row.kindLabel = "网易云";
            row.index = i;
            row.name = t.title;
            row.artist = t.artist;
            row.album = t.album;
            row.coverThumbPath = t.coverThumbPath;
            row.coverUrl = t.coverUrl;
            row.id = t.neteaseId;
            row.durationMs = t.durationMs;
            row.menuEnabled = t.neteaseId != 0;
            rows.add(row);
        }
        // 本地结果
        for (int i = 0; i < localSearchResultTrackList.size(); i++) {
            Track t = localSearchResultTrackList.get(i);
            SearchRow row = new SearchRow();
            row.kind = "local";
            row.kindLabel = "本地";
            row.index = i;
            row.name = t.title;
            row.artist = t.artist;
            row.album = t.album;
            row.coverThumbPath = t.coverThumbPath;
            row.coverUrl = t.coverUrl;
            row.filePath = t.filePath;
            row.durationMs = t.durationMs;
            row.menuEnabled = t.filePath != null && !t.filePath.isEmpty();
            rows.add(row);
        }
        // 自定义 API 结果（searchCustom 发布，位于网易云/本地之后）
        for (int i = 0; i < customSearchResultList.size(); i++) {
            CustomSong s = customSearchResultList.get(i);
            SearchRow row = new SearchRow();
            row.kind = "custom";
            row.kindLabel = "自定义源";
            row.index = i;
            row.name = s.name;
            row.artist = s.artist;
            row.album = s.album;
            row.coverThumbPath = s.coverThumbPath;
            row.coverUrl = s.coverUrl;
            row.customId = s.id;
            row.durationMs = s.durationMs;
            row.menuEnabled = s.id != null && !s.id.isEmpty();
            rows.add(row);
        }
        searchRows.set(rows);
    }

    public void playSearchRow(int rowIndex) {
        List<SearchRow> rows = searchRows.peek();
        if (rows == null || rowIndex < 0 || rowIndex >= rows.size()) return;
        SearchRow row = rows.get(rowIndex);
        switch (row.kind) {
            case "netease": playSearchResult(row.index); break;
            case "local": playLocalSearchResult(row.index); break;
            case "custom": playCustomSearchResult(row.index); break;
            default: break;
        }
    }

    public void playLocalSearchResult(int i) {
        if (i < 0 || i >= localSearchResultTrackList.size()) return;
        setQueue(localSearchResultTrackList, i);
    }

    private void fillMissingCovers(List<NeteaseSong> songs) {
        if (songs == null || songs.isEmpty()) return;
        List<Long> missingIds = new ArrayList<>();
        for (NeteaseSong s : songs) {
            if (s.coverUrl == null || s.coverUrl.isEmpty()) missingIds.add(s.id);
        }
        if (missingIds.isEmpty()) return;
        try {
            List<NeteaseSong> details = netease.songDetails(missingIds);
            Map<Long, NeteaseSong> detailMap = new HashMap<>();
            for (NeteaseSong d : details) detailMap.put(d.id, d);
            for (NeteaseSong s : songs) {
                if ((s.coverUrl == null || s.coverUrl.isEmpty())) {
                    NeteaseSong d = detailMap.get(s.id);
                    if (d != null && d.coverUrl != null && !d.coverUrl.isEmpty()) {
                        s.coverUrl = d.coverUrl;
                        s.coverThumbPath = d.coverThumbPath;
                    }
                }
            }
        } catch (Exception e) {
            AdaptClient.LOGGER.warn("[PlayerController] fillMissingCovers failed: {}", e.getMessage());
        }
    }

    public void addSearchHistory(String q) {
        if (q == null || q.isEmpty()) return;
        List<String> hist = new ArrayList<>(searchHistory.get());
        hist.remove(q);
        hist.add(0, q);
        while (hist.size() > 20) hist.remove(hist.size() - 1);
        searchHistory.set(hist);
        saveSearchHistory();
    }

    public void removeSearchHistory(int index) {
        List<String> hist = new ArrayList<>(searchHistory.get());
        if (index >= 0 && index < hist.size()) hist.remove(index);
        searchHistory.set(hist);
        saveSearchHistory();
    }

    public void clearSearchHistory() {
        searchHistory.set(Collections.emptyList());
        saveSearchHistory();
    }

    public void loadHotSearches() {
        async(() -> {
            try {
                List<String> hot = netease.searchHot();
                return () -> {
                    if (hot.isEmpty()) {
                        List<String> defaults = new ArrayList<>();
                        defaults.add("周杰伦");
                        defaults.add("薛之谦");
                        defaults.add("陈奕迅");
                        defaults.add("林俊杰");
                        defaults.add("邓紫棋");
                        hotSearches.set(defaults);
                    } else {
                        hotSearches.set(hot);
                    }
                };
            } catch (Exception e) {
                return () -> {
                    List<String> defaults = new ArrayList<>();
                    defaults.add("周杰伦");
                    defaults.add("薛之谦");
                    defaults.add("陈奕迅");
                    defaults.add("林俊杰");
                    defaults.add("邓紫棋");
                    hotSearches.set(defaults);
                };
            }
        });
    }

    public void playSearchResult(int idx) {
        if (idx < 0 || idx >= searchTrackList.size()) return;
        setQueue(searchTrackList, idx);
    }

    /** 以自定义 API 搜索结果建立播放队列并从 {@code i} 开始播放 ——
     *  {@link #playSearchResult(int)} 的 CUSTOM_API 对应物。 */
    public void playCustomSearchResult(int i) {
        if (i < 0 || i >= customSearchResultList.size()) return;
        List<Track> q = new ArrayList<>(customSearchResultList.size());
        for (CustomSong s : customSearchResultList) q.add(toTrackCustom(s));
        setQueue(q, i);
    }

    // ==================== 缓存管理 ====================

    public void cacheSong(long songId) {
        if (songId == 0) { toast.set("无法缓存"); return; }
        if (diskCache.hasAudio(songId)) { toast.set("这首歌已缓存"); return; }
        Track t = findTrackById(songId);
        if (t != null && t.streamUrl != null && !t.trial) {
            cacheSongAsync(t, () -> post(() -> toast.set(diskCache.hasAudio(songId) ? "缓存完成" : "缓存失败")));
            toast.set("已开始缓存");
            return;
        }
        final String title = t != null ? t.title : "";
        final String artist = t != null ? t.artist : "";
        final String album = t != null ? t.album : "";
        final String cover = t != null ? t.coverUrl : "";
        final long duration = t != null ? t.durationMs : 0;
        async(() -> {
            try {
                NeteaseClient.UrlInfo info = netease.songUrlInfo(songId, playLevel());
                String url = (info != null && !info.trial) ? info.url : null;
                if (url == null && settings.unblockEnabled.get()) {
                    url = SongUnblocker.resolve(songId, title, artist);
                }
                if (url == null) {
                    post(() -> toast.set("无法缓存：仅可试听"));
                    return null;
                }
                Track c = new Track();
                c.source = Track.Source.NETEASE;
                c.neteaseId = songId;
                c.title = title;
                c.artist = artist;
                c.album = album;
                c.coverUrl = cover;
                c.durationMs = duration;
                c.streamUrl = url;
                cacheSongAsync(c, () -> post(() -> toast.set(diskCache.hasAudio(songId) ? "缓存完成" : "缓存失败")));
                post(() -> toast.set("已开始缓存"));
                return null;
            } catch (Throwable e) {
                AdaptClient.LOGGER.warn("[PlayerController] cache song {} failed: {}", songId, e.getMessage());
                post(() -> toast.set("缓存失败"));
                return null;
            }
        });
    }

    public void deleteCachedSong(long songId) {
        if (songId == 0) return;
        boolean ok = diskCache.deleteAudio(songId);
        if (ok) {
            for (int i = 0; i < cachedSongTracks.size(); i++) {
                if (cachedSongTracks.get(i).neteaseId == songId) {
                    cachedSongTracks.remove(i);
                    break;
                }
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Track t : cachedSongTracks) out.add(trackToMap(t));
            cachedSongs.set(out);
            toast.set("已删除缓存");
        } else {
            toast.set("删除失败");
        }
    }

    public void refreshCachedSongs() {
        try {
            java.util.Set<Long> ids = new java.util.HashSet<>();
            java.io.File dir = new java.io.File(diskCache.baseDir(), DiskCache.AUDIO);
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    String n = f.getName();
                    if (n.endsWith(".cache")) {
                        try {
                            ids.add(Long.parseLong(n.substring(0, n.length() - ".cache".length()).replace("-u", "")));
                        } catch (NumberFormatException ignore) {}
                    }
                }
            }
            List<Track> out = new ArrayList<>();
            for (NeteaseSong s : songMetaIndex.all()) {
                if (ids.contains(s.id)) {
                    Track t = neteaseSongToTrack(s);
                    String localThumb = diskCache.getThumb64(thumbUrl(s.coverUrl, "512"));
                    if (localThumb == null) localThumb = diskCache.getThumb64(thumbUrl(s.coverUrl, "64"));
                    if (localThumb != null) t.coverThumbPath = localThumb;
                    out.add(t);
                }
            }
            cachedSongTracks.clear();
            cachedSongTracks.addAll(out);
            List<Map<String, Object>> mapOut = new ArrayList<>();
            for (Track t : out) mapOut.add(trackToMap(t));
            cachedSongs.set(mapOut);
        } catch (Throwable e) {
            AdaptClient.LOGGER.warn("[PlayerController] refreshCachedSongs failed: {}", e.getMessage());
        }
    }

    public void playCachedSong(int i) {
        if (i < 0 || i >= cachedSongTracks.size()) return;
        setQueue(cachedSongTracks, i);
    }

    private void cacheAudioAsync(Track t, Runnable onDone) {
        if (t == null || t.trial || t.neteaseId == 0 || t.streamUrl == null) return;
        final String url = t.streamUrl;
        final long nid = t.neteaseId;
        songMetaIndex.upsert(nid, t.title, t.artist, t.album, t.coverUrl, t.durationMs);
        cacheWorker.submit(() -> {
            diskCache.cacheAudio(url, nid);
            songMetaIndex.save();
            if (onDone != null) post(onDone);
        });
        cacheThumb64Async(t.coverUrl);
    }

    private void cacheSongAsync(Track t, Runnable onDone) {
        if (t == null || t.trial || t.neteaseId == 0 || t.streamUrl == null) return;
        final String url = t.streamUrl;
        final long nid = t.neteaseId;
        final String cover = t.coverUrl;
        songMetaIndex.upsert(nid, t.title, t.artist, t.album, cover, t.durationMs);
        cacheWorker.submit(() -> {
            diskCache.cacheAudio(url, nid);
            if (cover != null && !cover.isEmpty()) {
                diskCache.cacheImage(thumbUrl(cover, "1024"));
            }
            try { netease.lyric(nid); } catch (Exception ignored) {}
            songMetaIndex.save();
            if (onDone != null) post(onDone);
        });
        cacheThumb64Async(cover);
    }

    private void cacheThumb64Async(String coverUrl) {
        if (coverUrl == null || coverUrl.isEmpty()) return;
        String thumb64 = thumbUrl(coverUrl, "64");
        if (diskCache.hasThumb64(thumb64)) return;
        cacheWorker.submit(() -> diskCache.cacheThumb64(thumb64));
    }

    private static String thumbUrl(String url, String size) {
        if (url == null || url.isEmpty()) return "";
        return url.contains("?") ? url + "&param=" + size + "y" + size
                                 : url + "?param=" + size + "y" + size;
    }

    // ==================== 主页/推荐 ====================

    private void loadRecommendations() {
        async(() -> {
            try {
                List<NeteasePlaylist> pls = netease.personalizedPlaylists(12);
                return () -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (NeteasePlaylist p : pls) {
                        out.add(playlistToMap(p));
                    }
                    recommendPlaylists.set(out);
                    AdaptClient.LOGGER.info("[PlayerController] recommend playlists: {}", pls.size());
                };
            } catch (Exception e) {
                AdaptClient.LOGGER.warn("[PlayerController] loadRecommendations failed: {}", e.getMessage());
                return null;
            }
        });

        // 推荐歌曲需要登录
        if (netease.isLoggedIn()) {
            async(() -> {
                try {
                    List<NeteaseSong> songs = netease.recommendSongs();
                    return () -> {
                        recommendTrackList.clear();
                        List<Map<String, Object>> out = new ArrayList<>();
                        for (NeteaseSong s : songs) {
                            Track t = neteaseSongToTrack(s);
                            recommendTrackList.add(t);
                            out.add(trackToMap(t));
                        }
                        recommendations.set(out);
                    };
                } catch (Exception e) {
                    return null;
                }
            });
        }
    }

    public void playRecommendation(int idx) {
        if (idx < 0 || idx >= recommendTrackList.size()) return;
        setQueue(recommendTrackList, idx);
    }

    // ==================== 歌单 ====================

    public void loadMyPlaylists() {
        if (!netease.isLoggedIn()) {
            myPlaylists.set(Collections.emptyList());
            return;
        }
        async(() -> {
            try {
                long uid = neteaseUid > 0 ? neteaseUid : netease.loginUid();
                if (uid == 0) return null;
                neteaseUid = uid;
                List<NeteasePlaylist> pls = netease.userPlaylists(uid, 100);
                return () -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (NeteasePlaylist p : pls) {
                        out.add(playlistToMap(p));
                    }
                    myPlaylists.set(out);
                    playlistCount.set(pls.size());
                };
            } catch (Exception e) {
                AdaptClient.LOGGER.warn("[PlayerController] loadMyPlaylists failed: {}", e.getMessage());
                return null;
            }
        });
    }

    public void openPlaylist(Object id) {
        long pid = 0;
        if (id instanceof Number) pid = ((Number) id).longValue();
        else if (id instanceof String) {
            try { pid = Long.parseLong((String) id); } catch (Exception ignored) {}
        }
        if (pid <= 0) return;
        final long fpid = pid;
        openPlaylistId.set(fpid);
        playlistLoading.set(true);
        async(() -> {
            try {
                NeteasePlaylist pl = netease.playlistDetail(fpid);
                List<NeteaseSong> songs = netease.playlistTracks(fpid, 500);
                return () -> {
                    playlistTrackList.clear();
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (NeteaseSong s : songs) {
                        Track t = neteaseSongToTrack(s);
                        playlistTrackList.add(t);
                        out.add(trackToMap(t));
                    }
                    playlistTracks.set(out);
                    if (pl != null) {
                        playlistTitle.set(pl.name != null ? pl.name : "");
                        playlistOwned.set(pl.creatorUid == neteaseUid);
                        playlistSubscribed.set(pl.subscribed);
                    }
                    playlistLoading.set(false);
                };
            } catch (Exception e) {
                AdaptClient.LOGGER.warn("[PlayerController] openPlaylist failed: {}", e.getMessage());
                return () -> {
                    playlistLoading.set(false);
                    toast.set("加载歌单失败: " + e.getMessage());
                };
            }
        });
    }

    public void playPlaylistTrack(int idx) {
        if (idx < 0 || idx >= playlistTrackList.size()) return;
        setQueue(playlistTrackList, idx);
    }

    /** 歌单卡片右键菜单"立即播放"：拉取歌单歌曲并直接替换队列（不打开详情页） */
    public void playPlaylist(long playlistId) {
        if (playlistId <= 0) return;
        async(() -> {
            try {
                List<NeteaseSong> songs = netease.playlistTracks(playlistId, 500);
                fillMissingCovers(songs);
                final List<Track> tracks = new ArrayList<>();
                for (NeteaseSong s : songs) tracks.add(neteaseSongToTrack(s));
                return () -> {
                    if (tracks.isEmpty()) {
                        toast.set("歌单中暂无歌曲");
                        return;
                    }
                    setQueue(tracks, 0);
                    toast.set("开始播放歌单");
                };
            } catch (Exception e) {
                AdaptClient.LOGGER.warn("[PlayerController] playPlaylist failed: {}", e.getMessage());
                return () -> toast.set("播放歌单失败: " + e.getMessage());
            }
        });
    }

    /** 心动推荐：以歌单当前播放曲（否则首曲）为种子，把队列替换为网易云智能推荐 */
    public void startIntelligenceMode(long playlistId) {
        if (!loggedIn.get()) {
            toast.set("请先登录后使用心动推荐");
            return;
        }
        if (playlistId <= 0 || intelligenceLoading.get()) return;
        // 种子曲：若当前队列正播放的恰好是本歌单的歌曲则用它，否则用歌单第一首。
        long seedId = 0L;
        Track current = currentTrack();
        if (current != null && current.source == Track.Source.NETEASE) {
            for (Track t : playlistTrackList) {
                if (t.neteaseId == current.neteaseId) { seedId = current.neteaseId; break; }
            }
        }
        if (seedId == 0L && !playlistTrackList.isEmpty()) seedId = playlistTrackList.get(0).neteaseId;
        if (seedId == 0L) {
            toast.set("歌单中暂无可推荐歌曲");
            return;
        }
        final long seed = seedId;
        intelligenceLoading.set(true);
        async(() -> {
            try {
                // 网易云目前只接受账号默认"我喜欢的音乐"作为智能推荐的上下文歌单，
                // 种子曲可以来自任意普通歌单（官方客户端同样如此）；直接传用户自建
                // 或收藏的歌单会被接口以"不支持该歌单类型"拒绝。
                long intelligencePid = 0L;
                long uid = neteaseUid > 0 ? neteaseUid : netease.loginUid();
                if (uid != 0L) {
                    List<NeteasePlaylist> playlists = netease.userPlaylists(uid, 1);
                    if (!playlists.isEmpty()) intelligencePid = playlists.get(0).id;
                }
                if (intelligencePid == 0L) throw new java.io.IOException("无法获取我喜欢的音乐歌单");
                List<NeteaseSong> songs = netease.intelligenceSongs(seed, intelligencePid, seed);
                fillMissingCovers(songs);
                final List<Track> tracks = new ArrayList<>();
                for (NeteaseSong s : songs) tracks.add(neteaseSongToTrack(s));
                return () -> {
                    intelligenceLoading.set(false);
                    if (tracks.isEmpty()) {
                        toast.set("暂时没有心动推荐");
                        return;
                    }
                    setQueue(tracks, 0);
                    toast.set("已开启心动推荐");
                };
            } catch (Throwable e) {
                AdaptClient.LOGGER.warn("[PlayerController] startIntelligenceMode failed: {}", e.getMessage());
                return () -> {
                    intelligenceLoading.set(false);
                    toast.set("获取心动推荐失败: " + e.getMessage());
                };
            }
        });
    }

    public void deletePlaylist(long pid) {
        if (pid <= 0) return;
        async(() -> {
            try {
                netease.deletePlaylist(pid);
                return () -> {
                    toast.set("歌单已删除");
                    loadMyPlaylists();
                };
            } catch (Exception e) {
                return () -> toast.set("删除失败: " + e.getMessage());
            }
        });
    }

    public void createPlaylist(String name) {
        if (name == null || name.trim().isEmpty()) return;
        async(() -> {
            try {
                long pid = netease.createPlaylist(name.trim(), false);
                return () -> {
                    if (pid > 0) {
                        toast.set("歌单已创建");
                        loadMyPlaylists();
                    } else {
                        toast.set("创建失败");
                    }
                };
            } catch (Exception e) {
                return () -> toast.set("创建失败: " + e.getMessage());
            }
        });
    }

    public void togglePlaylistSubscribe() {
        long pid = openPlaylistId.get();
        if (pid <= 0) return;
        boolean sub = !playlistSubscribed.get();
        async(() -> {
            try {
                netease.playlistSubscribe(pid, sub);
                return () -> {
                    playlistSubscribed.set(sub);
                    toast.set(sub ? "已收藏歌单" : "已取消收藏歌单");
                };
            } catch (Exception e) {
                return () -> toast.set("操作失败: " + e.getMessage());
            }
        });
    }

    public void pickPlaylistCover(long pid) {
        // 桌面端无原生文件选择器 — QML 会弹出路径输入对话框
    }

    public void setPlaylistCover(long pid, String path) {
        if (pid <= 0 || path == null || path.isEmpty()) return;
        async(() -> {
            try {
                byte[] data = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
                long docId = netease.uploadImage(data, "cover.jpg");
                if (docId > 0) netease.updatePlaylistCover(pid, docId);
                return () -> toast.set("封面已更新");
            } catch (Exception e) {
                return () -> toast.set("封面更新失败: " + e.getMessage());
            }
        });
    }

    // ==================== 最近播放 ====================

    public void loadRecent() {
        if (!netease.isLoggedIn()) {
            recentSongs.set(Collections.emptyList());
            return;
        }
        async(() -> {
            try {
                long uid = neteaseUid > 0 ? neteaseUid : netease.loginUid();
                if (uid == 0) {
                    AdaptClient.LOGGER.warn("[PlayerController] loadRecent: uid is 0");
                    return null;
                }
                neteaseUid = uid;
                // 先试周榜，空则回退到总榜
                List<NeteaseSong> songList = netease.userRecord(uid, 1);
                if (songList.isEmpty()) {
                    AdaptClient.LOGGER.info("[PlayerController] loadRecent: weekData empty, fallback to allData");
                    songList = netease.userRecord(uid, 0);
                }
                AdaptClient.LOGGER.info("[PlayerController] loadRecent: got {} songs", songList.size());
                final List<NeteaseSong> songs = songList;
                return () -> {
                    recentTrackList.clear();
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (NeteaseSong s : songs) {
                        Track t = neteaseSongToTrack(s);
                        recentTrackList.add(t);
                        out.add(trackToMap(t));
                    }
                    recentSongs.set(out);
                };
            } catch (Exception e) {
                AdaptClient.LOGGER.error("[PlayerController] loadRecent failed", e);
                return () -> toast.set("加载最近播放失败: " + e.getMessage());
            }
        });
    }

    public void playRecentSong(int idx) {
        if (idx < 0 || idx >= recentTrackList.size()) return;
        setQueue(recentTrackList, idx);
    }

    // ==================== 队列操作 ====================

    public void playQueueIndex(int idx) {
        if (idx < 0 || idx >= queueTrackList.size()) return;
        playTrack(idx);
    }

    public void removeFromQueue(int idx) {
        if (idx < 0 || idx >= queueTrackList.size()) return;
        int cur = index.get();
        queueTrackList.remove(idx);
        if (idx < cur) {
            index.set(cur - 1);
        } else if (idx == cur) {
            index.set(-1);
        }
        updateQueueTracks();
    }

    public void playCustomPlaylistIndex(int idx) {
        if (idx < 0 || idx >= customPlaylistTrackList.size()) return;
        setQueue(customPlaylistTrackList, idx);
    }

    public void removeFromCustomPlaylistIndex(int idx) {
        if (idx < 0 || idx >= customPlaylistTrackList.size()) return;
        Track t = customPlaylistTrackList.remove(idx);
        if (t != null) customPlaylistIds.remove(t.neteaseId);
        if (index.get() >= customPlaylistTrackList.size()) index.set(-1);
        updateCustomPlaylistTracks();
        saveCustomPlaylist();
    }

    // ==================== 喜欢/收藏 ====================

    public void toggleLike() {
        if (index.get() < 0 || index.get() >= queueTrackList.size()) return;
        Track t = queueTrackList.get(index.get());
        if (t.source != Track.Source.NETEASE || t.neteaseId <= 0) {
            toast.set("本地歌曲不支持收藏");
            return;
        }
        boolean newLike = !currentLiked.get();
        currentLiked.set(newLike);
        // 本地集合先行（刷新红心计数），失败回滚
        boolean added = newLike ? likedIds.add(t.neteaseId) : likedIds.remove(t.neteaseId);
        if (added) likedCount.set(likedIds.size());
        async(() -> {
            try {
                netease.like(t.neteaseId, newLike);
                return () -> toast.set(newLike ? "已喜欢" : "已取消喜欢");
            } catch (Exception e) {
                return () -> {
                    currentLiked.set(!newLike); // revert
                    if (newLike ? likedIds.remove(t.neteaseId) : likedIds.add(t.neteaseId)) {
                        likedCount.set(likedIds.size());
                    }
                    toast.set("操作失败: " + e.getMessage());
                };
            }
        });
    }

    // ==================== 登录 ====================

    public void startQrLogin() {
        AdaptClient.LOGGER.info("[PlayerController] startQrLogin called");
        qrStatus.set(0);
        qrImage.set(null);
        async(() -> {
            try {
                String key = netease.qrLoginKey();
                AdaptClient.LOGGER.info("[PlayerController] qrLoginKey={}", key);
                boolean[][] matrix = netease.qrMatrix(key);
                AdaptClient.LOGGER.info("[PlayerController] qrMatrix result: {}",
                        matrix != null ? matrix.length + "x" + (matrix.length > 0 ? matrix[0].length : 0) : "null");
                // 转成 List<List<Boolean>> — qml4j 支持 List.size() 但不支持数组 .length
                List<List<Boolean>> list = null;
                if (matrix != null) {
                    list = new ArrayList<>(matrix.length);
                    for (boolean[] row : matrix) {
                        List<Boolean> listRow = new ArrayList<>(row.length);
                        for (boolean b : row) listRow.add(b);
                        list.add(listRow);
                    }
                }
                final List<List<Boolean>> finalList = list;
                return () -> {
                    qrKey.set(key);
                    qrImage.set(finalList);
                    qrStatus.set(801); // 等待扫码
                    AdaptClient.LOGGER.info("[PlayerController] qrImage set, rows={}",
                            finalList != null ? finalList.size() : 0);
                };
            } catch (Exception e) {
                AdaptClient.LOGGER.error("[PlayerController] startQrLogin failed", e);
                return () -> {
                    qrStatus.set(800);
                    toast.set("获取二维码失败: " + e.getMessage());
                };
            }
        });
    }

    public void pollQrLogin() {
        String key = qrKey.get();
        if (key == null || key.isEmpty()) return;
        int st = qrStatus.get();
        if (st == 803 || st == 800) return; // 已成功或已过期
        async(() -> {
            try {
                int code = netease.qrLoginCheck(key);
                AdaptClient.LOGGER.info("[PlayerController] qrLoginCheck code={}", code);
                return () -> {
                    qrStatus.set(code);
                    if (code == 803) {
                        toast.set("登录成功");
                        onLoginSuccess();
                    } else if (code == 800) {
                        // 二维码过期，自动刷新
                        post(() -> startQrLogin());
                    }
                };
            } catch (Exception e) {
                AdaptClient.LOGGER.warn("[PlayerController] pollQrLogin failed: {}", e.getMessage());
                return null;
            }
        });
    }

    private void onLoginSuccess() {
        loggedIn.set(true);
        async(() -> {
            try {
                long uid = netease.loginUid();
                if (uid == 0) return null;
                neteaseUid = uid;
                NeteaseUser user = netease.userDetail(uid);
                return () -> {
                    if (user != null) {
                        userName.set(user.nickname != null ? user.nickname : "");
                        userAvatar.set(user.avatarUrl != null ? user.avatarUrl : "");
                        userVipType.set(user.vipType);
                        userLevel.set(user.level);
                        userSignature.set(user.signature != null ? user.signature : "");
                    }
                    loadMyPlaylists();
                    loadRecommendations();
                    loadRecent();
                    loadLikedSongs();
                };
            } catch (Exception e) {
                return null;
            }
        });
    }

    public void logout() {
        async(() -> {
            netease.logout();
            return () -> {
                loggedIn.set(false);
                userName.set("");
                userAvatar.set("");
                neteaseUid = 0;
                likedIds.clear();
                likedCount.set(0);
                currentLiked.set(false);
                myPlaylists.set(Collections.emptyList());
                recommendations.set(Collections.emptyList());
                recentSongs.set(Collections.emptyList());
                toast.set("已退出登录");
            };
        });
    }

    public void startUpdateDownload() {
        // 桌面模组不做 APK 下载：直接打开 GitHub releases 页（镜像开关生效）
        String url = "https://github.com/TIMER-err/qplayer/releases";
        if (Boolean.TRUE.equals(settings.mirrorEnabled.get())) {
            url = "https://mirror.ghproxy.com/" + url;
        }
        openExternalUrl(url);
        appendLog("[update] 打开更新页面: " + url);
    }

    // ==================== 上下文菜单 ====================

    public boolean isInCustomPlaylist(long songId) {
        return customPlaylistIds.contains(songId);
    }

    public void addToPlaylist(Object pid, long songId) {
        long plid = 0;
        if (pid instanceof Number) plid = ((Number) pid).longValue();
        final long fplid = plid;
        async(() -> {
            try {
                netease.manipulatePlaylistTracks(fplid, songId, true);
                return () -> toast.set("已添加到歌单");
            } catch (Exception e) {
                return () -> toast.set("添加失败: " + e.getMessage());
            }
        });
    }

    public void removeFromCurrentPlaylist(long songId) {
        long pid = openPlaylistId.get();
        if (pid <= 0) return;
        async(() -> {
            try {
                netease.manipulatePlaylistTracks(pid, songId, false);
                return () -> {
                    toast.set("已从歌单移除");
                    openPlaylist(pid);
                };
            } catch (Exception e) {
                return () -> toast.set("移除失败: " + e.getMessage());
            }
        });
    }

    public void addToCustomPlaylist(long songId) {
        if (customPlaylistIds.contains(songId)) return;
        Track t = findTrackById(songId);
        if (t == null) {
            toast.set("无法加入播放列表: 找不到歌曲信息");
            return;
        }
        customPlaylistIds.add(songId);
        customPlaylistTrackList.add(t);
        updateCustomPlaylistTracks();
        saveCustomPlaylist();
        toast.set("已加入播放列表");
    }

    public void removeFromCustomPlaylist(long songId) {
        customPlaylistIds.remove(songId);
        customPlaylistTrackList.removeIf(x -> x.neteaseId == songId);
        updateCustomPlaylistTracks();
        saveCustomPlaylist();
    }

    /** 自定义 API 条目是否已在本地播放列表中（按其音源稳定的 customId 判断）。 */
    public boolean isCustomApiInCustomPlaylist(String customId) {
        if (customId == null || customId.isEmpty()) return false;
        for (Track t : customPlaylistTrackList) {
            if (t.source == Track.Source.CUSTOM_API && customId.equals(t.customId)) return true;
        }
        return false;
    }

    /** 把自定义 API 搜索结果加入本地播放列表（按 customId 去重）。 */
    public void addCustomApiToCustomPlaylist(String customId) {
        if (customId == null || customId.isEmpty() || isCustomApiInCustomPlaylist(customId)) return;
        CustomSong found = null;
        for (CustomSong s : customSearchResultList) {
            if (customId.equals(s.id)) {
                found = s;
                break;
            }
        }
        if (found == null) {
            toast.set("添加失败");
            return;
        }
        customPlaylistTrackList.add(toTrackCustom(found));
        updateCustomPlaylistTracks();
        saveCustomPlaylist();
        toast.set("已加入播放列表");
    }

    /** 从本地播放列表移除自定义 API 条目（按 customId）。 */
    public void removeCustomApiFromCustomPlaylist(String customId) {
        if (customId == null || customId.isEmpty()) return;
        boolean removed = customPlaylistTrackList.removeIf(
                t -> t.source == Track.Source.CUSTOM_API && customId.equals(t.customId));
        if (removed) {
            updateCustomPlaylistTracks();
            saveCustomPlaylist();
        }
    }

    /** 从所有已知曲目列表中按网易云 id 查找 Track（队列/搜索/歌单/推荐/最近/本地） */
    private Track findTrackById(long songId) {
        @SuppressWarnings("unchecked")
        List<Track>[] pools = new List[]{
                queueTrackList, searchTrackList, playlistTrackList,
                recommendTrackList, recentTrackList, localTracks};
        for (List<Track> list : pools) {
            for (Track t : list) {
                if (t.neteaseId == songId) return t;
            }
        }
        return null;
    }

    private void updateCustomPlaylistTracks() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Track t : customPlaylistTrackList) {
            out.add(trackToMap(t));
        }
        customPlaylistTracks.set(out);
    }

    public void copySongLink(long songId) {
        try {
            String url = "https://music.163.com/song?id=" + songId;
            java.awt.Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(url), null);
            toast.set("已复制链接");
        } catch (Exception e) {
            toast.set("复制失败");
        }
    }

    /** 复制歌单分享链接。Minecraft 主线程（-XstartOnFirstThread）下 AWT 不可用，
     *  用 GLFW 剪贴板（和复制一起听邀请链接同一机制）。 */
    public void copyPlaylistLink(long playlistId) {
        if (playlistId <= 0) return;
        try {
            String url = "https://music.163.com/playlist?id=" + playlistId;
            long h = net.minecraft.client.Minecraft.getInstance().getWindow().handle();
            org.lwjgl.glfw.GLFW.glfwSetClipboardString(h, url);
            toast.set("已复制链接");
        } catch (Exception e) {
            AdaptClient.LOGGER.warn("[PlayerController] copyPlaylistLink failed: {}", e.getMessage());
            toast.set("复制失败");
        }
    }

    // ==================== 一起听 ====================

    public void createListenTogetherRoom() {
        if (listenTogetherBusy.get()) return;
        listenTogetherBusy.set(true);
        Track cur = currentTrack();
        final long songId = cur != null && cur.neteaseId > 0 ? cur.neteaseId : 0L;
        if (songId == 0) {
            listenTogetherBusy.set(false);
            toast.set("请先播放一首网易云歌曲");
            return;
        }
        async(() -> {
            try {
                TogetherRoom room = netease.createTogetherRoom();
                final String fRoomId = room.roomId;
                // inviterId 在 worker 线程解析（neteaseUid 可能尚未从登录态回填，联网兜底），
                // 避免生成 inviterId=0 的无效邀请链接（对方加入会提示"邀请链接无效"）。
                long uid = neteaseUid > 0 ? neteaseUid : netease.loginUid();
                final String inv = "https://music.163.com/together/?roomId=" + fRoomId + "&inviterId=" + uid;
                final long fUid = uid;
                return () -> {
                    togetherRoomId = fRoomId;
                    togetherInviterId = fUid;
                    listenTogetherRoomId.set(fRoomId);
                    listenTogetherInRoom.set(true);
                    listenTogetherBusy.set(false);
                    listenTogetherStatusText.set("房间已创建，等待好友加入…");
                    listenTogetherMembers.set("只有你一个人在房间");
                    listenTogetherInvitation.set(inv);
                    toast.set("一起听房间已创建");
                };
            } catch (Exception e) {
                AdaptClient.LOGGER.warn("[PlayerController] createListenTogetherRoom failed: {}", e.getMessage());
                return () -> {
                    listenTogetherBusy.set(false);
                    toast.set("创建房间失败: " + e.getMessage());
                };
            }
        });
    }

    public void joinListenTogether(String invitation) {
        if (invitation == null || invitation.trim().isEmpty()) return;
        if (listenTogetherBusy.get()) return;
        listenTogetherBusy.set(true);
        // Parse invitation URL: https://music.163.com/together/?roomId=xxx&inviterId=yyy
        String roomId = "";
        long inviterId = 0L;
        try {
            String q = invitation;
            int qi = q.indexOf("roomId=");
            if (qi >= 0) {
                int end = q.indexOf('&', qi);
                roomId = end > qi ? q.substring(qi + 7, end) : q.substring(qi + 7);
            }
            int ii = q.indexOf("inviterId=");
            if (ii >= 0) {
                int end = q.indexOf('&', ii);
                String idStr = end > ii ? q.substring(ii + 10, end) : q.substring(ii + 10);
                inviterId = Long.parseLong(idStr);
            }
        } catch (Exception e) {
            AdaptClient.LOGGER.warn("[PlayerController] parse invitation failed: {}", e.getMessage());
        }
        if (roomId.isEmpty() || inviterId == 0L) {
            listenTogetherBusy.set(false);
            toast.set("邀请链接无效");
            return;
        }
        final String fRoomId = roomId;
        final long fInviterId = inviterId;
        async(() -> {
            try {
                netease.acceptTogetherInvitation(fRoomId, fInviterId);
                return () -> {
                    togetherRoomId = fRoomId;
                    togetherInviterId = fInviterId;
                    listenTogetherRoomId.set(fRoomId);
                    listenTogetherInRoom.set(true);
                    listenTogetherBusy.set(false);
                    listenTogetherStatusText.set("已加入房间");
                    listenTogetherMembers.set("已连接");
                    toast.set("已加入一起听");
                };
            } catch (Exception e) {
                AdaptClient.LOGGER.warn("[PlayerController] joinListenTogether failed: {}", e.getMessage());
                return () -> {
                    listenTogetherBusy.set(false);
                    toast.set("加入房间失败: " + e.getMessage());
                };
            }
        });
    }

    public void leaveListenTogether() {
        if (!listenTogetherInRoom.get()) return;
        if (listenTogetherBusy.get()) return;
        listenTogetherBusy.set(true);
        final String roomId = togetherRoomId;
        async(() -> {
            try {
                netease.endTogetherRoom(roomId);
                return () -> {
                    togetherRoomId = "";
                    togetherInviterId = 0L;
                    togetherLastSongId = 0L;
                    togetherLastCommandTick = 0;
                    listenTogetherInRoom.set(false);
                    listenTogetherRoomId.set("");
                    listenTogetherMembers.set("");
                    listenTogetherStatusText.set("");
                    listenTogetherInvitation.set("");
                    listenTogetherBusy.set(false);
                    toast.set("已退出一起听房间");
                };
            } catch (Exception e) {
                AdaptClient.LOGGER.warn("[PlayerController] leaveListenTogether failed: {}", e.getMessage());
                return () -> {
                    listenTogetherBusy.set(false);
                    toast.set("退出房间失败: " + e.getMessage());
                };
            }
        });
    }

    public void copyListenTogetherInvitation() {
        String inv = listenTogetherInvitation.get();
        // 邀请链接未生成但房间仍在（例如创建成功回调未及时写入 UI 队列）：
        // 现场从 roomId 生成，保证"直接邀请"始终可用。
        if ((inv == null || inv.isEmpty()) && togetherRoomId != null && !togetherRoomId.isEmpty()) {
            long uid = togetherInviterId > 0 ? togetherInviterId : (neteaseUid > 0 ? neteaseUid : 0L);
            inv = uid > 0
                    ? "https://music.163.com/together/?roomId=" + togetherRoomId + "&inviterId=" + uid
                    : "https://music.163.com/together/?roomId=" + togetherRoomId;
            listenTogetherInvitation.set(inv);
        }
        if (inv == null || inv.isEmpty()) {
            toast.set("暂无邀请链接");
            return;
        }
        if (copyTextToClipboard(inv)) {
            toast.set("邀请链接已复制");
        } else {
            toast.set("复制失败");
        }
    }

    /** 复制文本到系统剪贴板。优先 GLFW（渲染线程/主线程，与原版一致）；窗口不可用或
     *  写入未生效（GLFW 失败只走错误回调、不抛异常）时回退到 AWT，最多尝试两者之一。 */
    private static boolean copyTextToClipboard(String text) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.getWindow() != null) {
                long windowHandle = mc.getWindow().handle();
                if (windowHandle != 0L) {
                    org.lwjgl.glfw.GLFW.glfwSetClipboardString(windowHandle, text);
                    // 回读校验：GLFW 剪贴板写入失败时仅设置错误回调，方法本身不抛异常，
                    // 不回读就会出现"提示已复制但剪贴板是空的"。
                    String readBack = org.lwjgl.glfw.GLFW.glfwGetClipboardString(windowHandle);
                    if (text.equals(readBack)) return true;
                    AdaptClient.LOGGER.warn("[PlayerController] GLFW clipboard write not effective, "
                            + "read back: {}", readBack == null ? "null" : readBack);
                }
            }
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[PlayerController] GLFW clipboard failed: {}", t.toString());
        }
        try {
            java.awt.Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
            return true;
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[PlayerController] AWT clipboard fallback failed: {}", t.toString());
            return false;
        }
    }

    /** 每秒 tick：心跳、同步队列、同步命令 */
    private void listenTogetherTick() {
        if (!listenTogetherInRoom.get()) return;
        if (togetherRoomId.isEmpty()) return;
        // 心跳
        try {
            Track curH = currentTrack();
            long curSongId = curH != null ? curH.neteaseId : 0L;
            long curPos = positionMs.get() != null ? positionMs.get() : 0L;
            netease.togetherHeartbeat(togetherRoomId, curSongId, Boolean.TRUE.equals(playing.peek()), curPos);
        } catch (Exception e) {
            AdaptClient.LOGGER.warn("[PlayerController] together heartbeat error: {}", e.getMessage());
        }
        // 每 3 秒同步一下本地变更
        long now = System.currentTimeMillis();
        if (now / 1000 % 3 == 0) {
            reportTogetherLocalChanges();
        }
        // 每 5 秒拉取远程快照
        if (now / 1000 % 5 == 0) {
            applyTogetherSnapshot();
        }
    }

    /** 检测本地播放变化并上报 */
    private void reportTogetherLocalChanges() {
        if (!listenTogetherInRoom.get()) return;
        Track cur = currentTrack();
        if (cur == null) return;
        long curId = cur.neteaseId;
        if (curId == 0) return;
        // 切歌上报
        if (curId != togetherLastSongId) {
            togetherLastSongId = curId;
            List<Long> ids = new ArrayList<>();
            ids.add(curId);
            try {
                netease.reportTogetherPlaylist(togetherRoomId, neteaseUid, 1L, ids);
                AdaptClient.LOGGER.info("[PlayerController] reported together playlist: {}", curId);
            } catch (Exception e) {
                AdaptClient.LOGGER.warn("[PlayerController] reportTogetherPlaylist failed: {}", e.getMessage());
            }
        }
    }

    /** 应用远程快照（别人切歌/操作） */
    private void applyTogetherSnapshot() {
        if (!listenTogetherInRoom.get()) return;
        try {
            TogetherSnapshot snap = netease.togetherSnapshot(togetherRoomId);
            // 远程切换歌曲：通过命令里的 targetSongId 判断
            if (snap.command != null && snap.command.targetSongId > 0
                    && snap.command.targetSongId != togetherLastSongId) {
                final long remoteSongId = snap.command.targetSongId;
                togetherLastSongId = remoteSongId;
                Track cur = currentTrack();
                if (cur == null || cur.neteaseId != remoteSongId) {
                    post(() -> {
                        Track remoteTrack = findTrackById(remoteSongId);
                        if (remoteTrack != null) {
                            setQueue(new ArrayList<>(queueTrackList), -1);
                            int idx = indexOfNeteaseTrack(remoteSongId);
                            if (idx >= 0) playTrack(idx);
                            else toast.set("房间同步：切换歌曲");
                        } else {
                            toast.set("房间同步：切换歌曲");
                        }
                    });
                }
            }
            // 房间人数：snapshot 不直接带成员数，用状态文本更新
            if (snap.songIds != null) {
                post(() -> listenTogetherStatusText.set("同步中(" + snap.songIds.size() + "首)"));
            }
        } catch (Exception e) {
            AdaptClient.LOGGER.warn("[PlayerController] applyTogetherSnapshot failed: {}", e.getMessage());
        }
    }

    private int indexOfNeteaseTrack(long songId) {
        for (int i = 0; i < queueTrackList.size(); i++) {
            if (queueTrackList.get(i).neteaseId == songId) return i;
        }
        return -1;
    }

    private static String neteaseMessage(JsonObject obj) {
        for (String key : new String[]{"message", "msg"}) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                String s = obj.get(key).getAsString();
                if (s != null && !s.isEmpty()) return s;
            }
        }
        return null;
    }

    // ==================== 转换工具 ====================

    private Track neteaseSongToTrack(NeteaseSong s) {
        Track t = new Track();
        t.source = Track.Source.NETEASE;
        t.neteaseId = s.id;
        t.title = s.name;
        t.artist = s.artist;
        t.album = s.album;
        t.coverUrl = s.coverUrl;
        t.coverThumbPath = s.coverThumbPath;
        t.durationMs = s.durationMs;
        return t;
    }

    private static Track toTrackCustom(CustomSong s) {
        Track t = new Track();
        t.source = Track.Source.CUSTOM_API;
        t.customId = s.id;
        t.title = s.name;
        t.artist = s.artist;
        t.album = s.album;
        t.coverUrl = s.coverUrl;
        t.coverThumbPath = s.coverThumbPath;
        t.durationMs = s.durationMs;
        return t;
    }

    private Map<String, Object> playlistToMap(NeteasePlaylist p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id);
        m.put("name", p.name != null ? p.name : "");
        m.put("coverUrl", p.coverUrl != null ? p.coverUrl : "");
        m.put("coverThumbPath", p.coverThumbPath != null ? p.coverThumbPath : "");
        m.put("trackCount", p.trackCount);
        m.put("playCount", p.playCount);
        m.put("description", p.description != null ? p.description : "");
        m.put("creatorNickname", p.creatorNickname != null ? p.creatorNickname : "");
        m.put("creatorUid", p.creatorUid);
        m.put("subscribed", p.subscribed);
        return m;
    }

    // ==================== 便捷 getter ====================

    public int backTick() { return backTick.get(); }
    public boolean lyricsOpen() { return lyricsOpen.get(); }
    public boolean queueOpen() { return queueOpen.get(); }
    // QML 下拉写入的 lyricSlide 可能是 Double（JS number raw 存储）——用 Number 安全读取
    public float lyricSlide() {
        Object v = lyricSlide.peek();
        return v instanceof Number ? ((Number) v).floatValue() : 0f;
    }
    public String toast() { return toast.get(); }
    public boolean updateAvailable() { return updateAvailable.get(); }
    public boolean playing() { return playing.get(); }
    public boolean loading() { return loading.get(); }
    public boolean loggedIn() { return loggedIn.get(); }
    public long position() { return positionMs.get(); }
    public long duration() { return durationMs.get(); }
    public int index() { return index.get(); }
    public int playMode() { return playMode.get(); }
    public float volume() { return volume.get(); }
    public String title() { return title.get(); }
    public String artist() { return artist.get(); }
    public String coverPath() { return coverPath.get(); }
}
