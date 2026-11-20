package com.nyroos.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

final class AppDirectory {
    record App(String name, String id, boolean system, String certificate) {}

    private final PackageManager manager;
    private final SharedPreferences approvals;

    AppDirectory(Context context) {
        manager = context.getPackageManager();
        approvals = context.getSharedPreferences("approvals", Context.MODE_PRIVATE);
    }

    List<App> installed() {
        List<App> apps = new ArrayList<>();
        for (PackageInfo pkg : manager.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES))) {
            ApplicationInfo info = pkg.applicationInfo;
            if (info == null || pkg.signingInfo == null) continue;
            boolean system = (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            apps.add(new App(manager.getApplicationLabel(info).toString(), pkg.packageName, system, signer(pkg)));
        }
        Collator locale = Collator.getInstance(Locale.forLanguageTag("pt-BR"));
        apps.sort((first, second) -> first.system() != second.system() ? Boolean.compare(first.system(), second.system())
                : locale.compare(first.name(), second.name()));
        return apps;
    }

    private static String signer(PackageInfo pkg) {
        try {
            ArrayList<String> signatures = new ArrayList<>();
            for (Signature signature : pkg.signingInfo.getApkContentsSigners()) {
                signatures.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
            }
            Collections.sort(signatures);
            return String.join(",", signatures);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    boolean approved(App app) {
        return !app.certificate().isBlank() && app.certificate().equals(approvals.getString(app.id(), ""));
    }

    void approve(App app, boolean allowed) {
        var editor = approvals.edit();
        if (allowed) editor.putString(app.id(), app.certificate());
        else editor.remove(app.id());
        editor.apply();
    }

    String report() throws Exception {
        var apps = new JSONArray();
        for (App item : installed()) {
            if (approved(item)) {
                apps.put(new JSONObject().put("package", item.id()).put("name", item.name())
                        .put("signingCertSha256", item.certificate()));
            }
        }
        return new JSONObject().put("mode", "supervised").put("apps", apps).toString(2) + "\n";
    }
}
