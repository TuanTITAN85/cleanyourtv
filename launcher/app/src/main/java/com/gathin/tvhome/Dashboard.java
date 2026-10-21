package com.gathin.tvhome;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Two glass cards between the header and the top shelf: the apps used most over the last 7 days, and a
 * system panel (CPU, RAM, disk, uptime, address, Wi-Fi signal, DNS, debugging). Personal build only: the
 * usage card needs usage access, and both cards poll while Home is on screen.
 *
 * The system card refreshes every 5 s while shown; usage is read on a background thread once a minute.
 * Nothing here touches the network.
 */
final class Dashboard {
    private static final long TICK_MS = 5_000L;
    private static final int USAGE_EVERY_TICKS = 60;                 // 60 x 5 s = 5 min
    private static final long MIN_USAGE_GAP_MS = 20_000L;
    private static final int NETWORK_EVERY_TICKS = 3;                // 3 x 5 s = 15 s
    private static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int TOP_APPS = 5;
    /** What WifiInfo reports when there is no signal reading (its own constant is not public API). */
    private static final int NO_RSSI = -127;

    private final Context ctx;
    private final float density;
    private final ViewGroup panels;
    private final boolean reducedMotion;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dashboard-io");
        t.setDaemon(true);
        return t;
    });
    private final Typeface font;

    private boolean shown;
    private boolean usageLoaded;
    private boolean usageBusy;
    private long lastUsageAt;
    private boolean running;
    private int tick;
    private String adbPort = "";

    // usage card
    private TextView usageTotal, usageHint;
    private final TextView[] usageTime = new TextView[TOP_APPS];
    private final TextView[] usageName = new TextView[TOP_APPS];
    private final Bar[] usageBar = new Bar[TOP_APPS];
    private final View[] usageCol = new View[TOP_APPS];

    // system card
    private TextView modelLine;
    private Cell cpu, ram, disk, uptime, ip, wifi, dns, adb;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (shown) {
                refreshSystem(tick % NETWORK_EVERY_TICKS == 0);
                if (!usageLoaded || tick % USAGE_EVERY_TICKS == 0) refreshUsage();
            }
            tick++;
            handler.postDelayed(this, TICK_MS);
        }
    };

    Dashboard(Context ctx, ViewGroup panels, boolean reducedMotion) {
        this.ctx = ctx;
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.panels = panels;
        this.reducedMotion = reducedMotion;
        this.font = ctx.getResources().getFont(R.font.outfit);
        buildUsageCard();
        buildSystemCard();
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    void start() {
        if (running) return;
        running = true;
        tick = 0;
        usageLoaded = false;                                // back from an app: refresh on the next show
        io.execute(() -> {                                  // `getprop` spawns a process: once, off the UI thread
            String port = readProp("service.adb.tcp.port");
            handler.post(() -> adbPort = port);
        });
        handler.post(ticker);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(ticker);
    }

    void destroy() {
        stop();
        io.shutdownNow();
    }

    /** Fades the cards with the top shelf: they are only on screen while a dock tile has focus. */
    void setShown(boolean show, boolean instant) {
        if (show == shown && !instant) return;
        shown = show;
        panels.animate().cancel();
        if (instant || reducedMotion) {
            panels.setAlpha(show ? 1f : 0f);
        } else {
            panels.animate().alpha(show ? 1f : 0f).setDuration(show ? 260 : 160).start();
        }
        if (show && running) {
            refreshSystem(true);
            if (!usageLoaded) refreshUsage();
        }
    }

    // ---- system card -----------------------------------------------------------------------------

    private void refreshSystem(boolean withNetwork) {
        int cores = Runtime.getRuntime().availableProcessors();

        long mhz = cpuMhz(cores);
        if (mhz > 0) {
            cpu.set(String.format(Locale.US, "%.2f GHz", mhz / 1000f), ctx.getString(R.string.dash_cpu_cores, cores), -1f);
        } else {
            cpu.set(String.valueOf(cores), ctx.getString(R.string.dash_cpu_cores_only), -1f);
        }
        modelLine.setText(Build.MODEL + " · Android " + Build.VERSION.RELEASE + " · " + cores + "×CPU");

        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        long ramUsed = mi.totalMem - mi.availMem;
        ram.set(pct(ramUsed, mi.totalMem), "RAM " + gb(ramUsed) + "/" + gb(mi.totalMem) + "G", frac(ramUsed, mi.totalMem));

        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long diskTotal = fs.getTotalBytes(), diskUsed = diskTotal - fs.getAvailableBytes();
        disk.set(pct(diskUsed, diskTotal), "DISK " + gb(diskUsed) + "/" + gb(diskTotal) + "G", frac(diskUsed, diskTotal));

        uptime.set(duration(SystemClock.elapsedRealtime(), true), ctx.getString(R.string.dash_uptime), -1f);

        if (withNetwork) refreshNetwork();

        boolean adbOn = Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
        adb.set(adbOn ? "ON" + (adbPort.isEmpty() ? "" : " :" + adbPort) : "OFF", ctx.getString(R.string.dash_usb_debug), -1f);
        adb.value.setTextColor(adbOn ? 0xFFFFC15A : 0xFFFFFFFF);
    }

    private void refreshNetwork() {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
        NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
        if (lp == null || nc == null) {
            ip.set("—", ctx.getString(R.string.dash_no_network), -1f);
            wifi.set("—", ctx.getString(R.string.dash_no_wifi), -1f);
            dns.set("—", "DNS", -1f);
            return;
        }
        String addr = "—";
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) {
                addr = la.getAddress().getHostAddress();
                break;
            }
        }
        boolean isWifi = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        boolean isEth = nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        ip.set(addr, "IP · " + (isWifi ? "WI-FI" : isEth ? "ETHERNET" : "NET"), -1f);

        if (isWifi) {
            WifiInfo wi = ((WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE)).getConnectionInfo();
            int rssi = wi == null ? NO_RSSI : wi.getRssi();
            int speed = wi == null ? -1 : wi.getLinkSpeed();
            wifi.set(rssi == NO_RSSI ? "—" : rssi + " dBm",
                    speed > 0 ? speed + " MBPS" : ctx.getString(R.string.dash_signal), -1f);
        } else {
            wifi.set("—", isEth ? "ETHERNET" : ctx.getString(R.string.dash_no_wifi), -1f);
        }

        if (lp.isPrivateDnsActive()) {
            String host = lp.getPrivateDnsServerName();
            dns.set(host == null || host.isEmpty() ? ctx.getString(R.string.dash_private) : host, "DNS · DoT", -1f);
        } else {
            dns.set(ctx.getString(R.string.dash_auto), "DNS", -1f);
        }
    }

    /** Highest current core frequency in MHz, or 0 when sysfs is not readable for apps on this build. */
    private static long cpuMhz(int cores) {
        long best = 0;
        for (int i = 0; i < cores; i++) {
            File f = new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            if (!f.canRead()) continue;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line = r.readLine();
                if (line != null) best = Math.max(best, Long.parseLong(line.trim()) / 1000);
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    private static String readProp(String name) {
        try {
            java.lang.Process p = new ProcessBuilder("getprop", name).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                return line == null ? "" : line.trim();
            } finally {
                p.destroy();
            }
        } catch (Exception e) {
            return "";
        }
    }

    // ---- usage card ------------------------------------------------------------------------------

    private static final class Row {
        final String label;
        final long ms;

        Row(String label, long ms) {
            this.label = label;
            this.ms = ms;
        }
    }

    private boolean hasUsageAccess() {
        AppOpsManager aom = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName())
                : aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void refreshUsage() {
        long nowMs = SystemClock.elapsedRealtime();
        // One read at a time, and not more often than every 20 s however often Home is re-entered.
        if (usageBusy || (lastUsageAt != 0 && nowMs - lastUsageAt < MIN_USAGE_GAP_MS)) return;
        usageBusy = true;
        lastUsageAt = nowMs;
        try {
            io.execute(() -> {
                boolean ok = false;
                List<Row> rows = new ArrayList<>();
                long total = 0;
                try {
                    ok = hasUsageAccess();
                    if (ok) total = readUsage(rows);
                } catch (RuntimeException e) {                 // e.g. access revoked while reading
                    ok = false;
                    rows.clear();
                    total = 0;
                }
                final boolean allowed = ok;
                final long sum = total;
                final List<Row> result = rows;
                handler.post(() -> {
                    usageLoaded = true;
                    usageBusy = false;                          // always released, or the card would never refresh again
                    showUsage(allowed, result, sum);
                });
            });
        } catch (RuntimeException e) {                         // executor already shut down
            usageBusy = false;
        }
    }

    /** Fills {@code rows} with the launchable apps used in the last 7 days, most used first; returns their total. */
    private long readUsage(List<Row> rows) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, String> launchable = new HashMap<>();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (!pkg.equals(ctx.getPackageName())) launchable.put(pkg, String.valueOf(ri.loadLabel(pm)));
        }
        long now = System.currentTimeMillis();
        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        Map<String, UsageStats> stats = usm.queryAndAggregateUsageStats(now - WEEK_MS, now);
        long total = 0;
        for (Map.Entry<String, String> e : launchable.entrySet()) {
            UsageStats us = stats.get(e.getKey());
            long ms = us == null ? 0 : us.getTotalTimeInForeground();
            if (ms <= 0) continue;
            rows.add(new Row(e.getValue(), ms));
            total += ms;
        }
        Collections.sort(rows, (x, y) -> Long.compare(y.ms, x.ms));
        return total;
    }

    private void showUsage(boolean allowed, List<Row> rows, long total) {
        usageHint.setVisibility(allowed && !rows.isEmpty() ? View.GONE : View.VISIBLE);
        usageHint.setText(allowed ? ctx.getString(R.string.dash_usage_empty)
                : ctx.getString(R.string.dash_usage_hint, ctx.getPackageName()));
        usageTotal.setText(allowed && total > 0 ? duration(total, false) : "");
        long max = rows.isEmpty() ? 1 : rows.get(0).ms;
        for (int i = 0; i < TOP_APPS; i++) {
            if (!allowed || i >= rows.size()) {
                usageCol[i].setVisibility(View.GONE);
                continue;
            }
            Row r = rows.get(i);
            usageCol[i].setVisibility(View.VISIBLE);
            usageTime[i].setText(duration(r.ms, false));
            usageName[i].setText(r.label);
            usageBar[i].setFraction(frac(r.ms, max));
        }
    }

    // ---- building the cards ----------------------------------------------------------------------

    private void buildUsageCard() {
        LinearLayout card = card(1f, 0);
        LinearLayout head = row();
        TextView title = text(head, ctx.getString(R.string.dash_most_used), 11, 0x8CFFFFFF, 500, 0.18f);
        title.setAllCaps(true);
        ((LinearLayout.LayoutParams) title.getLayoutParams()).weight = 1;
        usageTotal = text(head, "", 12, 0xFFFFC15A, 500, 0.06f);
        card.addView(head);

        LinearLayout cols = row();
        ((LinearLayout.LayoutParams) cols.getLayoutParams()).topMargin = px(20);
        for (int i = 0; i < TOP_APPS; i++) {
            LinearLayout c = new LinearLayout(ctx);
            c.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginEnd(i == TOP_APPS - 1 ? 0 : px(10));
            c.setLayoutParams(lp);
            usageTime[i] = text(c, "", 20, 0xFFFFFFFF, 400, 0f);
            usageName[i] = text(c, "", 12, 0x99FFFFFF, 400, 0.02f);
            usageName[i].setSingleLine(true);
            usageName[i].setEllipsize(android.text.TextUtils.TruncateAt.END);
            usageBar[i] = new Bar(ctx);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(3));
            bp.topMargin = px(8);
            c.addView(usageBar[i], bp);
            usageCol[i] = c;
            cols.addView(c);
        }
        usageHint = text(cols, "", 12, 0x99FFFFFF, 400, 0.02f);
        usageHint.setVisibility(View.VISIBLE);
        usageHint.setText(ctx.getString(R.string.dash_usage_empty));
        card.addView(cols);
        panels.addView(card);
    }

    private void buildSystemCard() {
        LinearLayout card = card(1f, px(24));
        LinearLayout head = row();
        TextView title = text(head, ctx.getString(R.string.dash_system), 11, 0x8CFFFFFF, 500, 0.18f);
        title.setAllCaps(true);
        ((LinearLayout.LayoutParams) title.getLayoutParams()).weight = 1;
        modelLine = text(head, "", 11, 0x99FFFFFF, 400, 0.02f);
        modelLine.setSingleLine(true);
        card.addView(head);

        cpu = new Cell();
        ram = new Cell();
        disk = new Cell();
        uptime = new Cell();
        ip = new Cell();
        wifi = new Cell();
        dns = new Cell();
        adb = new Cell();
        card.addView(cells(10, cpu, ram, disk, uptime));
        card.addView(cells(8, ip, wifi, dns, adb));
        panels.addView(card);
    }

    private LinearLayout cells(int topMarginDp, Cell... cells) {
        LinearLayout r = row();
        ((LinearLayout.LayoutParams) r.getLayoutParams()).topMargin = px(topMarginDp);
        for (int i = 0; i < cells.length; i++) {
            Cell c = cells[i];
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginEnd(i == cells.length - 1 ? 0 : px(10));
            col.setLayoutParams(lp);
            c.value = text(col, "", 16, 0xFFFFFFFF, 400, 0f);
            c.value.setSingleLine(true);
            c.value.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // Long values (an IPv4 address, a DNS host name) shrink to fit the cell instead of being cut.
            c.value.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
            c.label = text(col, "", 10, 0x99FFFFFF, 500, 0.1f);
            c.label.setAllCaps(true);
            c.label.setSingleLine(true);
            c.label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            c.bar = new Bar(ctx);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(3));
            bp.topMargin = px(4);
            c.bar.setVisibility(View.INVISIBLE);
            col.addView(c.bar, bp);
            r.addView(col);
        }
        return r;
    }

    private LinearLayout card(float weight, int marginStart) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.glass_panel);
        c.setPadding(px(16), px(12), px(16), px(12));
        c.setMinimumHeight(px(134));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.setMarginStart(marginStart);
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private TextView text(ViewGroup parent, String s, float sp, int color, int weight, float spacing) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font);
        t.setFontVariationSettings("'wght' " + weight);
        t.setLetterSpacing(spacing);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.START);
        parent.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    // ---- small helpers ---------------------------------------------------------------------------

    private int px(int dp) {
        return Math.round(dp * density);
    }

    private static float frac(long a, long b) {
        return b <= 0 ? 0f : Math.max(0f, Math.min(1f, a / (float) b));
    }

    private static String pct(long a, long b) {
        return b <= 0 ? "—" : Math.round(100f * a / b) + "%";
    }

    private static String gb(long bytes) {
        return String.format(Locale.US, "%.1f", bytes / (1024f * 1024f * 1024f));
    }

    private static String duration(long ms, boolean days) {
        long min = ms / 60_000L, h = min / 60, d = h / 24;
        if (days && d > 0) return d + "d " + (h % 24) + "h";
        if (h > 0) return h + "h " + (min % 60) + "m";
        return min < 1 ? "<1m" : min + "m";
    }

    /**
     * Shrinks the text, from 16sp down to 11sp, until it fits the view's width. Done by measuring: the
     * framework's uniform autosizing did not shrink these single-line, ellipsized values.
     */
    private void fit(TextView tv) {
        float avail = tv.getWidth() - tv.getPaddingLeft() - tv.getPaddingRight();
        if (avail <= 0) {                                     // not laid out yet: try once after the first layout
            tv.post(() -> {
                if (tv.getWidth() > 0) fit(tv);
            });
            return;
        }
        final android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        final float max = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16, dm);
        final float min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, dm);
        final float step = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 0.5f, dm);
        Paint p = new Paint(tv.getPaint());
        String text = tv.getText().toString();
        float size = max;
        while (size > min) {
            p.setTextSize(size);
            if (p.measureText(text) <= avail) break;
            size -= step;
        }
        size = Math.max(min, size);
        // Never set the full size first and shrink later: that shows one frame of cut text on every refresh.
        if (Math.abs(tv.getTextSize() - size) > 0.5f) tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }

    /** One number with a caption under it and an optional thin bar. */
    private final class Cell {
        TextView value, label;
        Bar bar;

        void set(String v, String l, float fraction) {
            if (!v.contentEquals(value.getText())) {          // an unchanged value must not relayout the cell
                value.setText(v);
                fit(value);
            }
            if (!l.contentEquals(label.getText())) label.setText(l);
            if (fraction >= 0f) {
                bar.setVisibility(View.VISIBLE);
                bar.setFraction(fraction);
            }
        }
    }

    /** A 3dp rounded track with a fill, drawn directly (no ProgressBar styling needed). */
    private static final class Bar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float fraction;

        Bar(Context c) {
            super(c);
        }

        void setFraction(float f) {
            if (f != fraction) {
                fraction = f;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight(), r = h / 2f;
            paint.setColor(0x22FFFFFF);
            rect.set(0, 0, getWidth(), h);
            canvas.drawRoundRect(rect, r, r, paint);
            if (fraction > 0f) {
                paint.setColor(0xB3FFFFFF);
                rect.set(0, 0, Math.max(h, getWidth() * fraction), h);
                canvas.drawRoundRect(rect, r, r, paint);
            }
        }
    }
}
