package com.nyroos.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Binder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class ShellUserService extends Binder {
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");
    private static final Set<String> PROTECTED = Set.of(
            "com.nyroos.core", "os.nyro.launcher", "moe.shizuku.privileged.api",
            "com.android.chrome", "com.android.vending", "com.google.android.googlequicksearchbox",
            "com.google.android.webview", "com.google.android.gms");
    private static final Set<String> OPTIONAL_SAMSUNG = Set.of(
            "com.samsung.android.bixby.agent", "com.samsung.android.app.spage",
            "com.samsung.android.arzone", "com.samsung.android.voc", "com.samsung.android.game.gamehome");
    static final Map<String, String> APP_OPS = Map.of(
            "android.permission.PACKAGE_USAGE_STATS", "GET_USAGE_STATS",
            "android.permission.SYSTEM_ALERT_WINDOW", "SYSTEM_ALERT_WINDOW",
            "android.permission.WRITE_SETTINGS", "WRITE_SETTINGS");
    private final int ownerUid;
    private final PackageManager packages;

    public ShellUserService(Context context) {
        ownerUid = context.getApplicationInfo().uid;
        packages = context.getPackageManager();
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == ShellActions.DESTROY && Binder.getCallingUid() == 2000) {
            System.exit(0);
            return true;
        }
        if (code != ShellActions.REQUEST) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(ShellActions.INTERFACE);
        if (Binder.getCallingUid() != ownerUid || Process.myUid() != 2000) {
            throw new SecurityException("O serviço é exclusivo do NyRoCore em modo Shell.");
        }
        try {
            ShellActions.Action action = ShellActions.Action.valueOf(data.readString());
            String target = data.readString();
            String argument = data.readString();
            data.enforceNoDataAvail();
            String response = execute(action, target, argument);
            reply.writeNoException();
            reply.writeString(response);
        } catch (IOException | PackageManager.NameNotFoundException error) {
            reply.writeException(new IllegalStateException(error.getMessage()));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            reply.writeException(new IllegalStateException("Operação interrompida."));
        }
        return true;
    }

    private String execute(ShellActions.Action action, String target, String argument)
            throws IOException, InterruptedException, PackageManager.NameNotFoundException {
        if (action == ShellActions.Action.STATUS) return command("/system/bin/id", "-u");
        if (target == null || !PACKAGE.matcher(target).matches() || PROTECTED.contains(target)) {
            throw new SecurityException("O pacote está protegido ou é inválido.");
        }
        var info = packages.getPackageInfo(target, PackageManager.GET_PERMISSIONS);
        boolean system = (info.applicationInfo.flags &
                (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        boolean toggle = action == ShellActions.Action.ENABLE || action == ShellActions.Action.DISABLE;
        if (system && !(toggle && OPTIONAL_SAMSUNG.contains(target))) {
            throw new SecurityException("Operação indisponível para esse aplicativo de sistema.");
        }
        return switch (action) {
            case GRANT_PERMISSION -> {
                checkDeclared(info.requestedPermissions, argument);
                PermissionInfo permission = packages.getPermissionInfo(argument, 0);
                if (permission.getProtection() != PermissionInfo.PROTECTION_DANGEROUS) {
                    throw new SecurityException("Selecione uma permissão de execução do app.");
                }
                yield command("/system/bin/pm", "grant", "--user", "0", target, argument);
            }
            case ALLOW_APP_OP -> {
                checkDeclared(info.requestedPermissions, argument);
                String operation = APP_OPS.get(argument);
                if (operation == null) throw new SecurityException("Acesso especial indisponível.");
                yield command("/system/bin/appops", "set", "--user", "0", target, operation, "allow");
            }
            case BATTERY_EXEMPT -> command("/system/bin/cmd", "deviceidle", "whitelist", "+" + target);
            case BATTERY_DEFAULT -> command("/system/bin/cmd", "deviceidle", "whitelist", "-" + target);
            case DISABLE -> command("/system/bin/pm", "disable-user", "--user", "0", target);
            case ENABLE -> command("/system/bin/pm", "enable", "--user", "0", target);
            case STATUS -> throw new IllegalStateException("Ação inválida.");
        };
    }

    static boolean canManage(String packageName, boolean system) {
        return !PROTECTED.contains(packageName) && (!system || OPTIONAL_SAMSUNG.contains(packageName));
    }

    private static void checkDeclared(String[] declared, String permission) {
        if (permission == null || declared == null || !Arrays.asList(declared).contains(permission)) {
            throw new SecurityException("A permissão precisa estar declarada pelo aplicativo.");
        }
    }

    private static String command(String... args) throws IOException, InterruptedException {
        java.lang.Process child = new ProcessBuilder(args).redirectErrorStream(true).start();
        try {
            if (!child.waitFor(8, TimeUnit.SECONDS)) throw new IOException("O comando excedeu o tempo limite.");
            try (var stream = child.getInputStream()) {
                String message = new String(stream.readNBytes(4096), StandardCharsets.UTF_8).strip();
                if (child.exitValue() != 0 || message.startsWith("Error:") || message.startsWith("Exception")) {
                    throw new IOException(message.isEmpty() ? "O Android recusou a alteração." : message);
                }
                return message;
            }
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }
}
