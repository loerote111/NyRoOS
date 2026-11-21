package com.nyroos.core;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.RecoverySystem;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.function.IntConsumer;
import java.util.zip.ZipFile;

final class OtaClient {
    private static final long MAX_PACKAGE = 6L * 1024 * 1024 * 1024;
    private static final int MAX_MANIFEST = 96 * 1024;
    private static final long MAX_APK = 128L * 1024 * 1024;
    private static final Map<String, String> APP_IDS = Map.of(
            "NyRoLauncher.apk", "os.nyro.launcher", "NyRoCore.apk", "com.nyroos.core");

    record AppRelease(String name, String packageName, long versionCode, String versionName,
                      URI url, long bytes, byte[] hash) {}
    record Catalog(Release system, List<AppRelease> apps) {}
    record Release(String version, String device, String sourceBuild, URI url, byte[] hash,
                   long bytes, List<String> changes, boolean demo) {}

    static String defaultFeed(Context context) throws IOException {
        try (InputStream stream = context.getAssets().open("ota_feed.txt")) {
            return read(stream, 512).trim();
        }
    }

    static String currentVersion() {
        String prefix = "NyRoOS ";
        if (Build.DISPLAY.startsWith(prefix)) {
            String candidate = Build.DISPLAY.substring(prefix.length());
            if (validVersion(candidate)) return candidate;
        }
        return "";
    }

    private static boolean validVersion(String version) {
        return version.matches("[0-9]{1,6}(\\.[0-9]{1,6}){1,3}");
    }

    static boolean newer(String remote, String local) {
        if (!validVersion(remote) || !validVersion(local)) return false;
        String[] a = remote.split("\\."), b = local.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    static Catalog check(Context context, String address) throws Exception {
        URI feed = checkedUri(address, true);
        HttpURLConnection connection = open(feed, false);
        JSONObject envelope;
        try (InputStream input = connection.getInputStream()) {
            envelope = new JSONObject(read(input, MAX_MANIFEST));
        } finally {
            connection.disconnect();
        }
        byte[] payload = Base64.decode(envelope.getString("manifest"), Base64.DEFAULT);
        byte[] mac = Base64.decode(envelope.getString("signature"), Base64.DEFAULT);
        var verifier = Signature.getInstance("SHA256withRSA");
        try (InputStream key = context.getAssets().open("ota_public.der")) {
            verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(key.readAllBytes())));
        }
        verifier.update(payload);
        if (!verifier.verify(mac)) throw new IOException("Assinatura do catálogo inválida.");
        if (envelope.has("manifestSha256")) {
            String received = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
            if (!received.equals(envelope.getString("manifestSha256")))
                throw new IOException("SHA-256 do catálogo inválido.");
        }
        JSONObject item = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        String version = item.getString("version");
        if (!validVersion(version)) throw new IOException("Versão inválida no catálogo.");
        if (item.optInt("schema", 1) != 1) throw new IOException("Formato de catálogo incompatível.");
        List<AppRelease> apps = appReleases(context, feed, item.optJSONArray("artifacts"));
        Release system = systemRelease(feed, item, version);
        return new Catalog(system, apps);
    }

    private static Release systemRelease(URI feed, JSONObject item, String version) throws Exception {
        if (!item.optBoolean("otaAvailable", true) || currentVersion().isEmpty()) return null;
        boolean demo = item.getBoolean("demo");
        String device = item.getString("device");
        String base = item.getString("baseFingerprint");
        if (!device.equals(Build.DEVICE) || !base.equals(Build.FINGERPRINT)) return null;
        if (!newer(version, currentVersion())) return null;
        URI url = checkedUri(item.getString("url"), demo);
        if (!demo && !feed.getScheme().equals("https"))
            throw new IOException("O catálogo de uma versão de produção exige HTTPS.");
        long bytes = item.getLong("size");
        if (bytes <= 0 || bytes > MAX_PACKAGE) throw new IOException("Tamanho do pacote inválido.");
        String hash = item.getString("sha256");
        if (!hash.matches("[0-9a-f]{64}")) throw new IOException("SHA-256 do pacote inválido.");
        byte[] checksum = java.util.HexFormat.of().parseHex(hash);
        JSONArray lines = item.getJSONArray("changelog");
        if (lines.length() > 12) throw new IOException("Notas da versão acima do limite.");
        List<String> changes = new ArrayList<>();
        for (int index = 0; index < lines.length(); index++) {
            String line = lines.getString(index).trim();
            if (line.length() > 240) throw new IOException("Nota da versão acima do limite.");
            if (!line.isBlank()) changes.add(line);
        }
        return new Release(version, device, base, url, checksum, bytes, List.copyOf(changes), demo);
    }

    private static List<AppRelease> appReleases(Context context, URI feed, JSONArray artifacts) throws Exception {
        if (artifacts == null) return List.of();
        boolean localTest = "http".equals(feed.getScheme());
        if (artifacts.length() > 20) throw new IOException("Catálogo com muitos artefatos.");
        var results = new ArrayList<AppRelease>();
        var seen = new HashSet<String>();
        for (int i = 0; i < artifacts.length(); i++) {
            JSONObject item = artifacts.getJSONObject(i);
            String name = item.getString("name");
            String packageName = APP_IDS.get(name);
            if (packageName == null || !item.has("versionCode")) continue;
            if (!packageName.equals(item.getString("packageName")) || !seen.add(packageName))
                throw new IOException("Identidade de APK inválida no catálogo.");
            long version = item.getLong("versionCode");
            long size = item.getLong("size");
            String hash = item.getString("sha256");
            String versionName = item.getString("versionName");
            if (version <= 0 || size <= 0 || size > MAX_APK || !hash.matches("[0-9a-f]{64}")
                    || !validVersion(versionName))
                throw new IOException("Metadados do APK inválidos.");
            URI url = checkedUri(item.getString("url"), localTest);
            try {
                var installed = context.getPackageManager().getPackageInfo(packageName, 0);
                if (version > installed.getLongVersionCode()) {
                    results.add(new AppRelease(name.substring(0, name.length() - 4), packageName, version,
                            versionName, url, size, java.util.HexFormat.of().parseHex(hash)));
                }
            } catch (PackageManager.NameNotFoundException ignored) {
                // The catalog only updates NyRoOS apps already installed on this device.
            }
        }
        return List.copyOf(results);
    }

    static File download(Context context, Release release, IntConsumer progress) throws Exception {
        File cache = new File(context.getCacheDir(), "ota");
        File partial = downloadVerified(cache, release.url(), release.bytes(), release.hash(), progress);
        try {
            if (!release.demo()) verifyRecovery(partial, release);
            return saveDownload(partial, cache, "nyroos-" + release.version() + ".zip");
        } finally {
            Files.deleteIfExists(partial.toPath());
        }
    }

    static File downloadApp(Context context, AppRelease release, IntConsumer progress) throws Exception {
        File cache = new File(context.getCacheDir(), "app-updates");
        File partial = downloadVerified(cache, release.url(), release.bytes(), release.hash(), progress);
        try {
            var pm = context.getPackageManager();
            var installed = pm.getPackageInfo(release.packageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            var fetched = pm.getPackageArchiveInfo(partial.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (fetched == null || !release.packageName().equals(fetched.packageName)
                    || fetched.getLongVersionCode() != release.versionCode()
                    || !release.versionName().equals(fetched.versionName)
                    || release.versionCode() <= installed.getLongVersionCode()
                    || !signers(fetched.signingInfo).equals(signers(installed.signingInfo))) {
                throw new IOException("O APK não corresponde à identidade ou assinatura do app instalado.");
            }
            return saveDownload(partial, cache, release.packageName() + "-" + release.versionCode() + ".apk");
        } finally {
            Files.deleteIfExists(partial.toPath());
        }
    }

    private static HashSet<android.content.pm.Signature> signers(android.content.pm.SigningInfo info) throws IOException {
        if (info == null || info.getApkContentsSigners().length == 0)
            throw new IOException("Certificado do APK ausente.");
        return new HashSet<>(Arrays.asList(info.getApkContentsSigners()));
    }

    private static File saveDownload(File partial, File directory, String name) throws IOException {
        File saved = new File(directory, name);
        Files.move(partial.toPath(), saved.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return saved;
    }

    private static File downloadVerified(File cache, URI uri, long size, byte[] hash, IntConsumer progress)
            throws Exception {
        Files.createDirectories(cache.toPath());
        if (cache.getUsableSpace() < size) throw new IOException("Espaço insuficiente para o pacote.");
        File partial = File.createTempFile("incoming-", ".apk", cache);
        boolean valid = false;
        try {
            HttpURLConnection connection = open(uri, true);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                if (connection.getContentLengthLong() >= 0 && connection.getContentLengthLong() != size)
                    throw new IOException("Tamanho recebido difere do catálogo.");
                byte[] buffer = new byte[65536];
                long count = 0;
                int previous = -1;
                for (int length; (length = input.read(buffer)) != -1;) {
                    count += length;
                    if (count > size) throw new IOException("O pacote excedeu o tamanho declarado.");
                    output.write(buffer, 0, length);
                    digest.update(buffer, 0, length);
                    int percent = (int) (count * 100 / size);
                    if (percent != previous) {
                        progress.accept(percent);
                        previous = percent;
                    }
                }
                if (count != size || !MessageDigest.isEqual(digest.digest(), hash))
                    throw new IOException("A integridade do pacote não confere.");
                output.getFD().sync();
            } finally {
                connection.disconnect();
            }
            valid = true;
            return partial;
        } finally {
            if (!valid) Files.deleteIfExists(partial.toPath());
        }
    }

    static void install(Context context, Release release, File cache) throws Exception {
        if (release.demo() || !newer(release.version(), currentVersion())
                || !release.device().equals(Build.DEVICE) || !release.sourceBuild().equals(Build.FINGERPRINT)) {
            throw new IOException("O pacote não pode ser instalado nesta imagem.");
        }
        // Only hand recovery a file staged on its cache partition, after rechecking it there.
        final Path stage;
        try {
            stage = Files.createTempDirectory(Path.of("/cache/recovery"), "nyrocore-");
        } catch (IOException | SecurityException e) {
            throw new IOException("Esta imagem não disponibiliza um diretório de staging para o recovery.", e);
        }
        Path packageFile = stage.resolve("update.zip");
        try {
            Files.copy(cache.toPath(), packageFile);
            verifyRecovery(packageFile.toFile(), release);
            RecoverySystem.installPackage(context, packageFile.toFile());
        } finally {
            Files.deleteIfExists(packageFile);
            Files.deleteIfExists(stage);
        }
    }

    private static void verifyRecovery(File file, Release release) throws Exception {
        if (file.length() != release.bytes()) throw new IOException("Tamanho do pacote inválido.");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(Files.newInputStream(file.toPath()), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        if (!MessageDigest.isEqual(digest.digest(), release.hash()))
            throw new IOException("A integridade do pacote não confere.");
        RecoverySystem.verifyPackage(file, null, null);
        try (ZipFile zip = new ZipFile(file)) {
            var entry = zip.getEntry("META-INF/com/android/metadata");
            if (entry == null) throw new IOException("Metadados de recovery ausentes.");
            Map<String, String> fields;
            try (InputStream input = zip.getInputStream(entry)) {
                fields = read(input, 16384).lines().filter(line -> line.contains("="))
                        .map(line -> line.split("=", 2)).collect(Collectors.toMap(pair -> pair[0], pair -> pair[1]));
            }
            if (!"BLOCK".equals(fields.get("ota-type")) || !release.device().equals(fields.get("pre-device"))
                    || !release.sourceBuild().equals(fields.get("pre-build"))
                    || Long.parseLong(fields.getOrDefault("post-timestamp", "0")) <= Build.TIME / 1000L) {
                throw new IOException("O pacote não é um OTA de recovery compatível com esta build.");
            }
        }
    }

    static URI checkedUri(String text, boolean loopback) throws Exception {
        URI uri = new URI(text);
        String host = uri.getHost();
        boolean local = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        if (host == null || uri.getUserInfo() != null || uri.getFragment() != null ||
                !("https".equals(uri.getScheme()) || loopback && local && "http".equals(uri.getScheme()))) {
            throw new IOException("Use HTTPS ou um endereço de teste local.");
        }
        return uri;
    }

    private static HttpURLConnection open(URI uri, boolean asset) throws IOException {
        boolean github = asset && "https".equals(uri.getScheme()) && "github.com".equals(uri.getHost())
                && uri.getPath().matches("/[^/]+/[^/]+/releases/download/.+");
        for (int attempt = 0; attempt < 4; attempt++) {
            var connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(10000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            boolean keepOpen = false;
            try {
                int status = connection.getResponseCode();
                if (status == 200) {
                    keepOpen = true;
                    return connection;
                }
                if (!github || (status != 301 && status != 302 && status != 303 && status != 307 && status != 308))
                    throw new IOException("O servidor respondeu HTTP " + status + ".");
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("Redirecionamento de release inválido.");
                try {
                    URI next = checkedUri(uri.resolve(location).toString(), false);
                    String host = next.getHost();
                    if (!("release-assets.githubusercontent.com".equals(host) || "objects.githubusercontent.com".equals(host))
                            || (next.getPort() != -1 && next.getPort() != 443))
                        throw new IOException("Destino de download do GitHub inválido.");
                    uri = next;
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException("Redirecionamento de release inválido.", e);
                }
            } finally {
                if (!keepOpen) connection.disconnect();
            }
        }
        throw new IOException("Limite de redirecionamentos do GitHub excedido.");
    }

    static String read(InputStream input, int maximum) throws IOException {
        if (input == null) throw new IOException("Não foi possível abrir o documento.");
        var bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int length; (length = input.read(buffer)) != -1;) {
            if (bytes.size() + length > maximum) throw new IOException("O documento excedeu o limite.");
            bytes.write(buffer, 0, length);
        }
        return bytes.toString(StandardCharsets.UTF_8.name());
    }
}
