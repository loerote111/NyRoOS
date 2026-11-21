package os.nyro.launcher;

import static os.nyro.launcher.LauncherPreferences.Option.*;
import static os.nyro.launcher.Visuals.*;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public final class NyRoSettingsActivity extends Activity {
    private SharedPreferences preferences;
    private TextView hiddenSummary;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = LauncherPreferences.get(this);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setNavigationBarContrastEnforced(false);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });

        LinearLayout page = column();
        page.setPadding(d(22), d(13), d(22), d(24));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);

        View back = new Glyph(this, "left", TEXT, 23);
        back.setBackground(pressable(this, PANEL, 16, LINE));
        back.setContentDescription("Voltar");
        back.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        back.setOnClickListener(view -> finish());
        page.addView(back, size(48, 48));

        LinearLayout heading = row();
        heading.setPadding(0, d(21), 0, d(20));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.nyro_settings);
        heading.addView(icon, size(53, 53));
        LinearLayout titles = column();
        titles.setPadding(d(13), 0, 0, 0);
        titles.addView(label("NyRoOS", 13, ACCENT, false));
        TextView title = label("Configurações", 29, TEXT, true);
        title.setAccessibilityHeading(true);
        titles.addView(title);
        heading.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(heading, size(-1, -2));

        LinearLayout home = section(page, "Tela inicial");
        for (var option : new LauncherPreferences.Option[]{GREETING, CLOCK, DATE, BATTERY, SEARCH, FAVORITES, DOCK}) {
            option(home, option);
        }

        LinearLayout hub = section(page, "Central");
        option(hub, METRICS);
        option(hub, DEVICE);
        option(hub, PROJECTS);

        LinearLayout drawer = section(page, "Gaveta");
        hiddenSummary = action(drawer, "Apps ocultos", "", "eye-off", () ->
                showHidden(this, preferences, this::updateHiddenSummary));
        updateHiddenSummary();

        LinearLayout system = section(page, "Sistema");
        action(system, "Atualizações e permissões", "Abrir NyRoCore", "refresh", () ->
                open(new Intent().setClassName("com.nyroos.core", "com.nyroos.core.CoreActivity")));
        action(system, "Configurações do Android", null, "settings", () ->
                open(new Intent(Settings.ACTION_SETTINGS)));
    }

    private void updateHiddenSummary() {
        int count = LauncherPreferences.hiddenApps(preferences).size();
        hiddenSummary.setText(count == 0 ? "Nenhum app oculto" : count == 1 ? "1 app oculto" : count + " apps ocultos");
    }

    static void showHidden(Activity activity, SharedPreferences prefs, Runnable onChange) {
        var hidden = LauncherPreferences.hiddenApps(prefs);
        if (hidden.isEmpty()) {
            Toast.makeText(activity, "Nenhum app oculto", Toast.LENGTH_SHORT).show();
            return;
        }
        var entries = new ArrayList<NyRoSheet.Action>();
        var pm = activity.getPackageManager();
        for (String packageName : hidden) {
            String name = packageName;
            try {
                name = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
            } catch (PackageManager.NameNotFoundException ignored) {
                // A previously hidden app can be unavailable or uninstalled.
            }
            entries.add(new NyRoSheet.Action(name, "eye", () -> {
                var current = LauncherPreferences.hiddenApps(prefs);
                current.remove(packageName);
                prefs.edit().putStringSet(LauncherPreferences.HIDDEN, current).apply();
                onChange.run();
            }));
        }
        entries.sort((first, second) -> first.title().compareToIgnoreCase(second.title()));
        NyRoSheet.show(activity, "Mostrar na gaveta", null, entries);
    }

    private LinearLayout section(LinearLayout page, String title) {
        TextView label = label(title, 14, ACCENT, true);
        label.setAccessibilityHeading(true);
        label.setPadding(d(4), d(13), 0, d(7));
        page.addView(label, size(-1, -2));
        LinearLayout section = column();
        section.setPadding(d(6), d(4), d(6), d(4));
        section.setBackground(surface(this, CARD, 22, LINE));
        page.addView(section, size(-1, -2));
        return section;
    }

    private void option(LinearLayout section, LauncherPreferences.Option option) {
        LinearLayout item = itemRow();
        LinearLayout labels = column();
        labels.addView(label(option.title, 15, TEXT, false));
        if (option.description != null) labels.addView(label(option.description, 12, MUTED, false));
        item.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        Switch control = new Switch(this);
        control.setContentDescription(option.title);
        control.setShowText(false);
        control.setChecked(option.enabled(preferences));
        int[][] states = {{android.R.attr.state_checked}, {}};
        control.setThumbTintList(new ColorStateList(states, new int[]{ACCENT, 0xff837d94}));
        control.setTrackTintList(new ColorStateList(states, new int[]{ACCENT, 0xff46425a}));
        control.setOnCheckedChangeListener((view, enabled) -> preferences.edit().putBoolean(option.key, enabled).apply());
        item.addView(control, size(-2, 48));
        item.setOnClickListener(view -> control.toggle());
        section.addView(item, size(-1, -2));
    }

    private TextView action(LinearLayout section, String title, String summary, String glyph, Runnable callback) {
        LinearLayout item = itemRow();
        View icon = new Glyph(this, glyph, ACCENT, 19);
        icon.setBackground(surface(this, 0xff252139, 12, Color.TRANSPARENT));
        item.addView(icon, size(36, 36));
        LinearLayout labels = column();
        labels.setPadding(d(12), 0, d(8), 0);
        labels.addView(label(title, 15, TEXT, false));
        TextView detail = null;
        if (summary != null) {
            detail = label(summary, 12, MUTED, false);
            labels.addView(detail);
        }
        item.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        item.addView(new Glyph(this, "right", MUTED, 17), size(24, 34));
        item.setOnClickListener(view -> callback.run());
        section.addView(item, size(-1, -2));
        return detail;
    }

    private LinearLayout itemRow() {
        LinearLayout item = row();
        item.setMinimumHeight(d(55));
        item.setPadding(d(11), d(5), d(10), d(5));
        item.setBackground(pressable(this, Color.TRANSPARENT, 15, Color.TRANSPARENT));
        return item;
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "Aplicativo indisponível", Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView label(String value, int size, int color, boolean medium) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        if (medium) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return label;
    }

    private int d(int n) { return dp(this, n); }

    private LinearLayout.LayoutParams size(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : d(width), height < 0 ? height : d(height));
    }
}
