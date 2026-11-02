package com.ylmac.adapt.command;

import com.ylmac.adapt.config.AppDirs;
import com.ylmac.adapt.AdaptClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

/**
 * 玩家名单管理器基类（好友/敌人列表通用）。
 * <p>
 * 内存 + 简单 JSON 文件持久化，
 * 26.2 无 1.8.9 的 FriendManager，此为简化实现。
 */
public abstract class PlayerListManager {

    protected final ArrayList<String> players = new ArrayList<>();
    private final String fileName;

    protected PlayerListManager(String fileName) {
        this.fileName = fileName;
        load();
    }

    /** 添加玩家，返回规范化后的名字；已存在返回 null */
    public synchronized String add(String name) {
        String n = normalize(name);
        if (n == null || contains(n)) return null;
        players.add(n);
        save();
        return n;
    }

    /** 移除玩家，返回被移除的名字；不存在返回 null */
    public synchronized String remove(String name) {
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).equalsIgnoreCase(name)) {
                String r = players.remove(i);
                save();
                return r;
            }
        }
        return null;
    }

    public synchronized boolean contains(String name) {
        for (String p : players) {
            if (p.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public synchronized ArrayList<String> getPlayers() {
        return new ArrayList<>(players);
    }

    public synchronized void clear() {
        players.clear();
        save();
    }

    protected String normalize(String name) {
        if (name == null) return null;
        String n = name.trim().replace("§", "&");
        return n.isEmpty() ? null : n;
    }

 // ── 持久化 ──

    private Path file() {
        return Paths.get(AppDirs.base(), fileName);
    }

    private void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.write(file(), String.join("\n", players).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            AdaptClient.LOGGER.warn("[{}] save failed: {}", fileName, e.toString());
        }
    }

    private void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty()) players.add(line.trim());
            }
        } catch (Exception e) {
            AdaptClient.LOGGER.warn("[{}] load failed: {}", fileName, e.toString());
        }
    }
}
