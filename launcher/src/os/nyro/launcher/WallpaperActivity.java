package os.nyro.launcher;

import android.app.Activity;
import android.app.WallpaperManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

public final class WallpaperActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try (var image = getAssets().open("nyroos_wallpaper.png")) {
                WallpaperManager wallpaper = getSystemService(WallpaperManager.class);
                if (!wallpaper.isWallpaperSupported() || !wallpaper.isSetWallpaperAllowed()) {
                    throw new IllegalStateException("O Android não permite trocar o papel de parede.");
                }
                int id = wallpaper.setStream(image, null, false, WallpaperManager.FLAG_SYSTEM);
                Log.i("NyRoWallpaper", "Aplicado ao HOME. wallpaperId=" + id);
            } catch (Exception error) {
                Log.e("NyRoWallpaper", "Falha ao aplicar papel de parede HOME", error);
                runOnUiThread(() -> Toast.makeText(this, "Falha ao aplicar o papel de parede.", Toast.LENGTH_LONG).show());
            } finally {
                runOnUiThread(this::finish);
            }
        }, "nyro-wallpaper").start();
    }
}
