package com.nyroos.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public final class CoreActivity extends Activity {
    private static final int BG=0xff11131a, PANEL=0xff1a1d28, EDGE=0xff333044, PURPLE=0xffb29aff,
            INK=0xff191026, TEXT=0xffefedf6, MUTED=0xffaaa7bc, GREEN=0xffa4dfc4;
    private static final int PICK_CONFIG=21, EXPORT_REPORT=22;
    private static final String TAG="NyRoCore";
    private final java.util.concurrent.ExecutorService tasks = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private AppDirectory directory;
    private LinearLayout header, body, list;
    private boolean appsTab, busy;
    private String status="Verifique se há uma nova versão do NyRoOS.";
    private String filter="De usuário";
    private EditText search;
    private OtaClient.Release release;
    private File downloaded;
    private int progress=-1;
    private TextView downloadText;
    private ProgressBar bar;
    private List<AppDirectory.App> installed = List.of();

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
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        body=column(24);
        body.setPadding(d(24),d(23),d(24),d(24));
        scroll.addView(body, new ScrollView.LayoutParams(-1,-2));
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (appsTab && list != null) loadApps();
    }

    @Override protected void onDestroy() {
        tasks.shutdownNow();
        super.onDestroy();
    }

    private void render() {
        header.removeAllViews();
        body.removeAllViews();
        bar = null;
        downloadText = null;
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
        if (appsTab) renderApps(); else renderOta();
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
        body.addView(text(status,13,MUTED,false),lp(-1,-2));
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
        action(row,"Instalar Agora",true,downloaded != null && !busy && !release.demo(),this::confirmInstall);
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
        status="Consultando catálogo assinado…";
        render();
        tasks.execute(() -> {
            try {
                String link=readFeedConfiguration();
                OtaClient.Release item=OtaClient.check(this,link);
                onUi(() -> {
                    release=item;
                    status=item == null ? "Catálogo assinado verificado. Nenhum OTA disponível." : "Catálogo assinado verificado.";
                    if (item == null) Log.i(TAG,"Catálogo assinado verificado. Nenhum OTA disponível.");
                    if (item != null) Log.i(TAG,"Catálogo verificado: NyRoOS " + item.version() + ", demo=" + item.demo());
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

    private void confirmInstall() {
        OtaClient.Release item=release;
        File file=downloaded;
        if (busy || item == null || item.demo() || file == null) return;
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
        list=column(0);
        body.addView(list,lp(-1,-2));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { populateApps(); }
            @Override public void afterTextChanged(Editable s) {}
        });
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
        if (!appsTab || list == null || search == null) return;
        list.removeAllViews();
        String query=search.getText().toString().toLowerCase(Locale.ROOT).trim();
        for (AppDirectory.App app : installed) {
            boolean approved=directory.approved(app);
            if (filter.equals("De usuário") && app.system() || filter.equals("Acompanhados") && !approved) continue;
            if (!(app.name().toLowerCase(Locale.ROOT).contains(query) || app.id().toLowerCase(Locale.ROOT).contains(query))) continue;
            showApp(app,approved);
        }
        if (list.getChildCount()==0)
            list.addView(text("Nenhum app neste filtro.",14,MUTED,false),lp(-1,-2));
    }

    private void showApp(AppDirectory.App app,boolean approved) {
        LinearLayout tile=card(PANEL);
        LinearLayout line=row();
        ImageView icon=new ImageView(this);
        try {
            icon.setImageDrawable(getPackageManager().getApplicationIcon(app.id()));
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            icon.setImageResource(R.drawable.core_icon);
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
        list.addView(tile,lp(-1,-2));
        gap(list,9);
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
