package os.nyro.launcher;

import android.content.ComponentName;
import android.content.pm.LauncherActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.util.LruCache;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class LauncherIcons {
    private final Handler main;
    private final int pixels;
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final Map<ComponentName, List<WeakReference<ImageView>>> pending = new HashMap<>();
    private final LruCache<ComponentName, Bitmap> cache = new LruCache<>(8 * 1024 * 1024) {
        @Override protected int sizeOf(ComponentName component, Bitmap bitmap) {
            return bitmap.getAllocationByteCount();
        }
    };

    LauncherIcons(Handler main, int pixels) {
        this.main = main;
        this.pixels = pixels;
    }

    void bind(ImageView image, LauncherActivityInfo app) {
        ComponentName key = app.getComponentName();
        image.setTag(key);
        Bitmap bitmap = cache.get(key);
        image.setImageBitmap(bitmap);
        if (bitmap != null) return;
        var target = new WeakReference<>(image);
        List<WeakReference<ImageView>> waiting = pending.get(key);
        if (waiting != null) {
            waiting.add(target);
            return;
        }
        List<WeakReference<ImageView>> request = new ArrayList<>();
        request.add(target);
        pending.put(key, request);
        worker.execute(() -> {
            Bitmap drawn = draw(app);
            main.post(() -> {
                if (pending.get(key) != request) return;
                pending.remove(key);
                if (drawn == null) return;
                cache.put(key, drawn);
                for (var entry : request) {
                    ImageView view = entry.get();
                    if (view != null && key.equals(view.getTag())) view.setImageBitmap(drawn);
                }
            });
        });
    }

    private Bitmap draw(LauncherActivityInfo app) {
        try {
            var drawable = app.getIcon(0);
            if (drawable == null) return null;
            Bitmap bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, pixels, pixels);
            drawable.draw(new Canvas(bitmap));
            return bitmap;
        } catch (RuntimeException error) {
            return null;
        }
    }

    void clear() {
        pending.clear();
        cache.evictAll();
    }

    void close() {
        clear();
        worker.shutdownNow();
    }
}
