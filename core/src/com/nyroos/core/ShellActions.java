package com.nyroos.core;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import rikka.shizuku.Shizuku;

final class ShellActions {
    static final String INTERFACE = "com.nyroos.core.ShellActions";
    static final int REQUEST = IBinder.FIRST_CALL_TRANSACTION;
    static final int DESTROY = 16777115;

    enum Action {
        STATUS, GRANT_PERMISSION, ALLOW_APP_OP, BATTERY_EXEMPT, BATTERY_DEFAULT, DISABLE, ENABLE
    }

    static boolean active() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.getUid() == 2000;
        } catch (RuntimeException error) {
            return false;
        }
    }

    static boolean authorized() {
        try {
            return active() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException error) {
            return false;
        }
    }

    static String run(Action action, String packageName, String argument) throws Exception {
        if (!authorized()) throw new IOException("Autorize o NyRoCore no Shizuku iniciado via ADB.");
        var connected = new CountDownLatch(1);
        var binder = new AtomicReference<IBinder>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                binder.set(service);
                connected.countDown();
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                binder.set(null);
                connected.countDown();
            }
        };
        var args = new Shizuku.UserServiceArgs(new ComponentName("com.nyroos.core", ShellUserService.class.getName()))
                .processNameSuffix("nyro_shell").version(2).daemon(false);
        Shizuku.bindUserService(args, connection);
        try {
            if (!connected.await(10, TimeUnit.SECONDS) || binder.get() == null) {
                throw new IOException("O serviço Shell do NyRoCore não respondeu.");
            }
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(INTERFACE);
                data.writeString(action.name());
                data.writeString(packageName);
                data.writeString(argument);
                IBinder remote = binder.get();
                if (remote == null || !remote.transact(REQUEST, data, reply, 0)) {
                    throw new IOException("O serviço Shell perdeu a conexão.");
                }
                reply.readException();
                String message = reply.readString();
                return message == null || message.isBlank() ? "Operação concluída." : message;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } finally {
            try {
                Shizuku.unbindUserService(args, connection, true);
            } catch (RuntimeException ignored) {
                // Shizuku may have stopped during the command.
            }
        }
    }

    private ShellActions() {}
}
