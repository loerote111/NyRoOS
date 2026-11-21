package os.nyro.launcher;

import static os.nyro.launcher.Visuals.*;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.os.UserHandle;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HomeActivity extends Activity {
    private static final String TAG = "NyRoLauncher";
    private static final String GOOGLE = "com.google.android.googlequicksearchbox";
    private static final String[] FAVORITES = {GOOGLE, "com.android.camera2", "com.android.gallery3d",
            "com.android.documentsui"};
    private static final String[] DOCK = {"com.android.dialer", "com.android.chrome", "com.android.vending",
            "com.android.settings"};
    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");
    private static final DateTimeFormatter CLOCK_24 = DateTimeFormatter.ofPattern("HH:mm", DeviceMetrics.PT_BR);
    private static final DateTimeFormatter CLOCK_12 = DateTimeFormatter.ofPattern("h:mm", DeviceMetrics.PT_BR);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", DeviceMetrics.PT_BR);
    private static final int CHOOSE_PROJECTS = 20;
    private static final int UNINSTALL_APP = 21;
    private enum Page { HOME, HUB, DRAWER }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService metricsWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService projectReader = Executors.newSingleThreadExecutor();
    private final ExecutorService endpointWorker = Executors.newFixedThreadPool(3);
    private final ExecutorService appWorker = Executors.newSingleThreadExecutor();
    private List<LauncherActivityInfo> installed = List.of();
    private Map<String, LauncherActivityInfo> appsByPackage = Map.of();
    private Map<String, String> labels = Map.of(), searchLabels = Map.of();
    private final Runnable refreshApps = this::loadApps;
    private final LauncherApps.Callback appChanges = new LauncherApps.Callback() {
        private void changed(UserHandle user) {
            if (!Process.myUserHandle().equals(user)) return;
            handler.removeCallbacks(refreshApps);
            handler.postDelayed(refreshApps, 120);
        }
        @Override public void onPackageAdded(String packageName, UserHandle user) { changed(user); }
        @Override public void onPackageRemoved(String packageName, UserHandle user) { changed(user); }
        @Override public void onPackageChanged(String packageName, UserHandle user) { changed(user); }
        @Override public void onPackagesAvailable(String[] names, UserHandle user, boolean replacing) { changed(user); }
        @Override public void onPackagesUnavailable(String[] names, UserHandle user, boolean replacing) { changed(user); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (page == Page.HOME) updateHeader();
            if (page == Page.HUB) updateMetrics();
            handler.postDelayed(this, page == Page.HUB ? 4000 : 5000);
        }
    };

    private SharedPreferences preferences;
    private LauncherApps launcher;
    private LauncherIcons icons;
    private int appsGeneration;
    private View homePage, drawerPage;
    private Visuals.Wallpaper wallpaper;
    private FrameLayout content;
    private Page page = Page.HOME;
    private int projectsGeneration;
    private boolean cpuPending;
    private TextView greeting, clock, date, battery;
    private TextView ramValue, ramDetail, cpuValue, storageValue, storageDetail, ipValue;
    private ProgressBar ramBar, storageBar;
    private LinearLayout projectList;
    private TextView projectSource;
    private GridView drawerGrid;
    private DrawerAdapter drawerAdapter;
    private TextView noApps;
    private EditText drawerQuery;
    private float downX, downY;
    private boolean swiping;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("launcher", MODE_PRIVATE);
        launcher = getSystemService(LauncherApps.class);
        icons = new LauncherIcons(handler, d(48));
        launcher.registerCallback(appChanges, handler);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setNavigationBarContrastEnforced(false);

        FrameLayout root = new FrameLayout(this);
        wallpaper = new Visuals.Wallpaper(this);
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));
        content = new FrameLayout(this);
        content.setFocusableInTouchMode(true);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            var keyboard = insets.getInsets(WindowInsets.Type.ime());
            content.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            return insets;
        });
        setContentView(root);
        loadApps();
        show(Page.HOME, false);
        Log.i(TAG, "Atividade HOME criada");
    }

    @Override protected void onResume() {
        super.onResume();
        if (page == Page.HUB) reloadProjects(false);
        handler.removeCallbacks(tick);
        tick.run();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        launcher.unregisterCallback(appChanges);
        appWorker.shutdownNow();
        icons.close();
        handler.removeCallbacksAndMessages(null);
        metricsWorker.shutdownNow();
        projectReader.shutdownNow();
        endpointWorker.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.hasCategory(Intent.CATEGORY_HOME) && page != Page.HOME) show(Page.HOME, true);
    }

    @Override public void onBackPressed() {
        if (page != Page.HOME) show(Page.HOME, true);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            swiping = false;
        }
        if (swiping) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1
                && !(getCurrentFocus() instanceof EditText)) {
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            Page next = null;
            if (Math.abs(dx) > d(62) && Math.abs(dx) > Math.abs(dy) * 1.45f) {
                if (page == Page.HOME && dx > 0) next = Page.HUB;
                if (page == Page.HUB && dx < 0) next = Page.HOME;
            }
            if (Math.abs(dy) > d(64) && Math.abs(dy) > Math.abs(dx) * 1.4f) {
                if (page == Page.HOME && dy < 0) next = Page.DRAWER;
                if (page == Page.DRAWER && dy > 0 && !drawerGrid.canScrollVertically(-1)) next = Page.HOME;
            }
            if (next != null) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                swiping = true;
                show(next, true);
                return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private void show(Page next, boolean animate) {
        Page previous = page;
        hideKeyboard();
        page = next;
        projectsGeneration++;
        if (content.getChildCount() != 0) content.getChildAt(0).animate().cancel();
        content.removeAllViews();
        wallpaper.showHome(page == Page.HOME);
        View view = switch (page) {
            case HOME -> {
                if (homePage == null || !animate) homePage = home();
                updateHeader();
                yield homePage;
            }
            case HUB -> hub();
            case DRAWER -> {
                if (drawerPage == null) drawerPage = drawer();
                else {
                    if (drawerQuery.length() > 0) drawerQuery.setText("");
                    drawerGrid.setSelection(0);
                }
                yield drawerPage;
            }
        };
        content.addView(view, new FrameLayout.LayoutParams(-1, -1));
        content.requestFocus();
        view.setTranslationX(0);
        view.setTranslationY(0);
        if (animate) {
            if (next == Page.DRAWER || previous == Page.DRAWER) {
                view.setTranslationY(d(next == Page.DRAWER ? 32 : -18));
            } else if (next == Page.HUB || previous == Page.HUB) {
                view.setTranslationX(d(next == Page.HUB ? -32 : 32));
            }
            view.animate().translationX(0).translationY(0).setDuration(165).start();
        }
        Log.i(TAG, "Página " + page);
    }

    private View home() {
        LinearLayout layout = column(24, 15, 24, 12);
        LinearLayout header = row();
        LinearLayout brand = row();
        brand.setBackground(pressable(this, 0x551f1b30, 17, Color.TRANSPARENT));
        ImageView mark = image(getDrawable(R.drawable.nyro_icon));
        brand.addView(mark, size(34, 39));
        TextView name = label("NyRoOS", 16, TEXT, "sans-serif-medium");
        name.setPadding(d(8), 0, d(12), 0);
        brand.addView(name, size(-2, 42));
        brand.setContentDescription("Abrir Hub NyRoOS");
        brand.setOnClickListener(view -> show(Page.HUB, true));
        header.addView(brand);
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        LinearLayout batteryPill = row();
        batteryPill.setPadding(d(10), 0, d(12), 0);
        batteryPill.setBackground(pressable(this, 0x9e262034, 17, 0x545d4a79));
        batteryPill.addView(glyph("battery", ACCENT, 18), size(24, 38));
        battery = label("—", 13, TEXT, "sans-serif-medium");
        battery.setPadding(d(5), 0, 0, 0);
        batteryPill.addView(battery, size(-2, 38));
        batteryPill.setContentDescription("Configurações da bateria");
        batteryPill.setOnClickListener(view -> open(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)));
        header.addView(batteryPill);
        layout.addView(header, size(-1, 43));
        gap(layout, 42);

        greeting = label("", 22, ACCENT, "sans-serif-medium");
        layout.addView(greeting, size(-1, 29));
        clock = label("", 78, TEXT, "sans-serif-light");
        clock.setLetterSpacing(-.062f);
        clock.setIncludeFontPadding(false);
        layout.addView(clock, size(-1, 93));
        date = label("", 16, MUTED, "sans-serif");
        layout.addView(date, size(-1, 27));
        gap(layout, 24);
        layout.addView(homeSearch(), size(-1, 58));
        layout.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));

        LinearLayout favoriteHeader = row();
        TextView favorites = label("Favoritos", 19, TEXT, "sans-serif-medium");
        favoriteHeader.addView(favorites, new LinearLayout.LayoutParams(0, d(44), 1));
        LinearLayout drawerButton = pill("grid", "Todos os apps", () -> show(Page.DRAWER, true));
        favoriteHeader.addView(drawerButton, size(-2, 38));
        layout.addView(favoriteHeader);
        gap(layout, 7);
        layout.addView(slots("favorite", FAVORITES, false), size(-1, 102));
        gap(layout, 13);

        LinearLayout dock = slots("dock", DOCK, true);
        dock.setPadding(d(8), d(1), d(8), d(1));
        dock.setBackground(surface(this, 0xe8221e30, 30, 0x665e4e78));
        layout.addView(dock, size(-1, 83));
        updateHeader();
        return layout;
    }

    private View homeSearch() {
        LinearLayout search = row();
        search.setPadding(d(10), 0, d(14), 0);
        search.setBackground(surface(this, 0xe6242031, 25, 0xff4b3d60));
        LauncherActivityInfo google = app(GOOGLE);
        View icon = google == null ? glyph("search", ACCENT, 20) : appIcon(google);
        search.addView(icon, size(36, 50));
        icon.setContentDescription("Abrir app Google");
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        icon.setOnClickListener(view -> launch(google));
        EditText query = edit("Pesquisar no Google");
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            String value = query.getText().toString().trim();
            if (!value.isEmpty()) googleSearch(value);
            return true;
        });
        LinearLayout.LayoutParams field = new LinearLayout.LayoutParams(0, d(52), 1);
        field.leftMargin = d(9);
        search.addView(query, field);
        View action = glyph("search", ACCENT, 21);
        action.setContentDescription("Pesquisar");
        action.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        action.setOnClickListener(view -> {
            String value = query.getText().toString().trim();
            if (!value.isEmpty()) googleSearch(value);
            else {
                query.requestFocus();
                getSystemService(InputMethodManager.class).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        search.addView(action, size(39, 52));
        return search;
    }

    private void updateHeader() {
        if (clock == null || page != Page.HOME) return;
        LocalDateTime now = LocalDateTime.now();
        int hour = now.getHour();
        setTextIfChanged(greeting, hour < 12 ? "Bom dia" : hour < 18 ? "Boa tarde" : "Boa noite");
        setTextIfChanged(clock, now.format(DateFormat.is24HourFormat(this) ? CLOCK_24 : CLOCK_12));
        String today = now.format(DATE);
        setTextIfChanged(date, Character.toUpperCase(today.charAt(0)) + today.substring(1));
        int percent = getSystemService(BatteryManager.class).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        setTextIfChanged(battery, percent >= 0 && percent <= 100 ? percent + "%" : "—");
    }

    private LinearLayout slots(String prefix, String[] defaults, boolean dock) {
        LinearLayout line = row();
        for (int index = 0; index < defaults.length; index++) {
            int slot = index;
            LauncherActivityInfo item = slotApp(prefix + index, defaults[index]);
            View tile = appTile(item, dock ? "dock" : "favorite", () -> launch(item),
                    () -> slotMenu(prefix, slot, item));
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1);
            if (!dock && index > 0) cell.leftMargin = d(8);
            line.addView(tile, cell);
        }
        return line;
    }

    private View appTile(LauncherActivityInfo item, String variant, Runnable tap, Runnable hold) {
        boolean favorite = variant.equals("favorite");
        boolean dock = variant.equals("dock");
        LinearLayout tile = column(2, 3, 2, 3);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(pressable(this, favorite ? CARD : Color.TRANSPARENT, favorite ? 22 : 18,
                favorite ? 0xff3d3553 : Color.TRANSPARENT));
        int tileSize = favorite ? 48 : dock ? 38 : 48;
        FrameLayout box = new FrameLayout(this);
        if (!dock) box.setBackground(surface(this, favorite ? 0xff242236 : CARD, 16, Color.TRANSPARENT));
        View icon = item == null ? glyph("grid", MUTED, 24) : appIcon(item);
        box.addView(icon, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        tile.addView(box, size(tileSize, tileSize));
        String title = item == null ? "Selecionar" : appLabel(item);
        TextView text = label(title, dock ? 10.5f : 11.5f, dock ? MUTED : TEXT, "sans-serif-medium");
        text.setGravity(Gravity.CENTER);
        text.setMaxLines(1);
        text.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams textSize = size(-1, 22);
        textSize.topMargin = d(4);
        tile.addView(text, textSize);
        tile.setContentDescription(title);
        tile.setOnClickListener(view -> { if (item == null) hold.run(); else tap.run(); });
        tile.setOnLongClickListener(view -> { hold.run(); return true; });
        return tile;
    }

    private View hub() {
        LinearLayout layout = column(24, 15, 24, 9);
        layout.addView(pageBar("Central", "Atualizar", "refresh", () -> {
            updateMetrics();
            reloadProjects(true);
        }), size(-1, 42));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = column(0, 24, 0, 12);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        TextView title = label("Hub NyRoOS", 35, TEXT, "sans-serif-medium");
        title.setLetterSpacing(-.033f);
        body.addView(title, size(-1, 54));
        gap(body, 12);

        LinearLayout system = row();
        system.setPadding(d(15), 0, d(10), 0);
        system.setBackground(pressable(this, 0xff211c30, 25, 0xff42355a));
        system.addView(image(getDrawable(R.drawable.nyro_icon)), size(51, 65));
        LinearLayout version = column(11, 0, 0, 0);
        version.setGravity(Gravity.CENTER_VERTICAL);
        String identity = Build.DISPLAY.startsWith("NyRoOS ") ? Build.DISPLAY : "NyRoOS";
        version.addView(label(identity, 17, TEXT, "sans-serif-medium"), size(-1, 27));
        version.addView(label("Android " + Build.VERSION.RELEASE + "  ·  " + Build.SUPPORTED_ABIS[0],
                12, MUTED, "sans-serif"), size(-1, 22));
        system.addView(version, new LinearLayout.LayoutParams(0, -1, 1));
        system.addView(glyph("right", ACCENT, 20), size(38, 62));
        system.setContentDescription(identity + ", sobre o sistema");
        system.setOnClickListener(view -> open(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)));
        body.addView(system, size(-1, 86));
        gap(body, 14);

        LinearLayout first = row();
        LinearLayout ram = metric("RAM", "chip");
        ramValue = metricValue(ram, "—");
        ramDetail = metricDetail(ram, "");
        ramBar = meter(ram);
        metricCell(first, ram, false, () -> open(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)));
        LinearLayout cpu = metric("CPU", "code");
        cpuValue = metricValue(cpu, "—");
        metricDetail(cpu, Runtime.getRuntime().availableProcessors() + " núcleos disponíveis");
        metricCell(first, cpu, true, this::updateMetrics);
        body.addView(first, size(-1, 119));
        gap(body, 10);

        LinearLayout second = row();
        LinearLayout storage = metric("Armazenamento", "disk");
        storageValue = metricValue(storage, "—");
        storageDetail = metricDetail(storage, "");
        storageBar = meter(storage);
        metricCell(second, storage, false, () -> open(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)));
        LinearLayout ip = metric("IP local", "network");
        ipValue = metricValue(ip, "—");
        ipValue.setTextSize(20);
        metricDetail(ip, "Rede ativa");
        metricCell(second, ip, true, () -> open(new Intent(Settings.ACTION_WIRELESS_SETTINGS)));
        body.addView(second, size(-1, 119));

        gap(body, 17);
        LinearLayout projects = row();
        projects.addView(label("Projetos", 21, TEXT, "sans-serif-medium"),
                new LinearLayout.LayoutParams(0, d(44), 1));
        projects.addView(pill("link", "Conectar JSON", this::chooseProjects), size(-2, 37));
        body.addView(projects);
        projectSource = label("", 12, MUTED, "sans-serif");
        body.addView(projectSource, size(-1, -2));
        gap(body, 10);
        projectList = column(0, 0, 0, 0);
        body.addView(projectList, size(-1, -2));
        updateMetrics();
        reloadProjects(false);
        return layout;
    }

    private LinearLayout metric(String name, String symbol) {
        LinearLayout card = column(13, 9, 12, 7);
        card.setBackground(pressable(this, CARD, 22, LINE));
        LinearLayout top = row();
        top.addView(glyph(symbol, ACCENT, 17), size(21, 25));
        TextView title = label(name, 12.5f, MUTED, "sans-serif-medium");
        title.setPadding(d(5), 0, 0, 0);
        title.setSingleLine(true);
        top.addView(title, new LinearLayout.LayoutParams(0, d(25), 1));
        card.addView(top, size(-1, 27));
        return card;
    }

    private TextView metricValue(LinearLayout card, String value) {
        TextView text = label(value, 25, TEXT, "sans-serif-medium");
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        card.addView(text, size(-1, 37));
        return text;
    }

    private TextView metricDetail(LinearLayout card, String value) {
        TextView text = label(value, 11.5f, MUTED, "sans-serif");
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(text, size(-1, 21));
        return text;
    }

    private ProgressBar meter(LinearLayout card) {
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff343046));
        progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(progress, size(-1, 7));
        return progress;
    }

    private void metricCell(LinearLayout row, LinearLayout metric, boolean second, Runnable open) {
        LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1);
        if (second) cell.leftMargin = d(10);
        metric.setOnClickListener(view -> open.run());
        row.addView(metric, cell);
    }

    private void updateMetrics() {
        if (page != Page.HUB || ramValue == null || cpuPending) return;
        cpuPending = true;
        TextView destination = cpuValue;
        metricsWorker.execute(() -> {
            try {
                ActivityManager.MemoryInfo memory = DeviceMetrics.memory(this);
                StatFs disk = DeviceMetrics.storage();
                long used = memory.totalMem - memory.availMem;
                long free = disk.getAvailableBytes();
                long total = disk.getTotalBytes();
                String address = DeviceMetrics.address(this);
                handler.post(() -> {
                    if (page != Page.HUB || cpuValue != destination || isDestroyed()) return;
                    int ramPercent = percent(used, memory.totalMem);
                    setTextIfChanged(ramValue, ramPercent + "%");
                    setTextIfChanged(ramDetail, DeviceMetrics.gib(used) + " de " + DeviceMetrics.gib(memory.totalMem));
                    ramBar.setProgress(ramPercent, true);
                    setTextIfChanged(storageValue, DeviceMetrics.gib(free));
                    setTextIfChanged(storageDetail, "livres de " + DeviceMetrics.gib(total));
                    storageBar.setProgress(percent(total - free, total), true);
                    setTextIfChanged(ipValue, address);
                });
                String cpu = DeviceMetrics.cpu();
                handler.post(() -> {
                    if (page == Page.HUB && cpuValue == destination && !isDestroyed()) {
                        setTextIfChanged(destination, cpu);
                    }
                });
            } catch (RuntimeException error) {
                Log.w(TAG, "Métricas indisponíveis", error);
            } finally {
                handler.post(() -> cpuPending = false);
            }
        });
    }

    private int percent(long part, long whole) {
        return whole > 0 ? (int) Math.round(100d * part / whole) : 0;
    }

    private void chooseProjects() {
        Uri folder = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:NyRoOS");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder);
        try {
            startActivityForResult(intent, CHOOSE_PROJECTS);
        } catch (RuntimeException e) {
            notice("Seletor de arquivos indisponível");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == UNINSTALL_APP) {
            loadApps();
            return;
        }
        if (request != CHOOSE_PROJECTS || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri file = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(file, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String old = preferences.getString("projects", null);
            if (old != null && !old.equals(file.toString())) {
                try {
                    getContentResolver().releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // Access to the previous file may have been revoked.
                }
            }
            preferences.edit().putString("projects", file.toString()).apply();
            if (page == Page.HUB) reloadProjects(false);
        } catch (SecurityException e) {
            notice("O seletor não concedeu acesso ao arquivo");
        }
    }

    private void reloadProjects(boolean announce) {
        int generation = ++projectsGeneration;
        String saved = preferences.getString("projects", null);
        Uri document = saved == null ? null : Uri.parse(saved);
        projectSource.setText(document == null ? "Exemplos · selecione " + Projects.PATH : Projects.PATH);
        projectReader.execute(() -> {
            try {
                List<Projects.Card> cards = Projects.read(this, document);
                handler.post(() -> {
                    if (page != Page.HUB || generation != projectsGeneration || isDestroyed()) return;
                    populateProjects(cards, generation);
                    if (announce) notice("Hub atualizado");
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (page != Page.HUB || generation != projectsGeneration || isDestroyed()) return;
                    projectList.removeAllViews();
                    projectList.addView(label("Não foi possível ler o JSON. Selecione o arquivo novamente.",
                            14, MUTED, "sans-serif"), size(-1, -2));
                });
            }
        });
    }

    private void populateProjects(List<Projects.Card> cards, int generation) {
        projectList.removeAllViews();
        if (cards.isEmpty()) {
            projectList.addView(label("Adicione atalhos ao arquivo para exibi-los aqui.", 14, MUTED,
                    "sans-serif"), size(-1, -2));
        }
        for (Projects.Card card : cards) {
            LinearLayout tile = row();
            tile.setPadding(d(11), 0, d(8), 0);
            tile.setBackground(pressable(this, CARD, 19, LINE));
            boolean api = card.type().equals("api");
            boolean app = card.type().equals("app");
            View symbol = glyph(api ? "code" : app ? "grid" : "globe", ACCENT, 19);
            symbol.setBackground(surface(this, 0xff2c2441, 13, Color.TRANSPARENT));
            tile.addView(symbol, size(39, 39));
            LinearLayout info = column(11, 0, 4, 0);
            info.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = label(card.name(), 14.5f, TEXT, "sans-serif-medium");
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(title, size(-1, 25));
            String secondary = app ? "Abrir app" : Uri.parse(card.target()).getHost();
            TextView state = label(api ? "Verificando…" : secondary, 11.5f, MUTED, "sans-serif");
            state.setSingleLine(true);
            state.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(state, size(-1, 18));
            tile.addView(info, new LinearLayout.LayoutParams(0, d(58), 1));
            tile.addView(glyph(api ? "refresh" : "right", ACCENT, 17), size(36, 48));
            tile.setOnClickListener(view -> {
                if (api) checkEndpoint(card, state, generation);
                else if (app) launch(app(card.target()));
                else open(new Intent(Intent.ACTION_VIEW, Uri.parse(card.target())));
            });
            LinearLayout.LayoutParams params = size(-1, 65);
            if (projectList.getChildCount() > 0) params.topMargin = d(8);
            projectList.addView(tile, params);
            if (api) checkEndpoint(card, state, generation);
        }
    }

    private void checkEndpoint(Projects.Card card, TextView state, int generation) {
        if (Boolean.TRUE.equals(state.getTag())) return;
        state.setTag(true);
        state.setText("Verificando…");
        state.setTextColor(MUTED);
        endpointWorker.execute(() -> {
            Projects.Check check = Projects.check(card);
            handler.post(() -> {
                if (generation != projectsGeneration || page != Page.HUB || isDestroyed()) return;
                state.setTag(false);
                state.setText(check.text());
                state.setTextColor(check.healthy() ? SUCCESS : 0xfff0b8c9);
            });
        });
    }

    private View drawer() {
        LinearLayout layout = column(24, 15, 24, 12);
        layout.addView(pageBar("Início", "Opções da gaveta", "more", this::drawerMenu), size(-1, 42));
        gap(layout, 23);
        TextView title = label("Aplicativos", 35, TEXT, "sans-serif-medium");
        title.setLetterSpacing(-.033f);
        layout.addView(title, size(-1, 54));
        gap(layout, 12);

        FrameLayout list = new FrameLayout(this);
        drawerGrid = new GridView(this);
        drawerGrid.setNumColumns(4);
        drawerGrid.setHorizontalSpacing(d(5));
        drawerGrid.setVerticalSpacing(d(7));
        drawerGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        drawerGrid.setClipToPadding(false);
        drawerGrid.setVerticalScrollBarEnabled(false);
        drawerGrid.setSelector(android.R.color.transparent);
        drawerAdapter = new DrawerAdapter();
        drawerGrid.setAdapter(drawerAdapter);
        list.addView(drawerGrid, new FrameLayout.LayoutParams(-1, -1));
        noApps = label("Nenhum app encontrado", 15, MUTED, "sans-serif");
        noApps.setGravity(Gravity.CENTER);
        list.addView(noApps, new FrameLayout.LayoutParams(-1, -1));
        layout.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        gap(layout, 13);
        LinearLayout search = row();
        search.setPadding(d(10), 0, d(9), 0);
        search.setBackground(surface(this, 0xff231f32, 25, 0xff4b3d62));
        search.addView(glyph("search", ACCENT, 21), size(39, 56));
        drawerQuery = edit("Buscar aplicativos");
        drawerQuery.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        drawerQuery.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            hideKeyboard();
            return true;
        });
        drawerQuery.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { filterDrawer(); }
            @Override public void afterTextChanged(Editable text) {}
        });
        search.addView(drawerQuery, new LinearLayout.LayoutParams(0, d(56), 1));
        TextView clear = label("×", 24, ACCENT, "sans-serif");
        clear.setGravity(Gravity.CENTER);
        clear.setContentDescription("Limpar pesquisa");
        clear.setOnClickListener(view -> drawerQuery.setText(""));
        search.addView(clear, size(40, 50));
        layout.addView(search, size(-1, 60));
        filterDrawer();
        return layout;
    }

    private void filterDrawer() {
        if (drawerAdapter == null || drawerQuery == null) return;
        String query = fold(drawerQuery.getText().toString().trim());
        Set<String> hidden = hidden();
        drawerAdapter.items.clear();
        for (LauncherActivityInfo item : installed) {
            String packageName = item.getComponentName().getPackageName();
            if (hidden.contains(packageName)) continue;
            if (query.isEmpty() || searchLabels.getOrDefault(packageName, "").contains(query)) {
                drawerAdapter.items.add(item);
            }
        }
        drawerAdapter.notifyDataSetChanged();
        noApps.setVisibility(drawerAdapter.items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private final class DrawerAdapter extends BaseAdapter {
        final List<LauncherActivityInfo> items = new ArrayList<>();

        @Override public int getCount() { return items.size(); }
        @Override public LauncherActivityInfo getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            DrawerTile tile = recycled instanceof DrawerTile existing ? existing : new DrawerTile();
            tile.bind(getItem(position));
            return tile;
        }
    }

    private final class DrawerTile extends LinearLayout {
        private final ImageView icon;
        private final TextView title;
        private LauncherActivityInfo item;

        DrawerTile() {
            super(HomeActivity.this);
            setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, d(87)));
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setPadding(d(2), d(3), d(2), d(3));
            setBackground(pressable(HomeActivity.this, Color.TRANSPARENT, 18, Color.TRANSPARENT));
            FrameLayout box = new FrameLayout(HomeActivity.this);
            box.setBackground(surface(HomeActivity.this, CARD, 16, Color.TRANSPARENT));
            icon = image(null);
            box.addView(icon, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            addView(box, size(48, 48));
            title = label("", 11.5f, TEXT, "sans-serif-medium");
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            var textSize = size(-1, 22);
            textSize.topMargin = d(4);
            addView(title, textSize);
            setOnClickListener(view -> launch(item));
            setOnLongClickListener(view -> { appMenu(item); return true; });
        }

        void bind(LauncherActivityInfo app) {
            item = app;
            String name = appLabel(app);
            setTextIfChanged(title, name);
            setContentDescription(name);
            icons.bind(icon, app);
        }
    }

    private void appMenu(LauncherActivityInfo item) {
        ApplicationInfo app = item.getApplicationInfo();
        boolean userApp = (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
        List<String> options = new ArrayList<>(List.of("Ocultar da gaveta", "Adicionar aos favoritos", "Informações do app"));
        if (userApp) options.add("Desinstalar");
        new AlertDialog.Builder(this).setTitle(appLabel(item))
                .setItems(options.toArray(String[]::new), (dialog, option) -> {
                    switch (option) {
                        case 0 -> {
                            Set<String> updated = hidden();
                            updated.add(app.packageName);
                            preferences.edit().putStringSet("hidden", updated).apply();
                            filterDrawer();
                        }
                        case 1 -> chooseSlot(item);
                        case 2 -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", app.packageName, null)));
                        case 3 -> requestUninstall(app.packageName);
                    }
                }).show();
    }

    private void requestUninstall(String packageName) {
        Intent action = new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true);
        try {
            startActivityForResult(action, UNINSTALL_APP);
        } catch (RuntimeException e) {
            notice("Desinstalador do Android indisponível");
        }
    }

    private void drawerMenu() {
        new AlertDialog.Builder(this).setTitle("Gaveta de aplicativos")
                .setItems(new String[]{"Restaurar apps ocultos"}, (dialog, option) -> restoreHidden())
                .show();
    }

    private void restoreHidden() {
        Set<String> hidden = hidden();
        List<LauncherActivityInfo> items = new ArrayList<>();
        for (LauncherActivityInfo item : installed) {
            if (hidden.contains(item.getApplicationInfo().packageName)) items.add(item);
        }
        if (items.isEmpty()) {
            notice("Nenhum app oculto");
            return;
        }
        String[] labels = items.stream().map(this::appLabel).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Restaurar app")
                .setItems(labels, (dialog, choice) -> {
                    hidden.remove(items.get(choice).getApplicationInfo().packageName);
                    preferences.edit().putStringSet("hidden", hidden).apply();
                    filterDrawer();
                }).show();
    }

    private Set<String> hidden() {
        return new LinkedHashSet<>(preferences.getStringSet("hidden", Collections.emptySet()));
    }

    private void slotMenu(String prefix, int index, LauncherActivityInfo current) {
        String[] names = installed.stream().map(this::appLabel).toArray(String[]::new);
        String title = prefix.equals("dock") ? "Trocar atalho do dock" : "Trocar favorito";
        new AlertDialog.Builder(this).setTitle(title).setItems(names, (dialog, choice) -> {
            String chosen = installed.get(choice).getApplicationInfo().packageName;
            preferences.edit().putString(prefix + index, chosen).apply();
            show(Page.HOME, false);
        }).show();
    }

    private void chooseSlot(LauncherActivityInfo item) {
        String[] names = new String[FAVORITES.length];
        for (int index = 0; index < names.length; index++) {
            LauncherActivityInfo current = slotApp("favorite" + index, FAVORITES[index]);
            names[index] = (index + 1) + " · " + (current == null ? "Livre" : appLabel(current));
        }
        new AlertDialog.Builder(this).setTitle("Substituir favorito")
                .setItems(names, (dialog, choice) -> {
                    preferences.edit().putString("favorite" + choice, item.getApplicationInfo().packageName).apply();
                    notice("Favorito atualizado");
                }).show();
    }

    private void loadApps() {
        int generation = ++appsGeneration;
        appWorker.execute(() -> {
            try {
                Map<String, LauncherActivityInfo> byPackage = new LinkedHashMap<>();
                for (LauncherActivityInfo item : launcher.getActivityList(null, Process.myUserHandle())) {
                    ComponentName component = item.getComponentName();
                    String name = component.getPackageName();
                    if (name.equals(getPackageName()) || name.equals("com.android.launcher3")) continue;
                    if (name.equals(GOOGLE) && component.getShortClassName().equals(".SearchActivity")) {
                        byPackage.put(name, item);
                    } else {
                        byPackage.putIfAbsent(name, item);
                    }
                }
                Map<String, String> names = new HashMap<>();
                Map<String, String> search = new HashMap<>();
                byPackage.forEach((pkg, item) -> {
                    String label = fetchAppLabel(item);
                    names.put(pkg, label);
                    search.put(pkg, fold(label) + "\n" + fold(pkg));
                });
                List<LauncherActivityInfo> items = new ArrayList<>(byPackage.values());
                Collator order = Collator.getInstance(DeviceMetrics.PT_BR);
                items.sort((first, second) -> order.compare(names.get(first.getComponentName().getPackageName()),
                        names.get(second.getComponentName().getPackageName())));
                handler.post(() -> {
                    if (generation != appsGeneration || isDestroyed()) return;
                    installed = items;
                    appsByPackage = byPackage;
                    labels = names;
                    searchLabels = search;
                    icons.clear();
                    homePage = null;
                    if (drawerPage != null) filterDrawer();
                    if (page == Page.HOME) show(Page.HOME, false);
                });
            } catch (RuntimeException error) {
                Log.w(TAG, "Não foi possível atualizar a lista de apps", error);
            }
        });
    }

    private LauncherActivityInfo app(String packageName) {
        return appsByPackage.get(packageName);
    }

    private LauncherActivityInfo slotApp(String key, String defaultPackage) {
        if (preferences.contains(key)) return app(preferences.getString(key, ""));
        LauncherActivityInfo preferred = app(defaultPackage);
        if (preferred != null) return preferred;
        return switch (defaultPackage) {
            case "com.android.dialer" -> app("com.samsung.android.dialer");
            case "com.android.camera2" -> app("com.sec.android.app.camera");
            case "com.android.gallery3d" -> app("com.sec.android.gallery3d");
            case "com.android.documentsui" -> app("com.sec.android.app.myfiles");
            default -> null;
        };
    }

    private String appLabel(LauncherActivityInfo item) {
        return labels.getOrDefault(item.getComponentName().getPackageName(), item.getComponentName().getPackageName());
    }

    private String fetchAppLabel(LauncherActivityInfo item) {
        return switch (item.getComponentName().getPackageName()) {
            case "com.android.dialer", "com.samsung.android.dialer" -> "Telefone";
            case "com.android.camera2", "com.sec.android.app.camera" -> "Câmera";
            case "com.android.gallery3d", "com.sec.android.gallery3d" -> "Galeria";
            case "com.android.documentsui", "com.sec.android.app.myfiles" -> "Arquivos";
            case "com.android.settings" -> "Configurações";
            case "com.android.contacts" -> "Contatos";
            case "com.android.deskclock" -> "Relógio";
            case "com.android.calculator2" -> "Calculadora";
            case "com.android.calendar" -> "Calendário";
            case "com.android.messaging" -> "Mensagens";
            case "com.android.soundrecorder" -> "Gravador";
            case GOOGLE -> "Google";
            default -> item.getLabel().toString();
        };
    }

    private void launch(LauncherActivityInfo app) {
        if (app == null) {
            notice("Aplicativo não disponível");
            return;
        }
        hideKeyboard();
        try {
            launcher.startMainActivity(app.getComponentName(), app.getUser(), null, null);
        } catch (RuntimeException e) {
            notice("Não foi possível abrir " + appLabel(app));
        }
    }

    private void googleSearch(String query) {
        hideKeyboard();
        Intent search = new Intent(Intent.ACTION_WEB_SEARCH).setPackage(GOOGLE)
                .putExtra(SearchManager.QUERY, query);
        if (search.resolveActivity(getPackageManager()) != null) {
            Log.i(TAG, "Pesquisa encaminhada ao Google App");
            open(search);
        } else {
            Uri target = Uri.parse("https://www.google.com/search").buildUpon()
                    .appendQueryParameter("q", query).build();
            open(new Intent(Intent.ACTION_VIEW, target));
        }
    }

    private String fold(String value) {
        return ACCENTS.matcher(Normalizer.normalize(value, Normalizer.Form.NFD))
                .replaceAll("").toLowerCase(DeviceMetrics.PT_BR);
    }

    private void open(Intent intent) {
        hideKeyboard();
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            notice("Ação indisponível");
        }
    }

    private void hideKeyboard() {
        if (content == null || !(getCurrentFocus() instanceof EditText)) return;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(content.getWindowToken(), 0);
        content.requestFocus();
    }

    private void notice(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private LinearLayout pageBar(String back, String action, String symbol, Runnable run) {
        LinearLayout row = row();
        row.addView(pill("left", back, () -> show(Page.HOME, true)), size(-2, 39));
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (symbol.equals("more")) {
            View menu = glyph("more", ACCENT, 21);
            menu.setBackground(pressable(this, PANEL, 17, LINE));
            menu.setContentDescription(action);
            menu.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            menu.setOnClickListener(view -> run.run());
            row.addView(menu, size(46, 43));
        } else {
            row.addView(pill(symbol, action, run), size(-2, 39));
        }
        return row;
    }

    private LinearLayout pill(String symbol, String text, Runnable action) {
        LinearLayout pill = row();
        pill.setPadding(d(9), 0, d(12), 0);
        pill.setBackground(pressable(this, PANEL, 16, LINE));
        pill.addView(glyph(symbol, ACCENT, 17), size(25, -1));
        TextView label = label(text, 12.5f, TEXT, "sans-serif-medium");
        label.setPadding(d(3), 0, 0, 0);
        pill.addView(label, size(-2, -1));
        pill.setContentDescription(text);
        pill.setOnClickListener(view -> action.run());
        return pill;
    }

    private EditText edit(String hint) {
        EditText field = new EditText(this);
        field.setBackgroundColor(Color.TRANSPARENT);
        field.setPadding(0, 0, 0, 0);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setTextColor(TEXT);
        field.setTextSize(16);
        field.setHintTextColor(MUTED);
        field.setHint(hint);
        return field;
    }

    private TextView label(String text, float scale, int color, String font) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(scale);
        label.setTextColor(color);
        label.setTypeface(Typeface.create(font, Typeface.NORMAL));
        label.setGravity(Gravity.CENTER_VERTICAL);
        return label;
    }

    private void setTextIfChanged(TextView view, CharSequence value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    private ImageView appIcon(LauncherActivityInfo item) {
        ImageView view = image(null);
        icons.bind(view, item);
        return view;
    }

    private ImageView image(Drawable source) {
        ImageView image = new ImageView(this);
        image.setImageDrawable(source);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(d(4), d(4), d(4), d(4));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    private Visuals.Glyph glyph(String symbol, int color, int size) {
        return new Visuals.Glyph(this, symbol, color, size);
    }

    private LinearLayout column(int left, int top, int right, int bottom) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(d(left), d(top), d(right), d(bottom));
        return list;
    }

    private LinearLayout row() {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        return line;
    }

    private void gap(LinearLayout parent, int height) {
        parent.addView(new View(this), size(1, height));
    }

    private LinearLayout.LayoutParams size(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : d(width), height < 0 ? height : d(height));
    }

    private int d(float value) {
        return dp(this, value);
    }
}
