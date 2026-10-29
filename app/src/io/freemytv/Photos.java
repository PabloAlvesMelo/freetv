package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Your own photos. A computer puts them in the app's external files folder (tools/photos.py: adb push to
 * Android/data/io.freemytv/files/photos, no permission needed) or, where adb can't reach it, through PhotoInbox; the
 * next time the home screen, the screensaver or the settings open, each one is imported once into the app's private
 * folder, turned upright (EXIF), cropped to 16:9 and re-encoded at the screensaver's size (which also drops its GPS and
 * other tags), and the original is deleted. While they are on (Home Settings > My Photos), the screensaver shows them
 * instead of the picks' photos, in the order they came, and without picks (no TMDB key) one of them is the home
 * screen's background, a new one every hour.
 */
final class Photos {
    private Photos() {}

    /** The screensaver's photo size (DreamView loads them 10% larger than the screen, for the slow zoom). */
    static final int W = 2112, H = 1188;
    /** At most this many (about 300 KB each); later ones wait in the folder until some are removed. */
    static final int MAX = 100;
    /** Smaller ones are icons and thumbnails, not photos. */
    private static final int MIN_SIDE = 640;

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "photos");
    }

    /** Where tools/photos.py puts them (the app's own external folder: adb can write there, and no permission). */
    private static File inbox(Context c) {
        return c.getExternalFilesDir("photos");
    }

    /**
     * Where PhotoInbox puts what adb writes when it can't reach the external folder: Android 11 keeps adb out of other
     * apps' Android/data folders (on Fire OS 8 too).
     */
    static File privateInbox(Context c) {
        return new File(c.getFilesDir(), "inbox");
    }

    /**
     * What's waiting in both inboxes, in name order: claims (another process importing) included, other hidden names
     * (a photo PhotoInbox is still writing) left out.
     */
    private static File[] sent(Context c) {
        List<File> out = new ArrayList<>();
        for (File in : new File[] {inbox(c), privateInbox(c)}) {
            File[] all = in == null ? null : in.listFiles();
            if (all != null) for (File f : all) if (!f.getName().startsWith(".") || f.getName().startsWith(CLAIM)) out.add(f);
        }
        File[] sorted = out.toArray(new File[0]);
        Arrays.sort(sorted, (a, b) -> original(a.getName()).compareTo(original(b.getName())));
        return sorted;
    }

    // MARK: - The imported photos

    private static long listedStamp = -1;
    private static List<File> listed = new ArrayList<>();

    /** The imported photos, in the order they came (one stat of the folder while nothing changed). */
    static synchronized List<File> files(Context c) {
        File d = dir(c);
        long stamp = d.exists() ? d.lastModified() : 0;
        if (stamp != listedStamp) {
            List<File> out = new ArrayList<>();
            File[] all = d.listFiles();
            if (all != null) for (File f : all) if (f.getName().endsWith(".jpg")) out.add(f);
            Collections.sort(out);
            listed = out;
            listedStamp = stamp;
        }
        return listed;
    }

    /** On in Home Settings and something imported. */
    static boolean on(Context c) {
        return Prefs.photos() && !files(c).isEmpty();
    }

    /** For the screensaver: the photos as picks (no title, no caption). */
    static List<Recs.Pick> picks(Context c) {
        List<Recs.Pick> out = new ArrayList<>();
        if (!on(c)) return out;
        List<File> list = files(c);
        for (int i = 0; i < list.size(); i++) {
            Recs.Pick p = new Recs.Pick();
            p.kind = "photo";
            p.id = i;
            p.photo = list.get(i);
            out.add(p);
        }
        return out;
    }

    // MARK: - The home screen's background

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static File built, building;
    private static Backdrop backdrop;

    /**
     * This hour's photo, ready to draw as the home screen's background, or null (off, or the first one still being
     * built). When the hour changes, the previous one stays until the new one is ready; then `ready` runs.
     */
    static synchronized Backdrop backdrop(Context c, Runnable ready) {
        if (!on(c)) {
            backdrop = null;
            built = null;
            return null;
        }
        List<File> list = files(c);
        File want = list.get((int) ((System.currentTimeMillis() / 3_600_000L) % list.size()));
        if (!want.equals(built) && !want.equals(building)) {
            building = want;
            worker.execute(() -> {
                Backdrop b = null;
                try {
                    Bitmap src = BitmapFactory.decodeFile(want.getPath());
                    if (src != null) b = Backdrop.make(src, true, true);
                } catch (RuntimeException | OutOfMemoryError e) {
                    AppLog.w("photos: background from " + want.getName() + ": " + e.getMessage());
                }
                synchronized (Photos.class) {
                    building = null;
                    if (b == null) return;
                    backdrop = b;
                    built = want;
                }
                main.post(ready);
            });
        }
        return backdrop;
    }

    // MARK: - Importing what the computer sent

    private static boolean importing;
    private static final List<Runnable> waiting = new ArrayList<>();

    /**
     * Imports what tools/photos.py put in the inbox, in the background, one photo at a time; `done` runs (on the main
     * thread) when something was imported, also when an import already under way (asked by another screen) brings it.
     * Cheap when the inbox is empty: one directory listing.
     */
    static void importInbox(Context c, Runnable done) {
        Context app = c.getApplicationContext();
        synchronized (Photos.class) {
            if (done != null) waiting.add(done);
            if (importing) return;
            importing = true;
        }
        worker.execute(() -> {
            int n = 0;
            try {
                n = importNow(app);
            } catch (RuntimeException e) {
                AppLog.w("photos: import failed: " + e.getMessage());
            }
            List<Runnable> tell;
            synchronized (Photos.class) {
                importing = false;
                tell = new ArrayList<>(waiting);
                waiting.clear();
            }
            if (n > 0) main.post(() -> { for (Runnable r : tell) r.run(); });
        });
    }

    /** A photo one process is importing is named ".claim-<when>-<its name>". */
    private static final String CLAIM = ".claim-";
    /** A claim this old was left by a process that died while importing: another one may take the photo. */
    private static final long CLAIM_MS = 10 * 60_000L;

    private static String original(String name) {
        if (!name.startsWith(CLAIM)) return name;
        int dash = name.indexOf('-', CLAIM.length());
        return dash < 0 ? name : name.substring(dash + 1);
    }

    private static boolean abandoned(String claimed) {
        int dash = claimed.indexOf('-', CLAIM.length());
        if (dash < 0) return false;
        try {
            long when = Long.parseLong(claimed.substring(CLAIM.length(), dash));
            return Math.abs(System.currentTimeMillis() - when) > CLAIM_MS;   // also one dated ahead: the clock went back
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static int importNow(Context c) {
        File[] all = sent(c);
        if (all.length == 0) return 0;
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) {
            AppLog.w("photos: can't create " + d);
            return 0;
        }
        // Counted on disk, not from files(): its cache is keyed on the folder's time, which another process's import
        // (or two within the same second) may not move.
        File[] imported = d.listFiles((dir, name) -> name.endsWith(".jpg"));
        int have = imported == null ? 0 : imported.length, count = 0;
        for (File sent : all) {
            if (!sent.isFile()) continue;
            String name = sent.getName();
            if (name.startsWith(CLAIM) && !abandoned(name)) continue;   // another process is importing it
            String original = original(name);
            if (!image(original.toLowerCase(Locale.ROOT))) {
                sent.delete();
                continue;
            }
            if (have + count >= MAX) {
                AppLog.w("photos: " + MAX + " already, " + original + " waits");
                continue;
            }
            // Claimed with a rename before it's read: on Android 11 the screensaver (main process) and the home screen
            // (:home) both import, and without it they could take the same photo twice.
            File src = new File(sent.getParentFile(), CLAIM + System.currentTimeMillis() + "-" + original);
            if (!sent.renameTo(src)) continue;
            // Named by import time (the order they came is the order the screensaver shows them) and by process (the
            // other one may import in the same millisecond); written as .part first, so a crash leaves no half photo.
            File out = new File(d, String.format(Locale.US, "%013d-%d.jpg", System.currentTimeMillis() + count,
                    android.os.Process.myPid()));
            File part = new File(d, out.getName() + ".part");
            try {
                Bitmap b = decode(src, W, H);
                if (b == null) {
                    AppLog.w("photos: skipped " + original + " (too small or unreadable)");
                } else {
                    try (OutputStream os = new FileOutputStream(part)) {
                        b.compress(Bitmap.CompressFormat.JPEG, 86, os);
                    }
                    b.recycle();
                    if (!part.renameTo(out)) throw new IOException("can't name " + out.getName());
                    count++;
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                part.delete();
                AppLog.w("photos: skipped " + original + ": " + e.getMessage());
            }
            src.delete();
        }
        AppLog.w("photos: imported " + count + ", " + (have + count) + " in all");
        if (count > 0) Prefs.setPhotos(true);   // new photos show, even if they had been turned off
        return count;
    }

    /** Apaga só estas fotos (Adicionais › Fotos › Escolher fotos para apagar). */
    static void remove(Context c, List<File> chosen) {
        for (File f : chosen) f.delete();
        synchronized (Photos.class) {
            listedStamp = -1;   // a pasta pode ter mudado no mesmo segundo: lista de novo
            if (built != null && chosen.contains(built)) {
                backdrop = null;
                built = null;
            }
        }
    }

    /** Deletes the imported photos and whatever is still waiting, half-sent ones included. */
    static void removeAll(Context c) {
        clear(dir(c));
        clear(privateInbox(c));
        File in = inbox(c);
        File[] queued = in == null ? null : in.listFiles();
        if (queued != null) for (File f : queued) f.delete();
        synchronized (Photos.class) {
            backdrop = null;
            built = null;
        }
    }

    private static boolean image(String name) {
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")
                || (Build.VERSION.SDK_INT >= 28 && (name.endsWith(".heic") || name.endsWith(".heif")));
    }

    /**
     * The photo upright (EXIF) in a w × h picture; null if too small or unreadable. Close to the screen's shape it fills
     * it (the edges trimmed a little); otherwise (portrait, square, 4:3, panoramas) it shows whole, over a blurred and
     * darkened copy of itself that fills the rest, as photo frames do: no face cut in half. The photo is decoded
     * subsampled while it still covers its place (about 4 MP at most).
     */
    private static Bitmap decode(File f, int w, int h) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        if (Math.min(bounds.outWidth, bounds.outHeight) < MIN_SIDE) return null;
        int degrees = rotation(f);
        boolean sideways = degrees == 90 || degrees == 270;
        int iw = sideways ? bounds.outHeight : bounds.outWidth, ih = sideways ? bounds.outWidth : bounds.outHeight;   // upright
        float photo = (float) iw / ih, screen = (float) w / h;
        boolean fill = Math.abs(photo / screen - 1) < 0.12f;   // 16:9 or nearly: fills the screen
        float scale = fill ? Math.max((float) w / iw, (float) h / ih) : Math.min((float) w / iw, (float) h / ih);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        // Halved while it still comes out at least 90% of its place on the screen (never much upscaled).
        while (1f / (opts.inSampleSize * 2) >= scale * 0.9f) opts.inSampleSize *= 2;
        while ((long) bounds.outWidth * bounds.outHeight > 4_000_000L * opts.inSampleSize * opts.inSampleSize) opts.inSampleSize *= 2;
        Bitmap src = BitmapFactory.decodeFile(f.getPath(), opts);
        if (src == null) return null;
        if (degrees != 0) {
            Matrix m = new Matrix();
            m.setRotate(degrees);
            Bitmap turned = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            if (turned != src) src.recycle();
            src = turned;
        }
        int sw = src.getWidth(), sh = src.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.DITHER_FLAG);
        float cover = Math.max((float) w / sw, (float) h / sh);
        if (!fill) {
            // The background: the photo covering the screen, shrunk to a few pixels and stretched back (a soft blur),
            // then darkened so the photo stands out.
            int bw = Math.max(1, w / 40), bh = Math.max(1, h / 40);
            Bitmap tiny = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            float tc = Math.max((float) bw / sw, (float) bh / sh);
            new android.graphics.Canvas(tiny).drawBitmap(src, null, centered(sw * tc, sh * tc, bw, bh), paint);
            c.drawBitmap(tiny, null, new android.graphics.RectF(0, 0, w, h), paint);
            tiny.recycle();
            c.drawColor(0x8C000000);
        }
        float s2 = fill ? cover : Math.min((float) w / sw, (float) h / sh);
        c.drawBitmap(src, null, centered(sw * s2, sh * s2, w, h), paint);
        src.recycle();
        return out;
    }

    /** A box of `bw` × `bh` centred in a `w` × `h` one. */
    private static android.graphics.RectF centered(float bw, float bh, int w, int h) {
        return new android.graphics.RectF((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
    }

    private static int rotation(File f) {
        try {
            switch (new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static void clear(File d) {
        File[] all = d.listFiles();
        if (all != null) for (File f : all) f.delete();
        d.delete();
    }
}
