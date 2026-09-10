package com.ylmac.adapt.features.music.lyric.skia;

public final class LyricConfig {

    public static final LyricConfig instance = new LyricConfig();

    public enum FontWeight { THIN, LIGHT, REGULAR, MEDIUM }

    public static final class Val<T> {
        private volatile T value;
        Val(T value) { this.value = value; }
        public T getValue() { return value; }
        public void setValue(T value) { this.value = value; }
    }

    public final Val<Integer> lyricFontSize = new Val<>(28);
    public final Val<Integer> subFontSize = new Val<>(13);
    public final Val<FontWeight> fontWeight = new Val<>(FontWeight.REGULAR);
    public final Val<Float> lineSpacing = new Val<>(2.00f);
    public final Val<Boolean> showRomaji = new Val<>(Boolean.TRUE);
    public final Val<Boolean> showTranslation = new Val<>(Boolean.TRUE);
    public final Val<Boolean> springPhysics = new Val<>(Boolean.TRUE);
    public final Val<Boolean> scaleEmphasis = new Val<>(Boolean.TRUE);
    public final Val<Boolean> glow = new Val<>(Boolean.TRUE);
    public final Val<Boolean> edgeBlur = new Val<>(Boolean.FALSE);
    /** 歌词阴影：歌词文字下方柔和投影 */
    public final Val<Boolean> lyricShadow = new Val<>(Boolean.TRUE);
    /** 非逐字歌词线性动画：关闭后无逐字时间的行整行一次性点亮 */
    public final Val<Boolean> lyricLinearAnim = new Val<>(Boolean.TRUE);
    /** 歌词页进度条样式：wavy=波浪 straight=直线 */
    public final Val<String> lyricProgressStyle = new Val<>("wavy");
}
