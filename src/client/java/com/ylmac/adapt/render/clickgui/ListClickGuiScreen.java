package com.ylmac.adapt.render.clickgui;

import com.ylmac.adapt.AdaptClient;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.ModuleManager;
import com.ylmac.adapt.module.settings.Setting;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.module.settings.impl.ButtonSetting;
import com.ylmac.adapt.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.module.settings.impl.ColorSetting;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.module.settings.impl.EnumSetting;
import com.ylmac.adapt.module.settings.impl.IntSetting;
import com.ylmac.adapt.module.settings.impl.StringSetting;
import com.ylmac.adapt.render.surface.MinecraftSurfaceBackend;
import com.ylmac.adapt.render.surface.SkijaNativeFix;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.ylmac.adapt.input.GLFW;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 左键拖标题、右键收起栏；模块左键开关、右键展开、中键绑键。
 */
public class ListClickGuiScreen extends Screen {

    private static final Category[] ORDER = {
            Category.COMBAT, Category.MOVEMENT, Category.PLAYER, Category.RENDER, Category.SETTINGS
    };

    private static final float COL_W = 152f;
    private static final float COL_GAP = 8f;
    private static final float HEADER_H = 21f;
    private static final float ROW_H = 19.6f;
    private static final float FOOTER_H = 7f;
    private static final float RADIUS = 7f;
    private static final float TEXT_PAD = 10f;
    private static final float BOOL_H = 19.6f;
    private static final float NUM_H = 20f;
    private static final float MODE_H = 40f;
    private static final float FIELD_Y = 16f;
    private static final float FIELD_H = 21f;
    private static final float OPTION_H = 20f;

    private static final int HEADER = 0xFF1C1F29;
    private static final int BODY = 0xCC151315;
    private static final int ENABLED = 0xFFA8B0E7;
    private static final int ENABLED_HOVER = 0xFFB0B8EF;
    private static final int ACCENT = 0xFFBBC3FF;
    private static final int ENABLED_TEXT = 0xFF172778;
    private static final int TEXT = 0xFFE4E1E6;
    private static final int HOVER = 0x0CE4E1E6;
    private static final int FIELD = 0xFF2D2D38;
    private static final int FIELD_LINE = 0xFFC4C0CC;
    private static final int TRACK = 0xFF484858;
    private static final int SCROLLBAR = 0xCC636472;
    private static final int SELECTED = 0xFF414253;
    private static final int TOGGLE_OUTLINE = 0xFF938F99;

    private static final Map<Category, float[]> savedPos = new HashMap<>();

    private final List<Column> columns = new ArrayList<>();
    private final Paint paint = new Paint().setAntiAlias(true);
    private Typeface typeface;
    private Font font;
    private MinecraftSurfaceBackend surf;
    private int surfW = -1;
    private int surfH = -1;
    private boolean built;
    private float colW = COL_W;
    private float maxBody = 320f;
    private float centerY;
    private float menuScale = 1f;
    private float menuAlpha = 1f;
    private long openedAt;
    private long lastNs;
    private double mouseX;
    private double mouseY;
    private double rawX;
    private double rawY;

    private Column dragged;
    private float dragDX;
    private float dragDY;
    private Column scrollDrag;
    private float scrollGrab;
    private Row sliderRow;
    private Setting<?> slider;
    private Row binding;

    public ListClickGuiScreen() {
        super(Component.literal("ClickGUI"));
        openedAt = System.nanoTime();
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    @Override
    public void removed() {
        super.removed();
        if (surf != null) {
            try { surf.dispose(); } catch (Throwable ignored) { }
            surf = null;
        }
        if (font != null) { font.close(); font = null; }
        if (typeface != null) { typeface.close(); typeface = null; }
        paint.close();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        Minecraft mc = Minecraft.getInstance();
        int guiW = mc.getWindow().getGuiScaledWidth();
        int guiH = mc.getWindow().getGuiScaledHeight();
        rawX = mx;
        rawY = my;
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 16f : Math.min(64f, (now - lastNs) / 1_000_000f);
        lastNs = now;
        float openT = (now - openedAt) / 1_000_000f;
        menuScale = openT >= 320f ? 1f : 1f + (float) (0.18 * Math.exp(-17 * openT / 1000.0) * Math.cos(23 * openT / 1000.0));
        menuAlpha = Math.min(1f, openT / 180f);
        try {
            SkijaNativeFix.preload();
            ensureFont();
            layout(guiW, guiH, dt);
            float scale = mc.getWindow().getGuiScale();
            if (surf == null || surfW != guiW || surfH != guiH) {
                if (surf != null) surf.dispose();
                surf = new MinecraftSurfaceBackend("list_clickgui", guiW, guiH, scale);
                surfW = guiW;
                surfH = guiH;
            } else {
                surf.ensureDeviceScale(scale);
            }
            Canvas canvas = surf.acquireCanvas();
            if (canvas == null) return;
            float cx = guiW / 2f;
            canvas.save();
            canvas.translate(cx, centerY);
            canvas.scale(menuScale, menuScale);
            canvas.translate(-cx, -centerY);
            if (menuAlpha < 0.999f) {
                paint.setAlphaf(menuAlpha);
                canvas.saveLayer(Rect.makeXYWH(-40, -40, guiW + 80, guiH + 80), paint);
                paint.setAlphaf(1f);
            }
            for (Column column : columns) drawColumn(canvas, column);
            for (Column column : columns) drawDropdown(canvas, column);
            if (menuAlpha < 0.999f) canvas.restore();
            canvas.restore();
            surf.present();
            int tw = surf.getDisplayWidth();
            int th = surf.getDisplayHeight();
            g.blit(surf.blitPipeline(), surf.getTextureId(), 0, 0, 0f, 0f, guiW, guiH, tw, th, tw, th);
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[ListClickGUI] render failed: {}", t.toString());
        }
    }

    private void ensureFont() {
        if (font != null) return;
        FontMgr mgr = FontMgr.getDefault();
        typeface = mgr.matchFamilyStyle("PingFang SC", FontStyle.NORMAL);
        if (typeface == null) typeface = mgr.matchFamilyStyle(null, FontStyle.NORMAL);
        if (typeface == null) return;
        font = new Font(typeface, 8.5f);
        font.setSubpixel(true);
    }

    private void layout(int guiW, int guiH, float dt) {
        float margin = Math.min(30f, guiW * 0.0382f);
        float avail = guiW - margin * 2f - (ORDER.length - 1) * COL_GAP;
        colW = Math.min(COL_W, Math.max(108f, avail / ORDER.length));
        float top = Math.max(8f, guiH * 0.112f);
        maxBody = Math.min(16f * ROW_H + FOOTER_H, Math.max(ROW_H, guiH - top - HEADER_H - Math.max(8f, guiH * 0.10f)));
        centerY = top + (HEADER_H + maxBody) / 2f;
        mouseX = width / 2.0 + (rawX - width / 2.0) / Math.max(0.05f, menuScale);
        mouseY = centerY + (rawY - centerY) / Math.max(0.05f, menuScale);
        if (!built) {
            built = true;
            for (int i = 0; i < ORDER.length; i++) {
                Column column = new Column(ORDER[i]);
                for (Module module : ModuleManager.INSTANCE.getModules()) {
                    if (module.getCategory() == ORDER[i]) column.rows.add(new Row(module));
                }
                column.rows.sort(Comparator.comparing(row -> row.module.getName(), String.CASE_INSENSITIVE_ORDER));
                float[] saved = savedPos.get(ORDER[i]);
                column.x = saved != null ? saved[0] : margin + i * (colW + COL_GAP);
                column.y = saved != null ? saved[1] : top;
                columns.add(column);
            }
        }
        float smooth = 1f - (float) Math.exp(-dt * 0.014);
        for (Column column : columns) {
            column.x = clamp(column.x, 4f, Math.max(4f, guiW - colW - 4f));
            float expandTarget = column.expanded ? 1f : 0f;
            column.expand += (expandTarget - column.expand) * Math.min(1f, dt / 90f);
            if (Math.abs(column.expand - expandTarget) < 0.01f) column.expand = expandTarget;
            float content = 0f;
            column.entries.clear();
            for (Row row : column.rows) {
                row.enabledAnim += ((row.module.isEnabled() ? 1f : 0f) - row.enabledAnim) * Math.min(1f, dt / 80f);
                row.expand += ((row.expanded ? 1f : 0f) - row.expand) * Math.min(1f, dt / 90f);
                float topY = content;
                column.entries.add(new Entry(row, null, Kind.MODULE, topY, ROW_H));
                content += ROW_H;
                float block = settingsHeight(row);
                float shown = block * Math.max(0f, Math.min(1f, row.expand));
                if (shown > 0.5f) {
                    float used = 0f;
                    for (Setting<?> setting : visibleSettings(row.module)) {
                        float h = settingHeight(setting);
                        if (used + h > shown + 0.5f) break;
                        column.entries.add(new Entry(row, setting, kindOf(setting), content + used, h));
                        used += h;
                    }
                    content += shown;
                }
            }
            column.contentH = content;
            float available = Math.max(ROW_H + FOOTER_H, guiH - column.y - HEADER_H - 8f);
            float target = Math.min(maxBody, Math.min(available, content + FOOTER_H));
            column.bodyH = target * column.expand;
            column.maxScroll = Math.max(0f, content - Math.max(0f, column.bodyH - FOOTER_H));
            if (!column.expanded && column.expand < 0.02f) column.scroll = column.scrollTarget = 0f;
            column.scrollTarget = clamp(column.scrollTarget, 0f, column.maxScroll);
            column.scroll += (column.scrollTarget - column.scroll) * smooth;
            column.y = clamp(column.y, 4f, Math.max(4f, guiH - HEADER_H - column.bodyH - 8f));
        }
        float hoverStep = Math.min(1f, dt / 70f);
        for (Column column : columns) {
            for (Row row : column.rows) {
                float target = rowHovered(column, row) ? 1f : 0f;
                row.hover += (target - row.hover) * hoverStep;
            }
        }
    }

    private void drawColumn(Canvas canvas, Column column) {
        float bodyY = column.y + HEADER_H;
        if (column.bodyH > 0.5f) {
            panel(canvas, column.x, bodyY, colW, column.bodyH, false, BODY);
        }
        panel(canvas, column.x, column.y, colW, HEADER_H, true, HEADER);
        text(canvas, column.category.getDisplayName(), column.x + TEXT_PAD, column.y + HEADER_H / 2f,
                9.2f, colW - TEXT_PAD * 2f, TEXT);
        if (column.bodyH <= FOOTER_H + 0.5f) return;
        canvas.save();
        canvas.clipRect(Rect.makeXYWH(column.x, bodyY, colW, column.bodyH - FOOTER_H), true);
        for (Entry entry : column.entries) {
            float y = bodyY + entry.top - column.scroll;
            if (y + entry.height < bodyY || y > bodyY + column.bodyH - FOOTER_H) continue;
            drawEntry(canvas, column, entry, y);
        }
        canvas.restore();
        if (column.maxScroll > 0.5f) {
            float visible = Math.max(1f, column.bodyH - FOOTER_H);
            float track = Math.max(0f, visible - 4f);
            float thumb = Math.min(track, Math.max(12f, track * visible / Math.max(1f, column.contentH)));
            float travel = track - thumb;
            float top = 2f + (column.maxScroll <= 0f ? 0f : travel * column.scroll / column.maxScroll);
            round(canvas, column.x + colW - 3.5f, bodyY + top, 2.5f, thumb, 1.25f, SCROLLBAR);
        }
    }

    private void drawEntry(Canvas canvas, Column column, Entry entry, float y) {
        Row row = entry.row;
        if (entry.kind == Kind.MODULE) {
            float enabled = clamp(row.enabledAnim, 0f, 1f);
            float hover = clamp(row.hover, 0f, 1f);
            if (hover > 0.01f && enabled < 0.99f) {
                rect(canvas, column.x, y, colW, ROW_H, withAlpha(HOVER, hover * (1f - enabled)));
            }
            if (enabled > 0.01f) {
                rect(canvas, column.x, y, colW, ROW_H, withAlpha(mix(ENABLED, ENABLED_HOVER, hover), enabled));
            }
            String label = row == binding ? "Press a key" : row.module.getName();
            text(canvas, label, column.x + TEXT_PAD, y + ROW_H / 2f, 8.3f, colW - TEXT_PAD * 2f,
                    mix(TEXT, ENABLED_TEXT, enabled));
            return;
        }
        Setting<?> setting = entry.setting;
        if (setting instanceof BoolSetting bool) {
            text(canvas, setting.getDisplayName(), column.x + TEXT_PAD, y + BOOL_H / 2f, 8.5f, colW - TEXT_PAD * 2f - 32f, TEXT);
            drawToggle(canvas, column.x + colW - TEXT_PAD - 26f, y + (BOOL_H - 16f) / 2f, Boolean.TRUE.equals(bool.getValue()));
        } else if (setting instanceof IntSetting || setting instanceof DoubleSetting) {
            String value = formatNumber(setting);
            float valueW = Math.min((colW - TEXT_PAD * 2f) * 0.45f, measure(value, 8.5f));
            text(canvas, setting.getDisplayName(), column.x + TEXT_PAD, y + 6.5f, 8.5f, colW - TEXT_PAD * 2f - valueW - 4f, TEXT);
            text(canvas, value, column.x + colW - TEXT_PAD - valueW, y + 6.5f, 8.5f, valueW, TEXT);
            float fraction = fraction(setting);
            float trackW = colW - TEXT_PAD * 2f;
            float trackY = y + 15.6f;
            round(canvas, column.x + TEXT_PAD, trackY - 1.25f, trackW, 2.5f, 1.25f, TRACK);
            round(canvas, column.x + TEXT_PAD, trackY - 1.25f, trackW * fraction, 2.5f, 1.25f, ACCENT);
            float knobX = column.x + TEXT_PAD + trackW * fraction;
            round(canvas, knobX - 4.5f, trackY - 3.5f, 9f, 9f, 4.5f, 0x33000000);
            round(canvas, knobX - 4f, trackY - 4f, 8f, 8f, 4f, ACCENT);
        } else if (setting instanceof EnumSetting<?> || setting instanceof ChoiceSetting) {
            text(canvas, setting.getDisplayName(), column.x + TEXT_PAD, y + 8f, 8.5f, colW - TEXT_PAD * 2f, TEXT);
            float fx = column.x + TEXT_PAD - 2f;
            float fy = y + FIELD_Y;
            float fw = colW - TEXT_PAD * 2f + 4f;
            boolean open = row.open == setting;
            round(canvas, fx, fy, fw, FIELD_H, 2f, FIELD);
            if (open) rect(canvas, fx, fy + 3f, fw, MODE_H - FIELD_Y - 3f, FIELD);
            text(canvas, currentLabel(setting), fx + 6f, fy + FIELD_H / 2f, 8.5f, fw - 12f, TEXT);
            rect(canvas, fx + 1f, fy + FIELD_H - 1f, fw - 2f, 1f, open ? ENABLED : FIELD_LINE);
        } else if (setting instanceof ColorSetting color) {
            text(canvas, setting.getDisplayName(), column.x + TEXT_PAD, y + BOOL_H / 2f, 8.5f, colW - TEXT_PAD * 2f - 18f, TEXT);
            Color c = color.getValue();
            int argb = c == null ? 0xFFFFFFFF : (0xFF000000 | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue());
            round(canvas, column.x + colW - TEXT_PAD - 12f, y + (BOOL_H - 12f) / 2f, 12f, 12f, 3f, argb);
        } else {
            String extra = setting instanceof StringSetting ss && ss.getValue() != null ? ss.getValue() : "";
            text(canvas, extra.isEmpty() ? setting.getDisplayName() : setting.getDisplayName() + ": " + extra,
                    column.x + TEXT_PAD, y + BOOL_H / 2f, 8.5f, colW - TEXT_PAD * 2f, TEXT);
        }
    }

    private void drawDropdown(Canvas canvas, Column column) {
        for (Row row : column.rows) {
            if (row.open == null || row.expand < 0.85f) continue;
            Entry host = find(column, row.open);
            if (host == null) continue;
            float y = column.y + HEADER_H + host.top - column.scroll;
            float fx = column.x + TEXT_PAD - 2f;
            float fy = y + FIELD_Y + FIELD_H;
            String[] options = options(row.open);
            if (options.length == 0) continue;
            int shown = Math.min(options.length, 8);
            float h = shown * OPTION_H;
            if (fy + h > height - 4f) h = Math.max(OPTION_H, height - 4f - fy);
            round(canvas, fx, fy, colW - TEXT_PAD * 2f + 4f, h, 2f, FIELD);
            canvas.save();
            canvas.clipRect(Rect.makeXYWH(fx, fy, colW - TEXT_PAD * 2f + 4f, h), true);
            float scroll = clamp(row.optionScroll, 0f, Math.max(0f, options.length * OPTION_H - h));
            row.optionScroll = scroll;
            for (int i = 0; i < options.length; i++) {
                float oy = fy + i * OPTION_H - scroll;
                if (oy + OPTION_H < fy || oy > fy + h) continue;
                boolean selected = i == selectedIndex(row.open);
                boolean hover = contains(fx, oy, colW - TEXT_PAD * 2f + 4f, OPTION_H);
                if (selected || hover) rect(canvas, fx + 1f, oy, colW - TEXT_PAD * 2f + 2f, OPTION_H, selected ? SELECTED : HOVER);
                if (selected) check(canvas, fx + 6f, oy + 7f, 6f, TEXT);
                text(canvas, options[i], fx + (selected ? 18f : 6f), oy + OPTION_H / 2f, 8.5f,
                        colW - TEXT_PAD * 2f - (selected ? 24f : 12f), TEXT);
            }
            canvas.restore();
        }
    }

    private void drawToggle(Canvas canvas, float x, float y, boolean on) {
        round(canvas, x, y, 26f, 16f, 8f, on ? ACCENT : TOGGLE_OUTLINE);
        if (!on) round(canvas, x + 1f, y + 1f, 24f, 14f, 7f, FIELD);
        float knob = x + 2f + (on ? 10f : 0f);
        round(canvas, knob, y + 2f, 12f, 12f, 6f, on ? ENABLED_TEXT : FIELD_LINE);
        if (on) check(canvas, knob + 3f, y + 5f, 6f, ACCENT);
    }

    private void panel(Canvas canvas, float x, float y, float w, float h, boolean header, int color) {
        float r = Math.min(RADIUS, h / 2f);
        round(canvas, x, y, w, h, r, color);
        if (r <= 0f) return;
        if (header) rect(canvas, x, y + h - r, w, r, color);
        else rect(canvas, x, y, w, Math.min(r, h), color);
    }

    private void round(Canvas canvas, float x, float y, float w, float h, float r, int color) {
        if (w <= 0.5f || h <= 0.5f) return;
        paint.setAntiAlias(true);
        paint.setColor(color);
        paint.setMode(PaintMode.FILL);
        canvas.drawRRect(RRect.makeXYWH(x, y, w, h, Math.min(r, Math.min(w, h) / 2f)), paint);
    }

    private void rect(Canvas canvas, float x, float y, float w, float h, int color) {
        if (w <= 0.5f || h <= 0.5f) return;
        paint.setAntiAlias(false);
        paint.setColor(color);
        paint.setMode(PaintMode.FILL);
        canvas.drawRect(Rect.makeXYWH(x, y, w, h), paint);
        paint.setAntiAlias(true);
    }

    private void check(Canvas canvas, float x, float y, float size, int color) {
        Path path = Path.makeFromSVGString(String.format(java.util.Locale.ROOT,
                "M%.2f %.2f L%.2f %.2f L%.2f %.2f",
                x, y + size * 0.55f, x + size * 0.38f, y + size * 0.9f, x + size, y));
        if (path == null) return;
        paint.setAntiAlias(true);
        paint.setColor(color);
        paint.setMode(PaintMode.STROKE);
        paint.setStrokeWidth(1.15f);
        canvas.drawPath(path, paint);
        paint.setMode(PaintMode.FILL);
        path.close();
    }

    private void text(Canvas canvas, String value, float x, float centerY, float size, float maxW, int color) {
        if (font == null || value == null || maxW <= 2f) return;
        font.setSize(size);
        String label = ellipsis(value, maxW);
        var metrics = font.getMetrics();
        float baseline = centerY - (metrics.getAscent() + metrics.getDescent()) / 2f;
        paint.setAntiAlias(true);
        paint.setColor(color);
        paint.setMode(PaintMode.FILL);
        canvas.drawString(label, x, baseline, font, paint);
    }

    private String ellipsis(String value, float maxW) {
        if (font.measureTextWidth(value) <= maxW) return value;
        String dots = "...";
        if (font.measureTextWidth(dots) > maxW) return "";
        int end = value.length();
        while (end > 0 && font.measureTextWidth(value.substring(0, end) + dots) > maxW) end--;
        return value.substring(0, end) + dots;
    }

    private float measure(String value, float size) {
        if (font == null || value == null) return 0f;
        font.setSize(size);
        return font.measureTextWidth(value);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        rawX = e.x();
        rawY = e.y();
        int button = e.button();
        layout(width, height, 16f);
        float mx = (float) mouseX;
        float my = (float) mouseY;
        if (clickDropdown(mx, my, button)) return true;
        Column column = columnAt(mx, my);
        if (column == null) {
            closeDropdowns();
            return true;
        }
        columns.remove(column);
        columns.add(column);
        if (my < column.y + HEADER_H) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                column.expanded = !column.expanded;
            } else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                dragged = column;
                dragDX = mx - column.x;
                dragDY = my - column.y;
            }
            return true;
        }
        if (!column.expanded) return true;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && column.maxScroll > 0f
                && mx >= column.x + colW - 6f && mx <= column.x + colW
                && my >= column.y + HEADER_H && my <= column.y + HEADER_H + column.bodyH - FOOTER_H) {
            scrollDrag = column;
            scrollGrab = 8f;
            dragScroll(my);
            return true;
        }
        Entry entry = hit(column, my);
        if (entry == null) return true;
        if (entry.kind == Kind.MODULE) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) entry.row.module.toggle();
            else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) entry.row.expanded = !entry.row.expanded;
            else if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) binding = entry.row;
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !entry.row.expanded) return true;
        Setting<?> setting = entry.setting;
        if (setting instanceof BoolSetting bool) {
            bool.setValue(!Boolean.TRUE.equals(bool.getValue()));
        } else if (setting instanceof IntSetting || setting instanceof DoubleSetting) {
            slider = setting;
            sliderRow = entry.row;
            applySlider(mx);
        } else if (setting instanceof EnumSetting<?> || setting instanceof ChoiceSetting) {
            float y = column.y + HEADER_H + entry.top - column.scroll;
            if (my >= y + FIELD_Y && my <= y + FIELD_Y + FIELD_H) {
                boolean was = entry.row.open == setting;
                closeDropdowns();
                entry.row.open = was ? null : setting;
                entry.row.optionScroll = 0f;
            }
        } else if (setting instanceof ButtonSetting buttonSetting && buttonSetting.getValue() != null) {
            buttonSetting.getValue().run();
        }
        return true;
    }

    private boolean clickDropdown(float mx, float my, int button) {
        for (int i = columns.size() - 1; i >= 0; i--) {
            Column column = columns.get(i);
            for (Row row : column.rows) {
                if (row.open == null) continue;
                Rect box = dropdownBox(column, row);
                if (box == null || !box.contains(mx, my)) continue;
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    int index = (int) ((my - box.getTop() + row.optionScroll) / OPTION_H);
                    String[] options = options(row.open);
                    if (index >= 0 && index < options.length) select(row.open, index);
                    row.open = null;
                } else {
                    row.open = null;
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        float mx = localX(e.x());
        float my = localY(e.y());
        mouseX = mx;
        mouseY = my;
        if (e.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        if (dragged != null) {
            dragged.x = clamp(mx - dragDX, 4f, Math.max(4f, width - colW - 4f));
            dragged.y = clamp(my - dragDY, 4f, Math.max(4f, height - HEADER_H - dragged.bodyH - 8f));
            savedPos.put(dragged.category, new float[]{dragged.x, dragged.y});
            return true;
        }
        if (scrollDrag != null) {
            dragScroll(my);
            return true;
        }
        if (slider != null && sliderRow != null && sliderRow.expanded) {
            applySlider(mx);
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (e.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragged = null;
            scrollDrag = null;
            slider = null;
            sliderRow = null;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        float mx = localX(x);
        float my = localY(y);
        mouseX = mx;
        mouseY = my;
        layout(width, height, 16f);
        Column column = columnAt(mx, my);
        if (column != null) {
            for (Row row : column.rows) {
                Rect box = dropdownBox(column, row);
                if (box != null && box.contains(mx, my)) {
                    String[] options = options(row.open);
                    float max = Math.max(0f, options.length * OPTION_H - box.getHeight());
                    row.optionScroll = clamp(row.optionScroll - (float) v * OPTION_H, 0f, max);
                    return true;
                }
            }
            column.scrollTarget = clamp(column.scrollTarget - (float) v * ROW_H * 2f, 0f, column.maxScroll);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int key = e.key();
        if (binding != null) {
            binding.module.setKeyBind(key == GLFW.GLFW_KEY_ESCAPE ? -1 : key);
            binding = null;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (!closeDropdowns()) onClose();
            return true;
        }
        return super.keyPressed(e);
    }

    private void dragScroll(float my) {
        Column column = scrollDrag;
        float visible = Math.max(1f, column.bodyH - FOOTER_H);
        float track = Math.max(1f, visible - 4f);
        float thumb = Math.min(track, Math.max(12f, track * visible / Math.max(1f, column.contentH)));
        float travel = Math.max(1f, track - thumb);
        float thumbTop = my - column.y - HEADER_H - scrollGrab;
        column.scroll = column.scrollTarget = clamp((thumbTop - 2f) / travel, 0f, 1f) * column.maxScroll;
    }

    private void applySlider(float mx) {
        float fraction = clamp((mx - sliderRowColumnX() - TEXT_PAD) / Math.max(1f, colW - TEXT_PAD * 2f), 0f, 1f);
        if (slider instanceof IntSetting is) {
            int span = is.getMax() - is.getMin();
            int raw = is.getMin() + Math.round(fraction * span);
            int step = Math.max(1, is.getStep());
            int snapped = is.getMin() + Math.round((raw - is.getMin()) / (float) step) * step;
            is.setValue(Math.max(is.getMin(), Math.min(is.getMax(), snapped)));
        } else if (slider instanceof DoubleSetting ds) {
            double span = ds.getMax() - ds.getMin();
            double raw = ds.getMin() + fraction * span;
            double step = ds.getStep();
            if (step > 0) raw = ds.getMin() + Math.round((raw - ds.getMin()) / step) * step;
            ds.setValue(Math.max(ds.getMin(), Math.min(ds.getMax(), raw)));
        }
    }

    private float sliderRowColumnX() {
        for (Column column : columns) {
            if (column.rows.contains(sliderRow)) return column.x;
        }
        return 0f;
    }

    private boolean closeDropdowns() {
        boolean any = false;
        for (Column column : columns) {
            for (Row row : column.rows) {
                any |= row.open != null;
                row.open = null;
            }
        }
        return any;
    }

    private Column columnAt(float mx, float my) {
        for (int i = columns.size() - 1; i >= 0; i--) {
            Column column = columns.get(i);
            if (mx >= column.x && mx <= column.x + colW && my >= column.y && my <= column.y + HEADER_H + column.bodyH) {
                return column;
            }
        }
        return null;
    }

    private Entry hit(Column column, float my) {
        float local = my - column.y - HEADER_H + column.scroll;
        if (my < column.y + HEADER_H || my > column.y + HEADER_H + column.bodyH - FOOTER_H) return null;
        for (Entry entry : column.entries) {
            if (local >= entry.top && local < entry.top + entry.height) return entry;
        }
        return null;
    }

    private boolean rowHovered(Column column, Row row) {
        if (dragged != null) return false;
        if (columnAt((float) mouseX, (float) mouseY) != column) return false;
        Entry entry = hit(column, (float) mouseY);
        return entry != null && entry.kind == Kind.MODULE && entry.row == row;
    }

    private Entry find(Column column, Setting<?> setting) {
        for (Entry entry : column.entries) {
            if (entry.setting == setting) return entry;
        }
        return null;
    }

    private Rect dropdownBox(Column column, Row row) {
        if (row.open == null) return null;
        Entry host = find(column, row.open);
        if (host == null) return null;
        String[] options = options(row.open);
        if (options.length == 0) return null;
        float y = column.y + HEADER_H + host.top - column.scroll + FIELD_Y + FIELD_H;
        float h = Math.min(options.length, 8) * OPTION_H;
        if (y + h > height - 4f) h = Math.max(OPTION_H, height - 4f - y);
        return Rect.makeXYWH(column.x + TEXT_PAD - 2f, y, colW - TEXT_PAD * 2f + 4f, h);
    }

    private boolean contains(float x, float y, float w, float h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private float localX(double x) {
        return (float) (width / 2.0 + (x - width / 2.0) / menuScale);
    }

    private float localY(double y) {
        return (float) (centerY + (y - centerY) / menuScale);
    }

    private static List<Setting<?>> visibleSettings(Module module) {
        List<Setting<?>> out = new ArrayList<>();
        for (Setting<?> setting : module.getSettings()) {
            Setting.Dependency dependency = setting.getDependency();
            if (dependency == null || dependency.check()) out.add(setting);
        }
        return out;
    }

    private static float settingsHeight(Row row) {
        float h = 0f;
        for (Setting<?> setting : visibleSettings(row.module)) h += settingHeight(setting);
        return h;
    }

    private static float settingHeight(Setting<?> setting) {
        if (setting instanceof EnumSetting<?> || setting instanceof ChoiceSetting) return MODE_H;
        if (setting instanceof IntSetting || setting instanceof DoubleSetting) return NUM_H;
        return BOOL_H;
    }

    private static Kind kindOf(Setting<?> setting) {
        if (setting instanceof BoolSetting) return Kind.BOOL;
        if (setting instanceof IntSetting || setting instanceof DoubleSetting) return Kind.NUMBER;
        if (setting instanceof EnumSetting<?> || setting instanceof ChoiceSetting) return Kind.MODE;
        return Kind.TEXT;
    }

    private static String currentLabel(Setting<?> setting) {
        if (setting instanceof EnumSetting<?> es) return es.getTranslatedValue();
        if (setting instanceof ChoiceSetting cs) return cs.getTranslatedValue();
        return "";
    }

    private static String[] options(Setting<?> setting) {
        if (setting instanceof EnumSetting<?> es) {
            String[] out = new String[es.getModes().length];
            for (int i = 0; i < out.length; i++) out[i] = es.getTranslatedValueByIndex(i);
            return out;
        }
        if (setting instanceof ChoiceSetting cs) {
            List<String> choices = cs.getChoices();
            String[] out = new String[choices.size()];
            for (int i = 0; i < out.length; i++) out[i] = cs.getTranslatedChoice(choices.get(i));
            return out;
        }
        return new String[0];
    }

    private static int selectedIndex(Setting<?> setting) {
        if (setting instanceof EnumSetting<?> es) return Math.max(0, es.getModeIndex());
        if (setting instanceof ChoiceSetting cs) return Math.max(0, cs.getChoiceIndex());
        return 0;
    }

    private static void select(Setting<?> setting, int index) {
        if (setting instanceof EnumSetting<?> es) setEnum(es, index);
        else if (setting instanceof ChoiceSetting cs && index >= 0 && index < cs.getChoices().size()) {
            cs.setValue(cs.getChoices().get(index));
        }
    }

    private static <E extends Enum<E>> void setEnum(EnumSetting<E> setting, int index) {
        E[] modes = setting.getModes();
        if (index >= 0 && index < modes.length) setting.setValue(modes[index]);
    }

    private static float fraction(Setting<?> setting) {
        if (setting instanceof IntSetting is) {
            int span = is.getMax() - is.getMin();
            return span <= 0 ? 0f : clamp((is.getValue() - is.getMin()) / (float) span, 0f, 1f);
        }
        if (setting instanceof DoubleSetting ds) {
            double span = ds.getMax() - ds.getMin();
            return span <= 0 ? 0f : (float) clamp((ds.getValue() - ds.getMin()) / span, 0, 1);
        }
        return 0f;
    }

    private static String formatNumber(Setting<?> setting) {
        if (setting instanceof IntSetting is) return Integer.toString(is.getValue());
        if (setting instanceof DoubleSetting ds) {
            double step = ds.getStep();
            int scale = 0;
            if (step > 0 && step < 1) {
                double s = step;
                while (scale < 4 && Math.abs(s - Math.rint(s)) > 1e-6) {
                    s *= 10;
                    scale++;
                }
            }
            return String.format(java.util.Locale.ROOT, "%." + scale + "f", ds.getValue());
        }
        return "";
    }

    private static int mix(int a, int b, float t) {
        t = clamp(t, 0f, 1f);
        int aa = (a >>> 24) & 255, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = (b >>> 24) & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return ((int) (aa + (ba - aa) * t) << 24)
                | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(((color >>> 24) & 255) * clamp(alpha, 0f, 1f))));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private enum Kind { MODULE, BOOL, NUMBER, MODE, TEXT }

    private static final class Column {
        final Category category;
        final List<Row> rows = new ArrayList<>();
        final List<Entry> entries = new ArrayList<>();
        float x;
        float y;
        float scroll;
        float scrollTarget;
        float maxScroll;
        float contentH;
        float bodyH;
        float expand = 1f;
        boolean expanded = true;

        Column(Category category) { this.category = category; }
    }

    private static final class Row {
        final Module module;
        boolean expanded;
        Setting<?> open;
        float optionScroll;
        float hover;
        float enabledAnim;
        float expand;

        Row(Module module) {
            this.module = module;
            this.enabledAnim = module.isEnabled() ? 1f : 0f;
        }
    }

    private record Entry(Row row, Setting<?> setting, Kind kind, float top, float height) {}
}
