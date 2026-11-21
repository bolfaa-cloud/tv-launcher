package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Анис ТВ: the current weather as one line under the date ("☁ −3° · облачно"), from Open-Meteo (free, no key, no
 * account). Fetched on a background thread every {@link #REFRESH_MS} while the line is attached; any failure (no
 * network, a bad answer) leaves the last good line, or hides the line until the first one arrives.
 */
final class Weather {
    static final long REFRESH_MS = 30 * 60 * 1000L;
    /** After a failed fetch, try again sooner than the regular refresh. */
    static final long RETRY_MS = 2 * 60 * 1000L;
    private static final int TIMEOUT_MS = 10_000;

    private final TextView view;
    private final String url;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable tick = this::fetch;
    private boolean attached;

    private Weather(TextView view, String latitude, String longitude) {
        this.view = view;
        this.url = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude.trim() + "&longitude=" + longitude.trim()
                + "&current=temperature_2m,weather_code,is_day&timezone=auto";
    }

    /** Starts the line under {@code view}; hidden for good without a place (config.xml latitude and longitude). */
    static void attach(TextView view, String latitude, String longitude) {
        if (view == null) return;   // a layout variant without the line (layout-iw)
        view.setVisibility(View.GONE);
        if (latitude == null || longitude == null || latitude.trim().isEmpty() || longitude.trim().isEmpty()) return;
        Weather weather = new Weather(view, latitude, longitude);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                weather.attached = true;
                weather.main.removeCallbacks(weather.tick);
                weather.main.post(weather.tick);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                weather.attached = false;
                weather.main.removeCallbacks(weather.tick);
            }
        });
        if (view.isAttachedToWindow()) {
            weather.attached = true;
            weather.main.post(weather.tick);
        }
    }

    private void fetch() {
        if (!attached) return;
        worker.execute(() -> {
            String line = null;
            try {
                line = format(new JSONObject(get(url)).getJSONObject("current"));
            } catch (Exception ignored) {
                // offline or a bad answer: keep what is shown
            }
            String result = line;
            main.post(() -> {
                if (result != null) {
                    view.setText(result);
                    view.setVisibility(View.VISIBLE);
                }
                if (attached) main.postDelayed(tick, result != null ? REFRESH_MS : RETRY_MS);
            });
        });
    }

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** "☁ −3° · облачно" from Open-Meteo's "current" object. */
    static String format(JSONObject current) throws Exception {
        long t = Math.round(current.getDouble("temperature_2m"));
        int code = current.getInt("weather_code");
        boolean day = current.optInt("is_day", 1) == 1;
        String temp = (t < 0 ? "−" + (-t) : String.valueOf(t)) + "°";
        return icon(code, day) + "  " + temp + "  ·  " + describe(code);
    }

    /** WMO weather code to a short Russian description. */
    static String describe(int code) {
        switch (code) {
            case 0: return "ясно";
            case 1: return "почти ясно";
            case 2: return "переменная облачность";
            case 3: return "пасмурно";
            case 45: case 48: return "туман";
            case 51: case 53: case 55: return "морось";
            case 56: case 57: return "ледяная морось";
            case 61: return "небольшой дождь";
            case 63: return "дождь";
            case 65: return "сильный дождь";
            case 66: case 67: return "ледяной дождь";
            case 71: return "небольшой снег";
            case 73: return "снег";
            case 75: return "сильный снег";
            case 77: return "снежная крупа";
            case 80: case 81: case 82: return "ливень";
            case 85: case 86: return "снегопад";
            case 95: return "гроза";
            case 96: case 99: return "гроза с градом";
            default: return "";
        }
    }

    /** An emoji for the WMO code; the clear-sky ones follow day and night. */
    static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code == 1 || code == 2) return day ? "⛅" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return "🌧️";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄️";
        if (code >= 95) return "⛈️";
        return "☁️";
    }
}
