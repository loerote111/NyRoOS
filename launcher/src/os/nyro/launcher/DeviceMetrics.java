package os.nyro.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Environment;
import android.os.StatFs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DeviceMetrics {
    private static final Pattern CPU_TOTAL = Pattern.compile("^\\s*([0-9.]+)% TOTAL:");
    static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    static ActivityManager.MemoryInfo memory(Context context) {
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        context.getSystemService(ActivityManager.class).getMemoryInfo(memory);
        return memory;
    }

    static StatFs storage() {
        return new StatFs(Environment.getDataDirectory().getAbsolutePath());
    }

    static String gib(long bytes) {
        return String.format(PT_BR, "%.1f GiB", bytes / (double) (1L << 30));
    }

    static String address(Context context) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        Network active = connectivity.getActiveNetwork();
        LinkProperties properties = active != null ? connectivity.getLinkProperties(active) : null;
        if (properties == null) return "Sem rede";
        String ipv6 = null;
        for (LinkAddress link : properties.getLinkAddresses()) {
            InetAddress address = link.getAddress();
            if (address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
            if (address instanceof Inet4Address) return address.getHostAddress();
            ipv6 = address.getHostAddress();
        }
        return ipv6 == null ? "Sem IP" : ipv6;
    }

    static String cpu() {
        Process process = null;
        // CpuBinder checks the privileged dump and usage permissions before producing this summary.
        try {
            process = new ProcessBuilder("/system/bin/dumpsys", "-t", "2", "cpuinfo")
                    .redirectErrorStream(true).start();
            try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = input.readLine()) != null) {
                    Matcher match = CPU_TOTAL.matcher(line);
                    if (match.find()) return match.group(1).replace('.', ',') + "%";
                    if (line.startsWith("Permission Denial:") || line.startsWith("Can't find service:")) {
                        android.util.Log.w("NyRoLauncher", line);
                        break;
                    }
                }
            }
        } catch (java.io.IOException e) {
            android.util.Log.w("NyRoLauncher", "CPU indisponível", e);
        } finally {
            if (process != null) process.destroy();
        }
        return "—";
    }
}
