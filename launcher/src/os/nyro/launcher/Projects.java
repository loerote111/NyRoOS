package os.nyro.launcher;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class Projects {
    static final String PATH = "NyRoOS/projects.json";
    private static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_PROJECTS = 24;

    record Card(String name, String type, String target) {}
    record Check(String text, boolean healthy) {}

    static List<Card> read(Context context, Uri document) throws Exception {
        try (InputStream stream = document == null ? context.getAssets().open("projects.json")
                : context.getContentResolver().openInputStream(document)) {
            if (stream == null) throw new IOException("Arquivo indisponível");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int size; (size = stream.read(buffer)) != -1;) {
                if (bytes.size() + size > MAX_BYTES) throw new IOException("JSON acima de 64 KB");
                bytes.write(buffer, 0, size);
            }
            JSONArray rows = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name())).getJSONArray("projects");
            if (rows.length() > MAX_PROJECTS) throw new IOException("Máximo de 24 projetos");
            List<Card> cards = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String name = row.getString("name").trim();
                String type = row.getString("type");
                String target;
                if (type.equals("app")) {
                    target = row.getString("package");
                    if (!target.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) {
                        throw new IOException("Pacote inválido no item " + (i + 1));
                    }
                } else if (type.equals("url") || type.equals("api")) {
                    URL url = new URL(row.getString("url"));
                    if (!(url.getProtocol().equals("http") || url.getProtocol().equals("https"))
                            || url.getHost().isEmpty() || url.getUserInfo() != null) {
                        throw new IOException("Use uma URL HTTP ou HTTPS no item " + (i + 1));
                    }
                    target = url.toExternalForm();
                } else {
                    throw new IOException("Tipo inválido no item " + (i + 1));
                }
                if (name.isEmpty() || name.length() > 80) throw new IOException("Nome inválido no item " + (i + 1));
                cards.add(new Card(name, type, target));
            }
            return cards;
        }
    }

    static Check check(Card card) {
        HttpURLConnection connection = null;
        long start = SystemClock.elapsedRealtime();
        try {
            connection = (HttpURLConnection) new URL(card.target()).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setUseCaches(false);
            int code = connection.getResponseCode();
            return new Check("HTTP " + code + " · " + (SystemClock.elapsedRealtime() - start) + " ms",
                    code >= 200 && code < 300);
        } catch (SocketTimeoutException e) {
            return new Check("Tempo esgotado", false);
        } catch (IOException e) {
            return new Check("Sem resposta", false);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private Projects() {}
}
