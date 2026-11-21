package os.nyro.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

final class LauncherPreferences {
    static final String HIDDEN = "hidden";

    enum Option {
        GREETING("home.greeting", true, "Saudação", null),
        CLOCK("home.clock", true, "Relógio", null),
        DATE("home.date", true, "Data", null),
        BATTERY("home.battery", false, "Bateria", "Indicador no cabeçalho"),
        SEARCH("home.search", true, "Busca do Google", null),
        FAVORITES("home.favorites", true, "Favoritos", "Grade de atalhos escolhidos por você"),
        DOCK("home.dock", true, "Dock", "Atalhos na parte inferior"),
        DEVICE("hub.device", false, "Sobre o aparelho", "Versão do Android e arquitetura"),
        METRICS("hub.metrics", false, "Métricas sempre visíveis", "Memória, CPU, armazenamento e IP"),
        PROJECTS("hub.projects", true, "Projetos", "Atalhos e conexões configurados na Central");

        final String key, title, description;
        final boolean defaultValue;

        Option(String key, boolean defaultValue, String title, String description) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.title = title;
            this.description = description;
        }

        boolean enabled(SharedPreferences prefs) {
            return prefs.getBoolean(key, defaultValue);
        }
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    static Set<String> hiddenApps(SharedPreferences prefs) {
        return new LinkedHashSet<>(prefs.getStringSet(HIDDEN, Set.of()));
    }

    private LauncherPreferences() {}
}
