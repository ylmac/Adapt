package com.ylmac.adapt.features.module.impl.render.gui;

import java.util.HashMap;
import java.util.Map;

/**
 * Rise 6.9.5 多语言系统移植（util/localization/Localization + LocalizationScreen）。
 * <p>
 * 说明（用户要求：新建系统只放在 render 模块目录下，不污染项目其它位置）：
 * 本文件位于 {@code features/module/impl/render/gui/}。
 * <p>
 * Rise 用 lang 文件 + key 查表（Localization.ce(key)）。这里给 Rise 风格 ClickGUI
 * 自用的一套精简表（GUI 自身文案 + 常用界面词），模块名/描述仍走客户端既有文案。
 */
public final class RiseLang {

    /**
     * 语言（对齐 Rise Locale 枚举 + LanguageComponent 的两行显示）：
     * {@code file} = 国旗贴图名（assets/adaptclient/rise/flags/<file>.png，直接拷贝自 Rise）、
     * {@code english} = 20pt 主行、{@code local} = 17pt 副行。
     */
    public record Lang(String file, String english, String local) { }

    public static final Lang[] ALL = {
            new Lang("en_US", "English", "English"),
            new Lang("fr_FR", "French", "Français"),
            new Lang("es_ES", "Spanish", "Español"),
            new Lang("sv_SE", "Swedish", "Svenska"),
            new Lang("pl_PL", "Polish", "Polski"),
            new Lang("ru_RU", "Russian", "Русский"),
            new Lang("zh_ZH", "Chinese", "简体中文"),
    };

    private static Lang current = ALL[6];   // 默认 zh_ZH（与客户端现有中文界面一致）

    private static final Map<String, String[]> TABLE = new HashMap<>();

    static {
        // key → [en, zh_cn, zh_tw, ja, ko, ru, de, fr, es, pt]
        put("ui.search", "Search", "搜索", "搜尋", "検索", "검색", "Поиск", "Suche", "Recherche", "Buscar", "Pesquisar");
        put("ui.search.text", "Start typing to search...", "输入以搜索...", "輸入以搜尋...", "入力して検索...", "입력하여 검색...",
                "Начните вводить...", "Tippen zum Suchen...", "Tapez pour rechercher...", "Escribe para buscar...", "Digite para pesquisar...");
        put("ui.theme", "Themes", "主题", "主題", "テーマ", "테마", "Темы", "Themen", "Thèmes", "Temas", "Temas");
        put("ui.language", "Language", "语言", "語言", "言語", "언어", "Язык", "Sprache", "Langue", "Idioma", "Idioma");
        put("ui.script", "Scripts", "脚本", "腳本", "スクリプト", "스크립트", "Скрипты", "Skripte", "Scripts", "Scripts", "Scripts");
        put("ui.script.featured", "Featured", "精选", "精選", "おすすめ", "추천", "Рекомендуемые", "Empfohlen", "Sélection", "Destacados", "Destaques");
        put("ui.script.yours", "Your Configs", "你的配置", "你的設定", "あなたの設定", "내 설정", "Ваши конфиги", "Deine Configs", "Tes configs", "Tus configs", "Suas configs");
        put("ui.script.save", "Save Current", "保存当前", "儲存目前", "現在を保存", "현재 저장", "Сохранить", "Speichern", "Enregistrer", "Guardar", "Salvar");
        put("ui.config.loaded", "Config loaded", "配置已加载", "設定已載入", "設定を読み込みました", "설정 로드됨", "Конфиг загружен",
                "Config geladen", "Config chargée", "Config cargada", "Config carregada");
        put("ui.config.saved", "Config saved", "配置已保存", "設定已儲存", "設定を保存しました", "설정 저장됨", "Конфиг сохранён",
                "Config gespeichert", "Config enregistrée", "Config guardada", "Config salva");
    }

    private static void put(String key, String... vals) {
        TABLE.put(key, vals);
    }

    /** Localization.ce(key)：查不到就返回 key 本身。 */
    public static String tr(String key) {
        String[] row = TABLE.get(key);
        if (row == null) return key;
        // 语言表只提供 7 种（对齐 Rise Locale），超出的键回落英文
        int idx = indexOf(current);
        if (idx >= row.length) idx = 0;
        return row[Math.max(0, Math.min(row.length - 1, idx))];
    }

    private static int indexOf(Lang l) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i] == l) return i;
        return 0;
    }

    public static Lang current() { return current; }

    public static void setCurrent(Lang l) {
        if (l != null) current = l;
    }
}
