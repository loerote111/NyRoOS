package com.nyroos.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public final class CoreActivity extends Activity {
    private static final int BG=0xff11131a, PANEL=0xff1a1d28, EDGE=0xff333044, PURPLE=0xffb29aff,
            INK=0xff191026, TEXT=0xffefedf6, MUTED=0xffaaa7bc, GREEN=0xffa4dfc4;
    private static final int PICK_CONFIG=21, EXPORT_REPORT=22;
    private static final String TAG="NyRoCore";
    private final java.util.concurrent.ExecutorService tasks = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private AppDirectory directory;
    private LinearLayout header, body;
    private FrameLayout pages;
    private AppsAdapter appsAdapter;
    private final android.util.LruCache<String, android.graphics.drawable.Drawable.ConstantState> appIcons =
            new android.util.LruCache<>(48);
    private final java.util.concurrent.ExecutorService icons = Executors.newSingleThreadExecutor();
    private boolean appsTab, busy;
    private String status="Verifique se há uma nova versão do NyRoOS.";
    private String filter="De usuário";
    private EditText search;
    private OtaClient.Release release;
    private List<OtaClient.AppRelease> appUpdates = List.of();
    private final Map<String, File> readyApks = new HashMap<>();
    private TextView updateStatus;
    private File downloaded;
    private int progress=-1;
    private TextView downloadText;
    private ProgressBar bar;
    private List<AppDirectory.App> installed = List.of();
    private TextView shellStatus;
    private final Shizuku.OnBinderReceivedListener shellConnected = this::updateShellStatus;
    private final Shizuku.OnBinderDeadListener shellDisconnected = this::updateShellStatus;
    private final Shizuku.OnRequestPermissionResultListener shellPermission = (code, result) -> {
        if (code != 31) return;
        updateShellStatus();
        if (result == PackageManager.PERMISSION_GRANTED) testShell();
        else notify("Autorização do Shizuku recusada.");
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("core", MODE_PRIVATE);
        directory = new AppDirectory(this);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setNavigationBarContrastEnforced(false);
        LinearLayout root = column(0);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var system = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(system.left, system.top, system.right,
                    Math.max(system.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom));
            return insets;
        });
        setContentView(root);
        header = column(24);
        root.addView(header, lp(-1,-2));
        pages = new FrameLayout(this);
        root.addView(pages, new LinearLayout.LayoutParams(-1,0,1));
        render();
        Shizuku.addBinderReceivedListenerSticky(shellConnected);
        Shizuku.addBinderDeadListener(shellDisconnected);
        Shizuku.addRequestPermissionResultListener(shellPermission);
    }

    @Override protected void onResume() {
        super.onResume();
        if (appsTab && appsAdapter != null) loadApps();
        updateShellStatus();
    }

    @Override protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(shellConnected);
        Shizuku.removeBinderDeadListener(shellDisconnected);
        Shizuku.removeRequestPermissionResultListener(shellPermission);
        tasks.shutdownNow();
        icons.shutdownNow();
        super.onDestroy();
    }

    private void render() {
        header.removeAllViews();
        pages.removeAllViews();
        body = column(24);
        body.setPadding(d(24),d(23),d(24),d(24));
        appsAdapter = null;
        bar = null;
        downloadText = null;
        updateStatus = null;
        shellStatus = null;
        gap(header,10);
        LinearLayout brand = row();
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.core_icon);
        brand.addView(icon,lp(34,34));
        TextView name = text("NyRoCore",17,TEXT,true);
        name.setPadding(d(10),0,0,0);
        brand.addView(name,lp(-2,38));
        header.addView(brand,lp(-1,42));
        gap(header,13);
        LinearLayout tabs=row();
        tabs.setPadding(d(4),d(4),d(4),d(4));
        tabs.setBackground(shape(0xff1a1926,21,EDGE));
        chip(tabs,"Atualização",!appsTab,() -> { appsTab=false; render(); });
        chip(tabs,"Orquestrador",appsTab,() -> { appsTab=true; render(); });
        header.addView(tabs,lp(-1,49));
        if (appsTab) {
            ListView list = new ListView(this);
            list.setDivider(null);
            list.setItemsCanFocus(true);
            list.setVerticalScrollBarEnabled(false);
            list.addHeaderView(body, null, false);
            appsAdapter = new AppsAdapter();
            list.setAdapter(appsAdapter);
            pages.addView(list, new FrameLayout.LayoutParams(-1,-1));
            renderApps();
        } else {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(body, new ScrollView.LayoutParams(-1,-2));
            pages.addView(scroll, new FrameLayout.LayoutParams(-1,-1));
            renderOta();
        }
    }

    private void renderOta() {
        title("Atualização de\nsoftware");
        LinearLayout current=card(0xff221c33);
        TextView label=text("VERSÃO INSTALADA",12,PURPLE,true);
        label.setLetterSpacing(.1f);
        current.addView(label,lp(-1,-2));
        gap(current,8);
        current.addView(text(Build.DISPLAY.startsWith("NyRoOS ") ? Build.DISPLAY : "NyRoOS",30,TEXT,true),lp(-1,-2));
        gap(current,8);
        current.addView(text("Patch de segurança  •  " + Build.VERSION.SECURITY_PATCH,14,GREEN,false),lp(-1,-2));
        body.addView(current,lp(-1,-2));
        gap(body,14);
        LinearLayout actions=row();
        action(actions,"Verificar atualizações",true,!busy,this::check);
        body.addView(actions,lp(-1,54));
        gap(body,10);
        updateStatus = text(status,13,MUTED,false);
        body.addView(updateStatus,lp(-1,-2));
        for (var app : appUpdates) showAppUpdate(app);
        if (release != null) showRelease();
        gap(body,20);
        LinearLayout config=card(PANEL);
        config.addView(text("Fonte das atualizações",16,TEXT,true),lp(-1,-2));
        gap(config,5);
        TextView source=text(prefs.contains("otaConfig") ? "NyRoOS/ota_config.json" : "GitHub · catálogo oficial",13,MUTED,false);
        source.setSingleLine(true);
        source.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        config.addView(source,lp(-1,-2));
        gap(config,9);
        specialAction(config,"Selecionar configuração",() -> pickDocument(PICK_CONFIG),!busy);
        body.addView(config,lp(-1,-2));
    }

    private void showAppUpdate(OtaClient.AppRelease app) {
        gap(body,14);
        LinearLayout panel = card(PANEL);
        panel.addView(text(app.name() + " " + app.versionName(),19,TEXT,true),lp(-1,-2));
        gap(panel,5);
        panel.addView(text("Atualização do aplicativo",13,MUTED,false),lp(-1,-2));
        gap(panel,10);
        File apk = readyApks.get(app.packageName());
        if (apk == null) {
            specialAction(panel,"Baixar " + app.name(),() -> downloadApp(app),!busy);
        } else {
            specialAction(panel,"Instalar " + app.name(),() -> installApp(apk),!busy);
        }
        body.addView(panel,lp(-1,-2));
    }

    private void downloadApp(OtaClient.AppRelease app) {
        busy = true;
        status = "Baixando " + app.name() + "…";
        render();
        tasks.execute(() -> {
            try {
                File apk = OtaClient.downloadApp(this, app, progress -> onUi(() -> {
                    status = "Baixando " + app.name() + " · " + progress + "%";
                    if (updateStatus != null) updateStatus.setText(status);
                }));
                onUi(() -> {
                    readyApks.put(app.packageName(), apk);
                    status = app.name() + " · download e assinatura verificados.";
                    Log.i(TAG, status);
                    finishAction();
                });
            } catch (Exception error) {
                failure("Falha ao baixar " + app.name() + ".", error);
            }
        });
    }

    private void installApp(File apk) {
        if (!apk.isFile()) {
            notify("O APK expirou. Verifique e baixe a atualização novamente.");
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            notify("Autorize esta fonte e toque em Instalar novamente.");
            openSetting(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        Uri uri = new Uri.Builder().scheme("content").authority(UpdateFiles.AUTHORITY).appendPath(apk.getName()).build();
        Intent installer = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, UpdateFiles.APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        openSetting(installer);
    }

    private void showRelease() {
        gap(body,17);
        LinearLayout panel=card(PANEL);
        String marker=release.demo() ? "SIMULAÇÃO" : "NOVA VERSÃO";
        TextView badge=text(marker,12,PURPLE,true);
        badge.setLetterSpacing(.1f);
        panel.addView(badge,lp(-1,-2));
        gap(panel,6);
        panel.addView(text("NyRoOS " + release.version(),25,TEXT,true),lp(-1,-2));
        gap(panel,8);
        for (String change : release.changes()) {
            TextView bullet=text("•  " + change,14,MUTED,false);
            bullet.setPadding(0,0,0,d(4));
            panel.addView(bullet,lp(-1,-2));
        }
        gap(panel,9);
        downloadText=text("",13,downloaded == null ? MUTED : GREEN,true);
        panel.addView(downloadText,lp(-1,-2));
        gap(panel,6);
        bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        bar.setProgressTintList(ColorStateList.valueOf(PURPLE));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(EDGE));
        panel.addView(bar,lp(-1,7));
        renderProgress();
        gap(panel,13);
        LinearLayout row=row();
        action(row,downloaded == null ? "Baixar pacote" : "Baixar novamente",downloaded == null,!busy,this::download);
        gapX(row,8);
        action(row,"Instalar Agora",true,downloaded != null && !busy && !release.demo() && canInstallSystemOta(),this::confirmInstall);
        panel.addView(row,lp(-1,46));
        if (release.demo()) {
            gap(panel,9);
            panel.addView(text("Pacote de teste. Instalação desativada.",12,MUTED,false),lp(-1,-2));
        }
        body.addView(panel,lp(-1,-2));
    }

    private void renderProgress() {
        if (bar == null || downloadText == null) return;
        bar.setProgress(Math.max(progress,0),true);
        String size=String.format(Locale.forLanguageTag("pt-BR"),"%.1f MiB",release.bytes() / 1048576d);
        downloadText.setText(downloaded != null ? "Download verificado  •  " + size :
                progress >= 0 ? "Baixando  •  " + progress + "%" : "Tamanho do pacote  •  " + size);
    }

    private void check() {
        busy=true;
        release=null;
        downloaded=null;
        progress=-1;
        appUpdates=List.of();
        readyApks.clear();
        status="Consultando catálogo assinado…";
        render();
        tasks.execute(() -> {
            try {
                String link=readFeedConfiguration();
                OtaClient.Catalog catalog=OtaClient.check(this,link);
                onUi(() -> {
                    release=catalog.system();
                    appUpdates=catalog.apps();
                    if (!appUpdates.isEmpty()) {
                        status="Catálogo assinado verificado. Atualização de aplicativos disponível.";
                    } else if (release != null) {
                        status="Catálogo assinado verificado.";
                    } else {
                        status="Catálogo assinado verificado. Nenhum OTA disponível.";
                    }
                    Log.i(TAG,status);
                    finishAction();
                });
            } catch (Exception e) {
                failure("Falha ao verificar atualizações.",e);
            }
        });
    }

    private String readFeedConfiguration() throws Exception {
        String saved=prefs.getString("otaConfig",null);
        if (saved == null) return OtaClient.defaultFeed(this);
        try (var input=getContentResolver().openInputStream(Uri.parse(saved))) {
            String candidate=new JSONObject(OtaClient.read(input,8192)).getString("manifestUrl");
            return OtaClient.checkedUri(candidate,true).toString();
        }
    }

    private void download() {
        busy=true;
        downloaded=null;
        progress=0;
        OtaClient.Release item=release;
        render();
        tasks.execute(() -> {
            try {
                File file=OtaClient.download(this,item,value -> onUi(() -> { progress=value; renderProgress(); }));
                onUi(() -> {
                    downloaded=file;
                    progress=100;
                    Log.i(TAG,"Pacote recebido e verificado: " + file.length() + " bytes");
                    finishAction();
                });
            } catch (Exception e) {
                failure("Falha ao baixar pacote.",e);
            }
        });
    }

    private boolean canInstallSystemOta() {
        return Build.DISPLAY.startsWith("NyRoOS ")
                && checkSelfPermission("android.permission.RECOVERY") == PackageManager.PERMISSION_GRANTED;
    }

    private void confirmInstall() {
        OtaClient.Release item=release;
        File file=downloaded;
        if (busy || item == null || item.demo() || file == null || !canInstallSystemOta()) return;
        new AlertDialog.Builder(this).setTitle("Instalar NyRoOS " + item.version())
                .setMessage("O dispositivo será reiniciado no recovery para instalar a atualização verificada.")
                .setNegativeButton("Cancelar",null).setPositiveButton("Instalar Agora",(dialog,button) -> {
                    busy=true;
                    status="Preparando pacote para o recovery…";
                    render();
                    tasks.execute(() -> {
                        try {
                            OtaClient.install(this,item,file);
                        } catch (Exception e) {
                            failure("Falha ao iniciar o recovery.",e);
                        }
                    });
                }).show();
    }

    private void failure(String fallback, Exception error) {
        onUi(() -> {
            status=error instanceof java.io.IOException && error.getMessage() != null ? error.getMessage() : fallback;
            progress=-1;
            finishAction();
        });
    }

    private void finishAction() {
        busy=false;
        if (!appsTab) render();
    }

    private void renderApps() {
        title("Orquestrador\nde apps");
        TextView intro=text("Acompanhe seus projetos e abra os controles de acesso do Android.",15,MUTED,false);
        body.addView(intro,lp(-1,-2));
        gap(body,14);
        LinearLayout shell = card(0xff201b2d);
        shellStatus = text("",14,MUTED,false);
        shell.addView(shellStatus,lp(-1,-2));
        gap(shell,7);
        specialAction(shell,"Conectar ao Shizuku",this::connectShell,true);
        body.addView(shell,lp(-1,-2));
        updateShellStatus();
        gap(body,14);
        search=new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setHint("Buscar apps ou pacotes");
        search.setTextSize(15);
        search.setPadding(d(17),0,d(17),0);
        search.setBackground(shape(PANEL,20,EDGE));
        body.setFocusableInTouchMode(true);
        body.addView(search,lp(-1,52));
        body.requestFocus();
        gap(body,10);
        LinearLayout filters=row();
        for (String option : List.of("De usuário","Todos","Acompanhados")) {
            chip(filters,option,filter.equals(option),() -> {
                filter=option;
                for (int i=0;i<filters.getChildCount();i++) {
                    View widget=filters.getChildAt(i);
                    if (widget instanceof TextView label) {
                        boolean selected=label.getText().toString().equals(filter);
                        label.setTextColor(selected ? INK : MUTED);
                        label.setBackground(ripple(selected ? PURPLE : BG,17,selected ? 0 : EDGE));
                    }
                }
                populateApps();
            });
            if (!option.equals("Acompanhados")) gapX(filters,6);
        }
        body.addView(filters,lp(-1,42));
        gap(body,12);
        LinearLayout report=card(0xff201b2d);
        report.addView(text("Relatório de acompanhamento",15,TEXT,true),lp(-1,-2));
        gap(report,5);
        report.addView(text(prefs.contains("report") ? "trusted_apps.json vinculado" :
                "Exporte em trusted_apps.json.",13,MUTED,false),lp(-1,-2));
        gap(report,6);
        specialAction(report,prefs.contains("report") ? "Exportar novamente" : "Vincular relatório",
                () -> pickDocument(EXPORT_REPORT),true);
        body.addView(report,lp(-1,-2));
        gap(body,14);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { populateApps(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        populateApps();
        loadApps();
    }

    private void loadApps() {
        tasks.execute(() -> {
            List<AppDirectory.App> result=directory.installed();
            onUi(() -> {
                installed=result;
                if (appsTab) populateApps();
            });
        });
    }

    private void populateApps() {
        if (!appsTab || appsAdapter == null || search == null) return;
        String query=search.getText().toString().toLowerCase(Locale.ROOT).trim();
        var visible = new ArrayList<AppDirectory.App>();
        for (AppDirectory.App app : installed) {
            if (filter.equals("De usuário") && app.system()
                    || filter.equals("Acompanhados") && !directory.approved(app)) continue;
            if (!query.isEmpty() && !app.name().toLowerCase(Locale.ROOT).contains(query)
                    && !app.id().toLowerCase(Locale.ROOT).contains(query)) continue;
            visible.add(app);
        }
        appsAdapter.entries = visible;
        appsAdapter.notifyDataSetChanged();
    }

    private final class AppsAdapter extends BaseAdapter {
        List<AppDirectory.App> entries = List.of();

        @Override public int getCount() { return Math.max(1, entries.size()); }
        @Override public AppDirectory.App getItem(int position) { return entries.isEmpty() ? null : entries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean isEnabled(int position) { return !entries.isEmpty(); }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            FrameLayout row = new FrameLayout(CoreActivity.this);
            row.setPadding(d(24),0,d(24),d(9));
            AppDirectory.App app = getItem(position);
            View tile = app == null ? text("Nenhum app neste filtro.",14,MUTED,false)
                    : showApp(app, directory.approved(app));
            row.addView(tile, new FrameLayout.LayoutParams(-1,-2));
            return row;
        }
    }

    private View showApp(AppDirectory.App app,boolean approved) {
        LinearLayout tile=card(PANEL);
        LinearLayout line=row();
        ImageView icon=new ImageView(this);
        icon.setImageResource(R.drawable.core_icon);
        var cached = appIcons.get(app.id());
        if (cached != null) {
            icon.setImageDrawable(cached.newDrawable(getResources()));
        } else {
            var view = new java.lang.ref.WeakReference<>(icon);
            icons.execute(() -> {
                try {
                    var state = getPackageManager().getApplicationIcon(app.id()).getConstantState();
                    if (state != null) onUi(() -> {
                        appIcons.put(app.id(), state);
                        ImageView target = view.get();
                        if (target != null) target.setImageDrawable(state.newDrawable(getResources()));
                    });
                } catch (PackageManager.NameNotFoundException ignored) {
                    // The package may have been removed while the card was loading.
                }
            });
        }
        line.addView(icon,lp(39,39));
        LinearLayout label=column(0);
        label.setPadding(d(10),0,d(6),0);
        TextView name=text(app.name(),16,TEXT,true);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        label.addView(name,lp(-1,-2));
        TextView pkg=text(app.id(),12,MUTED,false);
        pkg.setSingleLine(true);
        pkg.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        label.addView(pkg,lp(-1,-2));
        line.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        Switch select=new Switch(this);
        select.setChecked(approved);
        select.setContentDescription("Acompanhar " + app.name());
        select.setOnClickListener(view -> {
            select.setChecked(approved);
            changeApproval(app,!approved);
        });
        line.addView(select,lp(-2,48));
        tile.addView(line,lp(-1,50));
        gap(tile,5);
        boolean exempt=getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(app.id());
        tile.addView(text((approved ? "Acompanhado  •  " : "") +
                (exempt ? "Bateria sem otimização" : "Bateria gerida pelo Android"),12,approved ? GREEN : MUTED,false),lp(-1,-2));
        gap(tile,11);
        LinearLayout actions=row();
        action(actions,"Permissões",false,true,() -> openSetting(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package",app.id(),null))));
        gapX(actions,8);
        action(actions,"Bateria",false,true,() -> openSetting(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        tile.addView(actions,lp(-1,42));
        if (ShellUserService.canManage(app.id(), app.system())) {
            gap(tile,8);
            specialAction(tile,"Ações via Shizuku",() -> chooseShellAction(app),true);
        }
        return tile;
    }

    private void updateShellStatus() {
        onUi(() -> {
            if (shellStatus == null) return;
            String statusText = "Shizuku precisa estar ativo via ADB.";
            if (ShellActions.active()) {
                statusText = ShellActions.authorized() ? "Shizuku autorizado · Shell (UID 2000)"
                        : "Shizuku ativo · autorize o NyRoCore";
            }
            shellStatus.setText(statusText);
        });
    }

    private void connectShell() {
        if (!ShellActions.active()) {
            Intent manager = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (manager != null) openSetting(manager);
            else notify("Instale e inicie o Shizuku via ADB.");
            return;
        }
        try {
            if (ShellActions.authorized()) testShell();
            else if (Shizuku.shouldShowRequestPermissionRationale()) {
                notify("Autorize o NyRoCore na lista de apps do Shizuku.");
            } else Shizuku.requestPermission(31);
        } catch (RuntimeException error) {
            notify("O serviço Shizuku foi desconectado.");
        }
    }

    private void testShell() {
        runShell(ShellActions.Action.STATUS, null, null);
    }

    private record AppAction(String label, ShellActions.Action action, String argument) {}

    private void chooseShellAction(AppDirectory.App app) {
        if (!ShellActions.authorized()) {
            connectShell();
            return;
        }
        var options = new ArrayList<AppAction>();
        try {
            var pm = getPackageManager();
            var info = pm.getPackageInfo(app.id(), PackageManager.GET_PERMISSIONS);
            int enabled = pm.getApplicationEnabledSetting(app.id());
            boolean disabled = enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
            options.add(new AppAction(disabled ? "Reativar app" : "Desativar app",
                    disabled ? ShellActions.Action.ENABLE : ShellActions.Action.DISABLE, null));
            if (!app.system()) {
                boolean exempt = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(app.id());
                options.add(new AppAction(exempt ? "Voltar à otimização de bateria" : "Isentar da otimização de bateria",
                        exempt ? ShellActions.Action.BATTERY_DEFAULT : ShellActions.Action.BATTERY_EXEMPT, null));
                if (info.requestedPermissions != null) {
                    for (int i=0; i<info.requestedPermissions.length; i++) {
                        String permission = info.requestedPermissions[i];
                        if (ShellUserService.APP_OPS.containsKey(permission)) {
                            options.add(new AppAction("Permitir " + permission.substring(permission.lastIndexOf('.') + 1),
                                    ShellActions.Action.ALLOW_APP_OP, permission));
                            continue;
                        }
                        try {
                            var detail = pm.getPermissionInfo(permission, 0);
                            if (detail.getProtection() == PermissionInfo.PROTECTION_DANGEROUS
                                    && (info.requestedPermissionsFlags == null ||
                                    (info.requestedPermissionsFlags[i] & android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) == 0)) {
                                options.add(new AppAction("Conceder " + permission.substring(permission.lastIndexOf('.') + 1),
                                        ShellActions.Action.GRANT_PERMISSION, permission));
                            }
                        } catch (PackageManager.NameNotFoundException ignored) {
                            // Permissions declared by unavailable packages are not selectable.
                        }
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException error) {
            notify("O aplicativo foi removido.");
            return;
        }
        String[] labels = options.stream().map(AppAction::label).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle(app.name()).setItems(labels, (dialog, index) -> {
            AppAction selection = options.get(index);
            new AlertDialog.Builder(this).setTitle(selection.label())
                    .setMessage("Aplicar a alteração a " + app.id() + "?")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Aplicar", (confirm, button) ->
                            runShell(selection.action(), app.id(), selection.argument())).show();
        }).setNegativeButton("Cancelar", null).show();
    }

    private void runShell(ShellActions.Action action, String packageName, String argument) {
        tasks.execute(() -> {
            try {
                String result = ShellActions.run(action, packageName, argument);
                onUi(() -> {
                    if (action == ShellActions.Action.STATUS) {
                        Log.i(TAG,"Shizuku UserService UID=" + result);
                        notify("Conexão Shell confirmada · UID " + result);
                    } else {
                        notify(result);
                        loadApps();
                    }
                });
            } catch (Exception error) {
                Log.w(TAG,"Falha na ação Shizuku: " + action, error);
                onUi(() -> notify(error.getMessage() == null ? "O Android recusou a alteração." : error.getMessage()));
            }
        });
    }

    private void changeApproval(AppDirectory.App app,boolean approved) {
        new AlertDialog.Builder(this)
                .setTitle(approved ? "Acompanhar " + app.name() + "?" : "Remover acompanhamento?")
                .setMessage(approved ? "Adiciona o app ao relatório local. Permissões e bateria são geridas nas telas do Android." : app.name())
                .setNegativeButton("Cancelar",null)
                .setPositiveButton(approved ? "Acompanhar" : "Remover",(dialog,which) -> {
                    directory.approve(app,approved);
                    Log.i(TAG,(approved ? "Acompanhamento ativado: " : "Acompanhamento revogado: ") + app.id());
                    populateApps();
                    writeReport(false);
                }).show();
    }

    private void pickDocument(int action) {
        Uri initial=DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:NyRoOS");
        Intent intent=new Intent(action == PICK_CONFIG ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(action == PICK_CONFIG ? "*/*" : "application/json")
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI,initial);
        if (action == EXPORT_REPORT) intent.putExtra(Intent.EXTRA_TITLE,"trusted_apps.json");
        try {
            startActivityForResult(intent,action);
        } catch (RuntimeException e) {
            notify("Seletor de documentos indisponível.");
        }
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if ((request != PICK_CONFIG && request != EXPORT_REPORT) || result != RESULT_OK || data == null || data.getData() == null)
            return;
        Uri uri=data.getData();
        int access=request == PICK_CONFIG ? Intent.FLAG_GRANT_READ_URI_PERMISSION : Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        String key=request == PICK_CONFIG ? "otaConfig" : "report";
        try {
            getContentResolver().takePersistableUriPermission(uri,access);
            String prior=prefs.getString(key,null);
            if (prior != null && !prior.equals(uri.toString())) {
                try {
                    getContentResolver().releasePersistableUriPermission(Uri.parse(prior),access);
                } catch (SecurityException ignored) {
                    // The user may have removed the old document in Android Files.
                }
            }
            prefs.edit().putString(key,uri.toString()).apply();
            if (request == PICK_CONFIG) {
                release=null;
                downloaded=null;
                progress=-1;
                status="Configuração vinculada. Verifique o catálogo.";
            }
            render();
            if (request == EXPORT_REPORT) writeReport(true);
        } catch (SecurityException e) {
            notify("O seletor não autorizou o documento.");
        }
    }

    private void writeReport(boolean announce) {
        String saved=prefs.getString("report",null);
        if (saved == null) return;
        tasks.execute(() -> {
            try {
                String json=directory.report();
                try (var output=getContentResolver().openOutputStream(Uri.parse(saved),"wt")) {
                    if (output == null) throw new java.io.IOException("Documento indisponível.");
                    output.write(json.getBytes(StandardCharsets.UTF_8));
                }
                Log.i(TAG,"Relatório trusted_apps.json atualizado");
                if (announce) onUi(() -> notify("Relatório exportado."));
            } catch (Exception e) {
                onUi(() -> notify("Vincule novamente o relatório para exportar."));
            }
        });
    }

    private void openSetting(Intent intent) {
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            notify("Tela de configuração indisponível.");
        }
    }

    private void onUi(Runnable runnable) {
        runOnUiThread(() -> { if (!isDestroyed()) runnable.run(); });
    }

    private void notify(String message) {
        Toast.makeText(this,message,Toast.LENGTH_SHORT).show();
    }

    private void title(String value) {
        TextView title=text(value,38,TEXT,true);
        title.setLetterSpacing(-.034f);
        title.setLineSpacing(0,.94f);
        body.addView(title,lp(-1,-2));
        gap(body,14);
    }

    private LinearLayout card(int color) {
        LinearLayout panel=column(15);
        panel.setPadding(d(16),d(15),d(16),d(15));
        panel.setBackground(shape(color,25,EDGE));
        return panel;
    }

    private void chip(LinearLayout row,String value,boolean selected,Runnable call) {
        TextView button=text(value,13,selected ? INK : MUTED,true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(ripple(selected ? PURPLE : BG,17,selected ? 0 : EDGE));
        button.setOnClickListener(view -> call.run());
        row.addView(button,new LinearLayout.LayoutParams(0,-1,1));
    }

    private void action(LinearLayout row,String value,boolean primary,boolean enabled,Runnable call) {
        TextView button=text(value,14,enabled ? primary ? INK : PURPLE : MUTED,true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(ripple(enabled ? primary ? PURPLE : 0xff222034 : 0xff282632,18,
                enabled && primary ? 0 : EDGE));
        button.setEnabled(enabled);
        if (enabled) button.setOnClickListener(view -> call.run());
        row.addView(button,new LinearLayout.LayoutParams(0,-1,1));
    }

    private void specialAction(LinearLayout container,String value,Runnable call,boolean enabled) {
        TextView button=text(value,13,PURPLE,true);
        button.setPadding(d(10),0,d(10),0);
        button.setBackground(ripple(0xff29233c,14,0));
        if (enabled) button.setOnClickListener(view -> call.run());
        button.setEnabled(enabled);
        container.addView(button,lp(-2,38));
    }

    private TextView text(String value,float size,int color,boolean strong) {
        TextView label=new TextView(this);
        label.setText(value);
        label.setTextColor(color);
        label.setTextSize(size);
        label.setTypeface(Typeface.create(strong ? "sans-serif-medium" : "sans-serif",Typeface.NORMAL));
        label.setGravity(Gravity.CENTER_VERTICAL);
        return label;
    }

    private GradientDrawable shape(int fill,int radius,int border) {
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(d(radius));
        if (border != 0) shape.setStroke(d(1),border);
        return shape;
    }

    private RippleDrawable ripple(int fill,int radius,int border) {
        return new RippleDrawable(ColorStateList.valueOf(0x33b29aff),shape(fill,radius,border),shape(Color.WHITE,radius,0));
    }

    private LinearLayout column(int inset) {
        LinearLayout column=new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(d(inset),0,d(inset),0);
        return column;
    }

    private LinearLayout row() {
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private int d(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams lp(int width,int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : d(width),height < 0 ? height : d(height));
    }
    private void gap(LinearLayout parent,int value) { parent.addView(new View(this),lp(1,value)); }
    private void gapX(LinearLayout parent,int value) { parent.addView(new View(this),lp(value,1)); }
}
