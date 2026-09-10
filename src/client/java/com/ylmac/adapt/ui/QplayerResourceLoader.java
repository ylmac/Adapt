package com.ylmac.adapt.ui;

import io.github.timer_err.qml4j.render.ResourceLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class QplayerResourceLoader implements ResourceLoader {

    private static final String BASE = "/assets/adaptclient/qml/";

    @Override
    public byte[] load(String source) {
        if (source == null) return null;

        try {
            File f = source.startsWith("file:") ? new File(URI.create(source)) : new File(source);
            if (f.isAbsolute() && f.isFile()) {
                return Files.readAllBytes(f.toPath());
            }
        } catch (Exception ignored) {}

        if (source.equals("md3/Core/qmldir")) {
            byte[] real = loadFromClasspath(BASE + "md3/Core/qmldir");
            if (real != null) return real;
            return buildQmldir();
        }

        if (source.startsWith("fonts/")) {
            return loadFromClasspath(BASE + source);
        }

        if (source.startsWith("md3/Core/")) {
            byte[] data = loadFromClasspath(BASE + source);
            if (data != null) return data;
            return loadFromClasspath(BASE + "md3/Core/Controls/" + source.substring("md3/Core/".length()));
        }

        return loadFromClasspath(BASE + source);
    }

    private static byte[] loadFromClasspath(String path) {
        try (InputStream in = QplayerResourceLoader.class.getResourceAsStream(path)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] buildQmldir() {
        StringBuilder sb = new StringBuilder();
        sb.append("singleton Theme 1.0 Theme.qml\n");
        String[] components = {
            "Breadcrumb", "Button", "CanvasBarChart", "CanvasLineChart",
            "CanvasPieChart", "Card", "Checkbox", "Chip",
            "CircularProgress", "ComboBox", "DataTable",
            "DatePicker", "Dialog", "FAB", "IconButton",
            "LinearProgress", "LoadingIndicator", "Menu",
            "NavigationBar", "NavigationDrawer", "NavigationRail", "RadioButton",
            "Ripple", "ScrollBar", "SegmentedButton", "SideSheet", "Slider",
            "Snackbar", "Switch", "Tabs", "TextField", "TimePicker", "ToolTip", "TopAppBar"
        };
        for (String comp : components) {
            sb.append(comp).append(" 1.0 ").append(comp).append(".qml\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}