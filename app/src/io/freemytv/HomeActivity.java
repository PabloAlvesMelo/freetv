package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    /** Opens All Apps (a remote's All Apps button, RemoteKeys). */
    static final String EXTRA_ALL_APPS = "io.freemytv.ALL_APPS";
    private HomeView home;
    private WhatsNewView whatsNew;              // o painel de Novidades, enquanto aberto
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        SleepTimer.listen(this);
        SleepTimer.check(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        if (getIntent().getBooleanExtra(EXTRA_ALL_APPS, false)) home.showAllApps();
    }

    @Override protected void onResume() {
        super.onResume();
        // TCL's Android 11 records the home activity as translucent (occludesParent=false) whatever its theme says:
        // Android then keeps the app just left visible, and home tasks are layered under all others, so that app stays
        // on screen over ours. Opaque again on every resume (setTranslucent is public from Android 11).
        if (RemoteKeys.TCL && Build.VERSION.SDK_INT >= 30) setTranslucent(false);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) {
            home.resume();
            maybeWhatsNew();     // "Novidades": uma vez, após uma atualização
            if (whatsNew == null) maybeStartupApp();   // "Abrir ao iniciar": uma vez por boot, nunca a cada Home
        }
        Updates.tellOnce(this);   // an update the week's check got ready: said once
    }

    /**
     * "Novidades": after an update to a newer version, show the highlights once over the home. A fresh install (no
     * version recorded yet) only records the version, without a panel; the next version bump shows it.
     */
    private static boolean whatsNewChecked;   // once per process

    private void maybeWhatsNew() {
        if (whatsNewChecked) return;
        whatsNewChecked = true;
        String installed = Updates.installedVersion(this), shown = Prefs.whatsNewVersion();
        if (installed.isEmpty()) return;
        if (shown.isEmpty()) {           // fresh install: record, no panel
            Prefs.setWhatsNewVersion(installed);
            return;
        }
        if (Updates.compare(installed, shown) <= 0) return;   // not a newer version
        Prefs.setWhatsNewVersion(installed);                  // record now: show once
        if (WhatsNewView.ITEMS.length == 0) return;
        whatsNew = new WhatsNewView(this, this::dismissWhatsNew);
        addContentView(whatsNew, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        whatsNew.requestFocus();
        whatsNew.announceForAccessibility(WhatsNewView.TITLE);
    }

    private void dismissWhatsNew() {
        if (whatsNew == null) return;
        ViewGroup parent = (ViewGroup) whatsNew.getParent();
        if (parent != null) parent.removeView(whatsNew);
        whatsNew = null;
        if (home != null) home.requestFocus();
    }

    /**
     * "Abrir ao iniciar": open the chosen app once, shortly after boot, never on later Home presses (else Home would
     * trap the TV in it). Detected by elapsedRealtime (small only right after boot) because the launcher shows before
     * BOOT_COMPLETED arrives; `startupBoot` keeps it to once per boot even if :home restarts within the window.
     */
    private static final long STARTUP_WINDOW = 5 * 60_000L;   // boot-to-home is seconds; this is generous
    private static boolean startupTried;                      // once per process

    private void maybeStartupApp() {
        if (startupTried) return;
        startupTried = true;
        if (SystemClock.elapsedRealtime() > STARTUP_WINDOW) return;   // a cold start long after boot isn't a boot
        long boot = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        if (Math.abs(boot - Prefs.startupBoot()) < 60_000L) return;   // already opened it for this boot
        Prefs.setStartupBoot(boot);
        String pkg = Prefs.startupApp();
        if (pkg.isEmpty() || pkg.equals(getPackageName())) return;
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) return;   // uninstalled since it was chosen
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("couldn't open startup app " + pkg + ": " + e.getMessage());
        }
    }

    @Override protected void onPause() {
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home == null) return;
        if (intent.getBooleanExtra(EXTRA_ALL_APPS, false)) home.showAllApps();
        else home.reset(true);
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (whatsNew != null) {   // modal: the panel handles keys while it's up
            if (whatsNew.onKey(event)) return true;
            return super.dispatchKeyEvent(event);
        }
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
