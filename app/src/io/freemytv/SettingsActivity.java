package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Free My TV's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Home screen"
 * opens the Free My TV / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;
    private boolean saverOnly;   // a prévia veio do ícone do topo (sem o painel atrás)   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (openFireHome(getIntent())) return;
        Watchdog.install(this);
        Updates.schedule(this);   // the week's update check, once (a fresh install has had no boot or update yet)
        Ui.init(this);
        SleepTimer.listen(this);   // :home may start here, from the launcher icon
        SleepTimer.check(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    /**
     * Fire TV: Amazon keeps the Home button, so the app's icon opens the home screen (FireHomeActivity) directly;
     * the gear on the home screen opens these settings (no launcher category).
     */
    private boolean openFireHome(Intent intent) {
        if (!HomeMode.AMAZON || intent == null || !(intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                || intent.hasCategory(Intent.CATEGORY_LAUNCHER)) || intent.getBooleanExtra("screensaver", false)) {
            return false;
        }
        startActivity(new Intent(this, FireHomeActivity.class));
        finish();
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (view == null) return;
        view.resume();
        // Da tela inicial (o convite sem chave): direto na tela da chave do TMDB.
        if (getIntent().getBooleanExtra(EXTRA_TMDB_KEY, false)) {
            getIntent().removeExtra(EXTRA_TMDB_KEY);
            view.openKeyFromHome();
        }
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            saverOnly = getIntent().getBooleanExtra("saverOnly", false);
            getIntent().removeExtra("saverOnly");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openFireHome(intent)) return;
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        if (view != null) view.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (view != null) { view.stopKeyPage(); view.stopPhotoPage(); }
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system); with Watch from the
            // screensaver on, OK opens the title first.
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        boolean ok = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
                        if (ok && Prefs.saverWatch()) preview.watch();
                        closePreview();
                    }
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        if (saverOnly) {   // aberta pelo ícone do topo: ao sair, volta à tela inicial
            saverOnly = false;
            finish();
            return;
        }
        setContentView(view);
        view.resume();
    }

    static final String EXTRA_TMDB_KEY = "io.freemytv.TMDB_KEY";

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5,
                POWER = 6, TMDB_KEY = 7, PICKS = 8, PICK_FROM = 9, THEME_SCREEN = 10, LAYOUT_SCREEN = 11, PHOTO_SEND = 12, PHOTO_PICK = 13, SAVER_SCREEN = 14;
        // O menu da esquerda (sempre à vista); a opção escolhida abre à direita.
        private static final String[] MENU = {"Início", "Configurações", "Adicionais", "Ícones do Topo", "Programas",
                "Desligamento Automático", "Temas", "Layout", "Tela de descanso", "Atualização", "Configurações do Android", "Doação"};
        private static final int M_HOME = 0, M_SETTINGS = 1, M_EXTRAS = 2, M_TOP = 3, M_SHELF = 4, M_POWER = 5, M_THEMES = 6,
                M_LAYOUT = 7, M_SAVER = 8, M_UPDATES = 9, M_ANDROID = 10, M_DONATE = 11;
        // O Pix (copia e cola, sem valor: quem doa escolhe) que o QR da Doação leva.
        private static final String PIX = "00020126690014br.gov.bcb.pix0136facd2ea6-4867-4d16-86da-1329247ba3730207Free Tv5204000053039865802BR5919Pablo Alves De Melo6009Sao Paulo62290525REC6AE35B903AC473154229186304FF41";
        private Bitmap pixQr;   // feito na primeira vez que a Doação aparece
        // Os layouts da tela inicial; os "em breve" aparecem nos cards, sem poder escolher ainda.
        private static final String[][] LAYOUTS = {{"classico", "Clássico", ""}, {"cinema", "Cinema", ""},
                {"lateral", "Menu lateral", ""}, {"minimalista", "Minimalista", ""}, {"vitrine", "Vitrine", ""},
                {"flutuante", "Menu flutuante", ""}};
        private static final float MENU_X = 60, MENU_R = 640, MENU_TOP = 160, MENU_H = 58, MENU_GAP = 6;
        // Ajustes do Início em dois grupos: Configurações (0) e Adicionais (1).
        private int hsGroup;
        private static final float LIST_ROW_H = 68, LIST_ROW_GAP = 6;
        private static final int PICK_VISIBLE = 9;
        private static final float LIST_X = 700, LIST_R = 1824, LIST_TOP = 186, ROW_GAP = 8, CHOICE_H = 104;
        // The main menu: eight rows, then the comparison, the picks (with the Watchmode credit) and two notices, all on
        // one 1080 px screen: the rows share the 462 px that seven took.
        private static final float MAIN_TOP = 140, ROW_H = 53.75f, MAIN_GAP = 4;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        private final Paint neutralPaint = new Paint();
        private android.graphics.LinearGradient neutralBg;
        private int neutralTop;
        private final Paint brand = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cardFill = new Paint(Paint.ANTI_ALIAS_FLAG), cardRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        { cardRim.setStyle(Paint.Style.STROKE); }
        private int neutralH;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[12];
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor (also the top buttons' one, with editingTop): what it can hold (selected ones first, in
        // order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private boolean someServicesOff;   // is a streaming service on the TV switched off in Picks from (counted when the screen opens)
        private final List<String[]> services = new ArrayList<>();   // {key, name} of the streaming services on the TV (Picks from)
        private final List<String> shelfOrder = new ArrayList<>();
        private boolean editingTop;
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private static final int PICK_CITY = 0, PICK_LANGUAGE = -1, PICK_SLEEP = -2, PICK_PLACE = -3;
        /** Sleep Timer's pickers (PICK_SLEEP and below): their choices as {value, name}, and the value now. */
        private final List<String[]> choices = new ArrayList<>();
        private String choiceNow = "";
        private final List<String[]> cities = new ArrayList<>();
        private String cityStatus = "";
        private JSONObject updates = new JSONObject();   // Updates' file (the main process writes it), read in poll
        private long checkAskedAt, installAskedAt;       // OK on Updates, on the wall clock as the file's "at"
        private String installed = "";                   // this app's version (an update starts a new process)
        // TMDB key: the page a phone opens (KeyPage), its QR code and what the screen says, all made when they change,
        // never while drawing.
        private KeyPage keyPage;
        private Bitmap keyQr;
        private boolean keyChecking;
        private int[] keyRowIds = new int[0];
        private StaticLayout keyPurpose, keySteps, keyStatus;
        private String keyHelp;   // the purpose and the steps, for TalkBack
        private Paint keyHeading, keyUrl, qrPaint, qrSlot;
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown, wasBusy;
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private Toast whyToast;   // why the focused option is unavailable
        private String[] whyRows;  // each row's reason, worked out once per screen (null: again on the next frame)
        private int whyScreen = -1, whyScroll = -1;
        private int sleepLeft;     // the sleep timer's minutes left, read with the state, not on every frame
        private int whyState = -1;
        private String errorShown;
        private final Spoken spoken;
        private long startedAt, lastNanos, pendingAt, lastSky;
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private TextPaint notice, rowValueFit;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                sleepLeft = SleepTimer.minutesLeft(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                String updatesWas = updatesValue();
                updates = Updates.state(getContext());
                if (screen == MAIN && focus == M_UPDATES && !updatesWas.equals(updatesValue())) {
                    spoken.focusMoved();   // TalkBack reads the Updates row's new value
                }
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) pendingAt = 0;
                // What can change the reasons away from a key: a switch (or the watchdog's revert) and the overlay's and the
                // listener's checks.
                int whyNow = (freeActive() ? 1 : 0) | (Capabilities.refused(getContext(), Capabilities.OVERLAY) ? 2 : 0)
                        | (Capabilities.refused(getContext(), Capabilities.LISTENER) ? 4 : 0);
                if (whyNow != whyState) whyRows = null;
                whyState = whyNow;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                invalidate();
                handler.postDelayed(this, busy() || updating() ? 500 : 2000);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            setFocusable(true);
            setContentDescription("Melo Free TV");
            installed = Updates.installedVersion(a);
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen, and why where it's unavailable. */
        private String rowText(int row) {
            String why = unavailable(row);
            return why == null ? rowValueText(row) : rowValueText(row) + ", " + Ui.tr("Unavailable") + ". " + why;
        }

        private String rowValueText(int row) {
            switch (screen) {
                case MAIN:
                    return row < MENU.length ? MENU[row] : "";
                case HOME_CHOICE: {
                    String name = row == 0 ? "Melo Free TV, " + Ui.tr("no ads, with the new screensaver")
                            : "Original, " + Ui.tr("the TV's factory home screen");
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? Ui.tr(". In use") : "") + ", " + Ui.trf("{i} of {n}", "i", row + 1, "n", 2);
                }
                case HOME_SETTINGS:
                case REMOTE_KEYS:
                case PICKS:
                case POWER: {
                    boolean keys = screen == REMOTE_KEYS;
                    String[] labels = listLabels(), values = listValues();
                    int at = keys ? row : pickScroll + row;
                    return at >= labels.length ? "" : values[at].equals("›") ? labels[at] : labels[at] + ", " + values[at];   // "›" is only drawn
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", selected") : "")
                            + ", " + Ui.trf("{i} of {n}", "i", at + 1, "n", pickLabels.size());
                }
                case PICK_FROM: {
                    int at = pickScroll + row;
                    if (at >= services.size()) return "";
                    return services.get(at)[1] + ", " + onOff(!Prefs.serviceOff(services.get(at)[0])) + ", " + Ui.trf("{i} of {n}", "i", at + 1, "n", services.size());
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    if (editingTop) {
                        return app[1] + ", " + (place >= 0 ? Ui.trf("in the top buttons, place {n}", "n", place + 1)
                                : Ui.tr("not in the top buttons"));
                    }
                    return app[1] + ", " + (place >= 0 ? Ui.trf("on the shelf, place {n}", "n", place + 1)
                            : Ui.tr("not on the shelf"));
                }
                case PHOTO_SEND:
                    return row < photoRowIds.length ? photoLabel(photoRowIds[row]) : "";
                case PHOTO_PICK:
                    if (focus == 0) return pickButton();
                    return "Foto " + focus + " de " + pickFiles.size() + (marked.contains(pickFiles.get(focus - 1)) ? ", marcada" : "");
                case TMDB_KEY:
                    if (row >= keyRowIds.length) return "";
                    // TalkBack can't see the drawn text: the first row also says what the key is for and how to get one.
                    return row == 0 && keyHelp != null ? keyLabel(keyRowIds[0]) + ". " + keyHelp : keyLabel(keyRowIds[row]);
                default:
                    return "";
            }
        }

        private RectF rowBox(int row) {
            float top, height;
            if (screen == MAIN) {
                top = MENU_TOP + row * (MENU_H + MENU_GAP);
                focusBox.set(MENU_X * k, top * k, MENU_R * k, (top + MENU_H) * k);
                return focusBox;
            } else if (screen == HOME_CHOICE) {
                top = LIST_TOP + 20 + row * (CHOICE_H + ROW_GAP);
                height = CHOICE_H;
            } else {
                top = LIST_TOP + 20 + row * (LIST_ROW_H + LIST_ROW_GAP);
                height = LIST_ROW_H;
            }
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + height) * k);
            return focusBox;
        }

        void resume() {
            if (running) return;
            running = true;
            whyRows = null;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
            Photos.importInbox(getContext(), this::invalidate);   // photos a computer just sent
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
            if (keyPage != null) {   // the phone's page answers only while its code shows (not after Home, standby, another app)
                stopKeyPage();
                setKeyStatus(Ui.tr("This code has expired."), false);
                keyRowsChanged();
            }
            if (photoPage != null) {   // a página das fotos também: só enquanto o código está na tela
                stopPhotoPage();
                photoStatus = "O código expirou. Escolha Novo código.";
                photoRowsChanged();
            }
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy();
            for (Spring s : pop) moving |= !s.settled();
            if (moving || SystemClock.uptimeMillis() - lastSky >= 100) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            Symbols.warm(getContext(), "sparkles", 150 * k);
            Symbols.warm(getContext(), "chevron_right", 22 * k);
            Symbols.warm(getContext(), "check", 26 * k);
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowValueFit = new TextPaint(rowValue);
            rowSub = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue = Ui.numbers(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            keyHeading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            keyUrl = Ui.text(Ui.medium, 27 * k, 0xF2FFFFFF);
            qrPaint = new Paint();   // no filtering: each module stays a sharp square
            qrPaint.setFilterBitmap(false);
            qrSlot = new Paint(Paint.ANTI_ALIAS_FLAG);
            qrSlot.setColor(0x14FFFFFF);
        }

        private boolean busy() {
            return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private String busyText() {
            long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
            boolean stale = now < at || now - at > 10 * 60_000L;
            return stale ? "" : state.optString("busy");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return MENU.length;
                case LAYOUT_SCREEN: return LAYOUTS.length;
                case THEME_SCREEN: return Prefs.themeFixedMode() ? 1 + Themes.ALL.length : themeLabels().length;
                case HOME_CHOICE: return 2;
                case HOME_SETTINGS: return Math.min(SETTINGS_VISIBLE, settingLabels().length);
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                case POWER: return powerRows().length;
                case TMDB_KEY: return keyRowIds.length;
                case PHOTO_SEND: return photoRowIds.length;
                case PHOTO_PICK: return 1;
                case SAVER_SCREEN: return 2;   // o botão; as fotos têm as setas delas (PICK_COLS)
                case PICKS: return picksLabels().length;
                case PICK_FROM: return Math.min(PICK_VISIBLE, services.size());
                default: return Prefs.KEYS.length;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                pop[i].step(dt);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance(seconds);
            }
            // Fundo neutro no lugar do céu azul: grafite de dia, quase preto à noite (o texto do painel é claro).
            // A cor do tema em uso (nos temas claros, a mesma cor mais fechada: o texto do painel é claro).
            Themes.T th = HomeData.get(getContext()).theme(System.currentTimeMillis());
            int bgTop = th.setTop, bgBottom = th.setBottom;
            if (neutralBg == null || neutralTop != bgTop || neutralH != h) {
                neutralTop = bgTop;
                neutralH = h;
                neutralBg = new android.graphics.LinearGradient(0, 0, 0, h, bgTop, bgBottom, android.graphics.Shader.TileMode.CLAMP);
            }
            neutralPaint.setShader(neutralBg);
            c.drawRect(0, 0, w, h, neutralPaint);

            // À esquerda: o logo pequeno com o nome e a versão, e o menu.
            Symbols.draw(getContext(), c, "sparkles", (MENU_X + 30) * k, 96 * k, 44 * k, 0xEBFFFFFF);
            brand.setTypeface(title.getTypeface());
            brand.setTextSize(32 * k);
            brand.setColor(0xF2FFFFFF);
            c.drawText(versionName(getContext()), (MENU_X + 68) * k, 96 * k + Ui.halfCap(brand), brand);
            drawMenu(c);

            if (screen == MAIN) drawMain(c, seconds); else if (screen == HOME_CHOICE) drawChoice(c); else if (screen == PICKER) drawPicker(c); else if (screen == SHELF_EDIT) drawShelfEditor(c);
            else if (screen == TMDB_KEY) drawKey(c);
            else if (screen == PHOTO_SEND) drawPhotoSend(c);
            else if (screen == PHOTO_PICK) drawPhotoPick(c);
            else if (screen == PICK_FROM) drawServices(c);
            else if (screen == THEME_SCREEN && Prefs.themeFixedMode()) drawThemeCards(c);
            else if (screen == LAYOUT_SCREEN) drawLayoutCards(c);
            else drawList(c);
        }

        /** A opção do menu que está aberta à direita (-1 no próprio menu). */
        private int menuActive() {
            switch (screen) {
                case HOME_CHOICE: return M_HOME;
                case HOME_SETTINGS: case TMDB_KEY: return hsGroup == 0 ? M_SETTINGS : M_EXTRAS;
                case PHOTO_SEND: case PHOTO_PICK: return M_EXTRAS;
                case SAVER_SCREEN: return M_SAVER;
                case PICKS: case PICK_FROM: return M_SETTINGS;
                case PICKER: return pickFor <= PICK_SLEEP ? M_POWER : pickFor == PICK_CITY ? M_EXTRAS : M_SETTINGS;
                case SHELF_EDIT: return editingTop ? M_TOP : M_SHELF;
                case POWER: return M_POWER;
                case THEME_SCREEN: return M_THEMES;
                case LAYOUT_SCREEN: return M_LAYOUT;
                default: return -1;
            }
        }

        /** O menu da esquerda: no menu, a linha focada em branco; numa opção aberta, ela marcada de leve. */
        private void drawMenu(Canvas c) {
            int active = menuActive();
            for (int i = 0; i < MENU.length; i++) {
                float y = (MENU_TOP + i * (MENU_H + MENU_GAP)) * k;
                rowRect.set(MENU_X * k, y, MENU_R * k, y + MENU_H * k);
                float f = screen == MAIN ? drawRowBackground(c, i) : 0;
                if (i == active) {
                    shadow.setColor(0x26FFFFFF);
                    c.drawRoundRect(rowRect, 16 * k, 16 * k, shadow);
                }
                boolean off = screen == MAIN && unavailable(i) != null;
                rowLabel.setColor(off ? blend(0x73FFFFFF, 0x731C1C1E, f) : blend(i == active || screen == MAIN ? 0xF2FFFFFF : 0x99FFFFFF, INK, f));
                if (i == M_DONATE) rowLabel.setColor(blend(donateColor(), INK, f));   // a Doação em destaque; focada, preta como as outras
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(MENU[i], rowRect.left + 26 * k, base, rowLabel);
                float cw = Symbols.width(getContext(), "chevron_right", 20 * k);
                Symbols.draw(getContext(), c, "chevron_right", rowRect.right - 24 * k - cw / 2, rowRect.centerY(), 20 * k,
                        blend(0x66FFFFFF, 0x991C1C1E, f));
            }
        }

        /** No menu: à direita, o que a opção focada faz (e o estado do Início e da atualização). */
        private void drawMain(Canvas c, float seconds) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            float x = LIST_X * k + 30 * k, y = (LIST_TOP - 24) * k;
            c.drawText(MENU[focus], x, y, heading);
            String[] about = {
                    "Escolha a tela inicial: o Melo Free TV ou a original da TV.",
                    "Título, idioma, temperatura, sons, destaques, tela de descanso, assistente de voz e Chromecast.",
                    "Fotos, câmeras, clima, arquivos, lista IPTV e a chave TMDB.",
                    "Relógio, TV online, configurações e Wi-Fi: ligar, desligar e mudar a ordem.",
                    "Os apps da barra da tela inicial e a ordem deles.",
                    "Desliga a TV sozinha depois de um tempo.",
                    "Oito temas; no automático, um de dia e um de noite.",
                    "O jeito da tela inicial: Clássico, Minimalista e outros.",
                    "Quando ela começa (tempo sem usar o controle) e ver agora.",
                    "Procura uma versão nova no repositório do Melo Free TV.",
                    "Abre as configurações do Android TV.",
                    ""};   // Doação: o texto vai centralizado
            if (focus == M_DONATE) {
                drawDonate(c, y + 60 * k);
                return;
            }
            Paint text = Ui.text(Ui.regular, 26 * k, 0xB3FFFFFF);
            c.drawText(about[focus], x, y + 60 * k, text);
            String status = null;
            if (focus == M_HOME) {
                String busyText = busyText();
                status = busy() ? (busyText.isEmpty() ? Ui.tr("Starting…") : busyText) : "Em uso: " + (freeActive() ? "Melo Free TV" : Ui.tr("Original"));
                String error = state.optString("error");
                if (!busy() && !error.isEmpty()) status += "  ·  " + error;
                drawUninstallWarning(c, y + 180 * k);
            } else if (focus == M_UPDATES) {
                status = updatesValue() + "  ·  " + (Updates.ready(updates, installed) ? Ui.tr("OK installs it; Android asks first.") : Ui.tr("OK checks for a new version."));
            } else if (unavailable(focus) != null) {
                status = Ui.tr("Unavailable") + ". " + unavailable(focus);
            }
            if (status != null) {
                Paint st = Ui.text(Ui.regular, 26 * k, 0xF2FFFFFF);
                c.drawText(TextUtils.ellipsize(status, new TextPaint(st), (LIST_R - LIST_X - 60) * k, TextUtils.TruncateAt.END).toString(),
                        x, y + 110 * k, st);
            }
        }

        /** O destaque da Doação: âmbar, ou rosa-coral nos temas de fundo marrom (Âmbar e Mostarda), onde o âmbar some. */
        private int donateColor() {
            String id = HomeData.get(getContext()).theme(System.currentTimeMillis()).id;
            return id.equals("ambar") || id.equals("mostarda") ? 0xFFFF9AAE : AMBER;
        }

        /** Doação: o agradecimento e o QR do Pix, centralizados no painel da direita. */
        private void drawDonate(Canvas c, float y) {
            float cx = (LIST_X + LIST_R) / 2 * k;
            Paint text = Ui.text(Ui.regular, 26 * k, 0xF2FFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            Paint title = Ui.text(Ui.displaySemibold, 44 * k, donateColor());
            title.setTextAlign(Paint.Align.CENTER);
            c.drawText("Gostou do Melo Free TV?", cx, y, title);
            text.setColor(0xB3FFFFFF);
            c.drawText("Um sistema simples e rápido, gratuito e sem anúncios.", cx, y + 54 * k, text);
            c.drawText("Ajude a mantê-lo com um Pix de qualquer valor.", cx, y + 92 * k, text);
            if (pixQr == null) pixQr = qrBitmap(Qr.encode(PIX));
            int n = pixQr.getWidth();
            float side = Math.max(1, (int) (480 * k / n)) * n;
            float top = y + 150 * k;
            tmp.set(cx - side / 2 - 16 * k, top - 16 * k, cx + side / 2 + 16 * k, top + side + 16 * k);
            Paint card = Ui.text(Ui.regular, 1, 0xFFFFFFFF);   // o cartão branco atrás do QR
            c.drawRoundRect(tmp, 24 * k, 24 * k, card);
            tmp.set(cx - side / 2, top, cx + side / 2, top + side);
            c.drawBitmap(pixQr, null, tmp, qrPaint);
            Paint thanks = Ui.text(Ui.displaySemibold, 34 * k, donateColor());
            thanks.setTextAlign(Paint.Align.CENTER);
            c.drawText("Muito obrigado!", cx, top + side + 80 * k, thanks);
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private float drawRecs(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("RECOMMENDATIONS"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("No TMDB key: add yours in Home Settings > TMDB key.");
            else if (error.equals(Recs.NO_KNOWN_APPS)) line = Ui.tr("None of the TV's streaming apps can give picks.");
            else if (error.equals(Recs.NO_APPS)) line = Ui.trf("TMDB has none of the TV's apps for tmdb_region={region}.", "region", Recs.REGION);
            else if (!error.isEmpty()) line = Ui.trf("Error: {error}.", "error", error);
            else if (count > 0) line = Ui.trf("On · {n} titles from the TV's apps, new ones every hour.", "n", count);
            else line = Ui.tr("Fetching titles…");
            c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 78 * k, version);
            if (!Config.WATCHMODE_KEY.trim().isEmpty()) {
                c.drawText(Ui.tr("Links to the titles by Watchmode."), x, y + 108 * k, version);
            }
            version.setTextAlign(Paint.Align.CENTER);
            return y + (Config.WATCHMODE_KEY.trim().isEmpty() ? 78 : 108) * k;
        }

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -10 * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        private float drawComparison(Canvas c, float y) {
            float x = LIST_X * k + 30 * k, colA = LIST_R * k - 220 * k, colB = LIST_R * k - 30 * k;
            c.drawText(Ui.tr("COMPARISON"), x, y, header);
            cellValue.setColor(0x73FFFFFF);
            c.drawText(Ui.tr("Original"), colA, y, cellValue);
            c.drawText("Melo Free TV", colB, y, cellValue);
            cellValue.setColor(0xD9FFFFFF);
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            String[][] rows = {{Ui.tr("Home button"), "homeMs"}, {Ui.tr("Memory in use"), "memUsedMB"},
                    {Ui.tr("Free memory"), "memAvailableMB"}, {Ui.tr("Home screen memory"), "homePssMB"}};
            for (int i = 0; i < rows.length; i++) {
                float base = y + (46 + 40 * i) * k;
                c.drawText(rows[i][0], x, base, cellLabel);
                c.drawText(metric(a, rows[i][1]), colA, base, cellValue);
                c.drawText(metric(b, rows[i][1]), colB, base, cellValue);
            }
            return y + (46 + 40 * rows.length) * k;
        }

        private void drawNotices(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 2; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String line = ago(o.optLong("at")) + " · " + o.optString("text");
                c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + (42 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("None yet."), x, y + 42 * k, notice);
        }

        /** Home screen choice: Free My TV or the original, with a check on the one in use. */
        private void drawChoice(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Home Screen"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {{"Melo Free TV", Ui.tr("No ads, with the new screensaver")},
                    {Ui.tr("Original"), Ui.tr("The TV's factory home screen")}};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowSub.setColor(blend(0x8CFFFFFF, 0x991C1C1E, f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            c.drawText(Ui.tr("Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k, hint);
            drawUninstallWarning(c, (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 110) * k);
            // A velocidade e a memória medidas na troca: Original × Melo Free TV (uma linha abaixo do aviso).
            drawComparison(c, (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 260) * k);
        }

        /** O Android não avisa o app antes de desinstalar: sem voltar ao Original, a TV fica sem tela inicial. */
        private void drawUninstallWarning(Canvas c, float y) {
            Paint warn = Ui.text(Ui.regular, 24 * k, AMBER);
            float x = LIST_X * k + 30 * k;
            c.drawText("Atenção: antes de desinstalar o Melo Free TV, volte para Original aqui.", x, y, warn);
            c.drawText("Se desinstalar sem isso, a TV fica sem tela inicial e pode inutilizar o aparelho!", x, y + 36 * k, warn);
            c.drawText("Não nos responsabilizamos por danos causados por desinstalar o app sem esse cuidado.", x, y + 72 * k, warn);
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key) {
            if (key.equals("memUsedMB")) {   // em uso: o total da TV menos a livre
                if (m == null || !m.has("memTotalMB") || !m.has("memAvailableMB")) return "—";
                return megabytes(m.optLong("memTotalMB") - m.optLong("memAvailableMB"));
            }
            if (m == null || !m.has(key)) return "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return Ui.trf("{n} ms", "n", v);
            if (key.equals("memAvailableMB")) return megabytes(v);
            return Ui.trf("{n} MB", "n", v);
        }

        private static String megabytes(long v) {
            if (v >= 1024) return Ui.trf("{n} GB", "n", String.format(Ui.locale(), "%.2f", v / 1024.0));
            return Ui.trf("{n} MB", "n", v);
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("now");
            if (minutes < 60) return Ui.trf("{n} min ago", "n", minutes);
            if (minutes < 48 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
            return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / 1440);
        }

        private static String versionName(Context c) {
            try {
                return "Melo Free TV " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Melo Free TV";
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && screen == PHOTO_PICK) {
                        if (focus > PICK_COLS) focus -= PICK_COLS; else focus = 0;   // da primeira linha: o botão
                        pickReveal();
                        invalidate();
                        spoken.focusMoved();
                        return true;
                    }
                    if (down && screen == LAYOUT_SCREEN) {
                        if (focus >= 3) focus -= 3;
                        invalidate();
                        spoken.focusMoved();
                        return true;
                    }
                    if (down && screen == THEME_SCREEN && Prefs.themeFixedMode()) {
                        focus = focus > CARDS_PER_ROW ? focus - CARDS_PER_ROW : 0;
                        invalidate();
                        spoken.focusMoved();
                        return true;
                    }
                    if (down && focus > 0) focus--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && screen == PHOTO_PICK) {
                        int n = pickFiles.size();
                        if (focus == 0 && n > 0) focus = 1;
                        else if (focus > 0 && (focus - 1) / PICK_COLS < (n - 1) / PICK_COLS) focus = Math.min(n, focus + PICK_COLS);
                        pickReveal();
                        invalidate();
                        spoken.focusMoved();
                        return true;
                    }
                    if (down && screen == LAYOUT_SCREEN) {
                        if (focus + 3 < LAYOUTS.length) focus += 3;
                        invalidate();
                        spoken.focusMoved();
                        return true;
                    }
                    if (down && screen == THEME_SCREEN && Prefs.themeFixedMode()) {
                        int cur = Themes.index(Prefs.themeFixed()) + 1;
                        focus = focus == 0 ? cur : Math.min(Themes.ALL.length, focus + CARDS_PER_ROW);
                        invalidate();
                        spoken.focusMoved();
                        return true;
                    }
                    if (down && focus < rowCount() - 1) focus++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == PHOTO_PICK && focus >= 1) {
                        focus = Math.max(1, Math.min(pickFiles.size(), focus + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
                        pickReveal();
                        spoken.focusMoved();
                    } else if (down && screen == LAYOUT_SCREEN) {
                        focus = Math.max(0, Math.min(LAYOUTS.length - 1, focus + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
                        spoken.focusMoved();
                    } else if (down && screen == THEME_SCREEN && Prefs.themeFixedMode() && focus >= 1) {
                        int dx = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                        focus = Math.max(1, Math.min(Themes.ALL.length, focus + dx));   // cards: ← → andam por eles
                        spoken.focusMoved();
                    } else if (down && screen == SHELF_EDIT) {
                        moveShelfApp(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    } else if (down && screen == SAVER_SCREEN && focus == 0) {
                        stepSaverTime(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    } else if (down && (screen == THEME_SCREEN || screen == HOME_SETTINGS || screen == REMOTE_KEYS || screen == POWER || screen == PICKS || screen == PICK_FROM)) {
                        adjust(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    }
                    whyRows = null;   // a value or the rows' order changed: what each row needs may have too
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) {
                            activate();
                            whyRows = null;   // the same after OK
                        }
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            if (screen == PICKER && pickFor <= PICK_SLEEP) {
                                focus = powerFocus(pickFor == PICK_SLEEP ? P_SLEEP : P_WHERE);
                                pickScroll = 0;   // the picker's scroll isn't this short list's
                                screen = POWER;
                            } else if (screen == PICKER && (pickFor == PICK_CITY || pickFor == PICK_LANGUAGE)) {
                                focus = hsIndex(pickFor == PICK_CITY ? CITY_ROW : 0);
                                pickScroll = 0;   // both rows are on the list's first screen
                                screen = HOME_SETTINGS;
                            } else if (screen == PICKER) {
                                focus = pickFor - 1;
                                screen = REMOTE_KEYS;
                            } else if (screen == TMDB_KEY) {
                                closeKeyScreen();
                            } else if (screen == PHOTO_SEND) {
                                closePhotoScreen();
                            } else if (screen == PHOTO_PICK) {
                                closePhotoPick();
                            } else if (screen == SAVER_SCREEN) {
                                focus = M_SAVER;
                                screen = MAIN;
                            } else if (screen == PICKS) {
                                focus = hsIndex(PICKS_ROW);
                                pickScroll = 0;
                                screen = HOME_SETTINGS;
                            } else if (screen == PICK_FROM) {
                                countServicesOff();
                                pickScroll = 0;   // the Picks screen has two rows
                                focus = 1;
                                screen = PICKS;
                            } else {
                                focus = screen == HOME_SETTINGS ? (hsGroup == 0 ? M_SETTINGS : M_EXTRAS)
                                        : screen == SHELF_EDIT ? (editingTop ? M_TOP : M_SHELF)
                                        : screen == POWER ? M_POWER : screen == THEME_SCREEN ? M_THEMES : screen == LAYOUT_SCREEN ? M_LAYOUT : 0;
                                screen = MAIN;
                            }
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};
        /** Home Settings: language, temperature, theme, my photos, sounds, picks, watch from the screensaver, weather, city,
         *  forecast, now playing, quotes, one row per coin, then the TMDB key and updates (the last row). */
        private static final int PHOTOS_ROW = 3, PICKS_ROW = 5, SAVER_ROW = 6, WEATHER_ROW = 7, CITY_ROW = 8, FORECAST_ROW = 9,
                PLAYING_ROW = 10, TITLE_ROW = 11, CLOCK_ROW = 12, CAMS_ROW = 13, CAMS_EDIT_ROW = 14, CAMS_RESET_ROW = 15,
                FILES_ROW = 16, IPTV_ROW = 17, IPTV_BACK_ROW = 18, ASSIST_ROW = 19, CAST_ROW = 20, QUOTES_ROW = -1, COIN_ROWS = 21;   // sem cotações: sem linha de widget nem de moedas
        /** Home Settings rows on screen at once (what fits above the hints at 1080p); a longer list scrolls. */
        private static final int SETTINGS_VISIBLE = 10;

        private boolean dollar() {
            return "BRL".equals(Place.currency());
        }

        /** As linhas (reais) do grupo aberto: Configurações ou Adicionais. */
        private int[] hsMap() {
            List<Integer> r = new ArrayList<>();
            if (hsGroup == 0) {
                for (int x : new int[] {TITLE_ROW, 0, 1, 4, PICKS_ROW, SAVER_ROW, ASSIST_ROW, CAST_ROW}) r.add(x);
            } else {
                r.add(PHOTOS_ROW);
                r.add(CAMS_ROW);
                if (Prefs.camsOn()) { r.add(CAMS_EDIT_ROW); r.add(CAMS_RESET_ROW); }
                r.add(WEATHER_ROW);
                if (Prefs.weather()) { r.add(CITY_ROW); r.add(FORECAST_ROW); }   // só com o clima ligado
                r.add(FILES_ROW);
                r.add(IPTV_ROW);
                r.add(IPTV_BACK_ROW);
                r.add(keyRow());
            }
            int[] out = new int[r.size()];
            for (int i = 0; i < out.length; i++) out[i] = r.get(i);
            return out;
        }

        private int hsRow(int visible) {
            int[] m = hsMap();
            return visible >= 0 && visible < m.length ? m[visible] : -1;
        }

        private int hsIndex(int real) {
            int[] m = hsMap();
            for (int i = 0; i < m.length; i++) if (m[i] == real) return i;
            return 0;
        }

        private String[] settingLabels() {
            String[] all = allSettingLabels();
            int[] m = hsMap();
            String[] out = new String[m.length];
            for (int i = 0; i < m.length; i++) out[i] = all[m[i]];
            return out;
        }

        private String[] settingValues() {
            String[] all = allSettingValues();
            int[] m = hsMap();
            String[] out = new String[m.length];
            for (int i = 0; i < m.length; i++) out[i] = all[m[i]];
            return out;
        }

        private String[] allSettingLabels() {
            List<String> l = new ArrayList<>();
            l.add(Ui.tr("Language"));
            l.add(Ui.tr("Temperature"));
            l.add(Ui.tr("Theme"));
            l.add(Ui.tr("My Photos"));
            l.add(Ui.tr("Navigation sounds"));
            l.add(Ui.tr("Picks"));
            l.add(Ui.tr("Watch from the screensaver"));
            l.add(Ui.tr("Weather widget"));
            l.add(Ui.tr("City"));
            l.add(Ui.tr("Forecast"));
            l.add(Ui.tr("Now Playing widget"));
            l.add("Título");
            l.add("Relógio no topo");
            l.add("Câmeras");
            l.add("Editar câmeras");
            l.add("Apagar dados das câmeras");
            l.add("Arquivos (FX)");
            l.add("Lista IPTV");
            l.add("Voltar à lista anterior");
            l.add("Assistente de voz");
            l.add("Chromecast embutido");
            l.add(Ui.tr("TMDB key"));
            l.add(Ui.tr("Updates"));
            return l.toArray(new String[0]);
        }

        /** The Updates row: the last one, after the coins and the TMDB key. */
        private int updatesRow() {
            return allSettingLabels().length - 1;
        }

        private int keyRow() {
            return updatesRow() - 1;
        }

        /** The TMDB key row: "Not set", or the key's last 4 characters (all that's ever shown of it). */
        private static String keyValue() {
            String key = Recs.key();
            return key.length() < 4 ? Ui.tr("Not set") : "•••• " + key.substring(key.length() - 4);
        }

        private String[] picksLabels() {
            return new String[] {Ui.tr("Picks per tab"), Ui.tr("Picks from")};
        }

        private String[] picksValues() {
            return new String[] {String.valueOf(Prefs.cards()), someServicesOff ? Ui.tr("services|Some") : Ui.tr("services|All")};
        }

        private String[] allSettingValues() {
            String[] labels = allSettingLabels();
            String[] v = new String[labels.length];
            String lang = Prefs.language();
            v[0] = lang.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(lang);
            String unit = Prefs.temperature();
            v[1] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("unit|Automatic");
            String theme = Prefs.theme();
            v[2] = theme.equals("light") ? Ui.tr("Light") : theme.equals("dark") ? Ui.tr("Dark") : Ui.tr("Automatic");
            int photos = Photos.files(getContext()).size();
            v[PHOTOS_ROW] = photos == 0 ? Ui.tr("photos|None")
                    : Prefs.photos() ? Ui.plural("plural|one:{n} photo|other:{n} photos", photos) : Ui.tr("Off");
            v[4] = onOff(Prefs.sounds());
            v[PICKS_ROW] = "›";   // opens its own screen: Picks per tab and Picks from
            v[SAVER_ROW] = onOff(Prefs.saverWatch());
            v[WEATHER_ROW] = onOff(Prefs.weather());
            v[CITY_ROW] = !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city();
            v[FORECAST_ROW] = Prefs.forecast().equals("days") ? Ui.tr("Next days") : Ui.tr("Next hours");
            v[PLAYING_ROW] = onOff(Prefs.nowPlaying());
            v[TITLE_ROW] = Prefs.title().isEmpty() ? "Melo TV" : Prefs.title();
            v[CLOCK_ROW] = onOff(Prefs.topClock());
            v[CAMS_ROW] = onOff(Prefs.camsOn());
            v[CAMS_EDIT_ROW] = "›";
            int cams = Onvif.saved().size();
            v[CAMS_RESET_ROW] = cams == 0 ? "—" : cams == 1 ? "1 câmera" : cams + " câmeras";
            v[FILES_ROW] = onOff(Prefs.files());
            v[ASSIST_ROW] = onOff(Prefs.assistant());
            v[CAST_ROW] = onOff(Prefs.cast());
            v[IPTV_ROW] = iptvStatus.isEmpty() ? "Baixar ›" : iptvStatus;
            v[IPTV_BACK_ROW] = !iptvBackStatus.isEmpty() ? iptvBackStatus : iptvBackup().exists() ? "Restaurar ›" : "Sem backup";
            // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
            for (int i = COIN_ROWS; i < v.length - 2; i++) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS));
            v[v.length - 2] = keyValue();
            v[v.length - 1] = updatesValue();
            return v;
        }

        /** "auto", then English and every language with a catalogue. */
        private static String[] languageChoices() {
            String[] all = Ui.languages();
            String[] v = new String[all.length + 1];
            v[0] = "auto";
            System.arraycopy(all, 0, v, 1, all.length);
            return v;
        }

        /**
         * What the focused screen's row `i` needs from the TV (Capabilities.NEED_…), or null. A new option that needs
         * something adds its line here; drawing, keys and TalkBack then treat it as unavailable where the TV lacks it.
         */
        private String need(int i) {
            switch (screen) {
                case MAIN:
                    return i == M_POWER ? Capabilities.NEED_ADB : null;
                case HOME_SETTINGS:
                    return hsRow(pickScroll + i) == PLAYING_ROW ? Capabilities.NEED_LISTENER : null;
                case POWER: {
                    int id = powerRows()[i];
                    return id == P_WHERE ? Capabilities.NEED_OVERLAY : null;
                }
                case SHELF_EDIT: {   // only to add it: one already among the buttons can still move or go
                    String key = shelfRows.get(pickScroll + i)[0];
                    return editingTop && HomeApps.TOP_LIVE.equals(key) && !shelfOrder.contains(key) ? Capabilities.NEED_LIVE_TV : null;
                }
                default:
                    return null;
            }
        }

        /** Why row `i` can't be used here, for the person to read; null where it works. */
        private String unavailable(int i) {
            int n = rowCount();
            if (i < 0 || i >= n) return null;
            if (whyRows == null || whyRows.length != n || whyScreen != screen || whyScroll != pickScroll) {
                whyRows = new String[n];
                whyScreen = screen;
                whyScroll = pickScroll;
                for (int r = 0; r < n; r++) {
                    String need = need(r);
                    whyRows[r] = screen == HOME_SETTINGS && hsRow(pickScroll + r) >= COIN_ROWS && hsRow(pickScroll + r) < keyRow() && !Prefs.quotes()
                            ? Ui.tr("Turn on the Quotes widget to pick its coins.")
                            : need == null ? null : Capabilities.missing(getContext(), need);
                }
            }
            return whyRows[i];
        }

        /** OK on an unavailable row: a toast says why; false if it isn't unavailable. */
        private boolean tellWhy() {
            String why = unavailable(focus);
            if (whyToast != null) whyToast.cancel();
            whyToast = why == null ? null : Toast.makeText(getContext(), why, Toast.LENGTH_LONG);
            if (whyToast != null) whyToast.show();
            return why != null;
        }

        /**
         * Sleep Timer's rows, by what they set: the timer, whether it shows on screen and (where it shows over other
         * apps) where. Values are picked from lists (OK), as on the TV's own settings; ← → step them.
         */
        private static final int P_SLEEP = 0, P_SHOW = 1, P_WHERE = 2;

        private int[] powerRows() {
            return Prefs.sleepShown() ? new int[] {P_SLEEP, P_SHOW, P_WHERE} : new int[] {P_SLEEP, P_SHOW};
        }

        private int powerRow() {
            int[] rows = powerRows();
            return rows[Math.min(focus, rows.length - 1)];
        }

        private int powerFocus(int id) {
            int[] rows = powerRows();
            for (int i = 0; i < rows.length; i++) if (rows[i] == id) return i;
            return 0;
        }

        private String[] powerLabels() {
            int[] rows = powerRows();
            String[] out = new String[rows.length];
            for (int i = 0; i < rows.length; i++) {
                switch (rows[i]) {
                    case P_SLEEP: out[i] = Ui.tr("Sleep Timer"); break;
                    case P_SHOW: out[i] = Ui.tr("Show Time Left"); break;
                    default: out[i] = Ui.tr("Position");
                }
            }
            return out;
        }

        private String[] powerValues() {
            int[] rows = powerRows();
            String[] out = new String[rows.length];
            for (int i = 0; i < rows.length; i++) {
                switch (rows[i]) {
                    case P_SLEEP: out[i] = sleepLeft == 0 ? Ui.tr("Off") : Ui.trf("{time} left", "time", SleepTimer.duration(sleepLeft)); break;
                    case P_SHOW: out[i] = onOff(Prefs.sleepShown()); break;
                    default: out[i] = SleepOverlay.placeName(Prefs.sleepPlace());
                }
            }
            return out;
        }

        /** OK on a Sleep Timer row: its list of choices, or (on a switch) the other value. */
        private void activatePower() {
            Context c = getContext();
            List<String[]> list = new ArrayList<>();
            switch (powerRow()) {
                case P_SLEEP:
                    list.add(new String[] {"0", Ui.tr("Off")});
                    for (int m : new int[] {5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240}) {
                        list.add(new String[] {String.valueOf(m), SleepTimer.duration(m)});
                    }
                    int left = SleepTimer.minutesLeft(c);
                    openChoices(PICK_SLEEP, list, String.valueOf(left));
                    return;
                case P_WHERE:
                    for (int i = 0; i < SleepOverlay.PLACES; i++) list.add(new String[] {String.valueOf(i), SleepOverlay.placeName(i)});
                    openChoices(PICK_PLACE, list, String.valueOf(Prefs.sleepPlace()));
                    return;
                default:
                    adjust(1);
            }
        }

        private void openChoices(int which, List<String[]> list, String now) {
            choices.clear();
            choices.addAll(list);
            choiceNow = now;
            openPicker(which);
        }

        /** OK in a Sleep Timer picker: applied, and back to its row. */
        private void pickPower() {
            Context c = getContext();
            String[] choice = choices.get(pickScroll + focus);
            int row = pickFor == PICK_SLEEP ? P_SLEEP : P_WHERE;
            if (pickFor == PICK_SLEEP) {
                SleepTimer.set(c, Integer.parseInt(choice[0]));
                sleepLeft = SleepTimer.minutesLeft(c);
            } else {
                Prefs.setSleepPlace(Integer.parseInt(choice[0]));
                SleepOverlay.update(c);
            }
            pickScroll = 0;
            screen = POWER;
            focus = powerFocus(row);
        }

        private String[] listLabels() {
            return screen == REMOTE_KEYS ? keyLabels() : screen == POWER ? powerLabels() : screen == PICKS ? picksLabels()
                    : screen == THEME_SCREEN ? themeLabels() : screen == SAVER_SCREEN ? new String[] {"Iniciar após", "Ver agora"}
                    : settingLabels();
        }

        private String[] listValues() {
            return screen == REMOTE_KEYS ? keyValues() : screen == POWER ? powerValues() : screen == PICKS ? picksValues()
                    : screen == THEME_SCREEN ? themeValues() : screen == SAVER_SCREEN ? new String[] {saverTimeLabel(saverMs), "›"}
                    : settingValues();
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("On") : Ui.tr("Off");
        }

        private String[] keyLabels() {
            if (RemoteKeys.HISENSE) return new String[] {Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                    Ui.tr("Disney+ button"), Ui.tr("All Apps button"), Ui.tr("Settings button"), Ui.tr("Profile button")};
            if (RemoteKeys.STEM_NETFLIX) return new String[] {Ui.tr("Netflix button")};
            if (RemoteKeys.KTC) return new String[] {Ui.tr("Tabii button")};
            if (RemoteKeys.XIAOMI) return new String[] {Ui.tr("Mi button"), Ui.tr("YouTube button"), Ui.tr("PatchWall button"),
                    Ui.tr("Okko button"), Ui.tr("VK button"), Ui.tr("Rutube button"), Ui.tr("KION button"),
                    Ui.tr("Wink button"), Ui.tr("IVI button")};
            if (RemoteKeys.SONY) return new String[] {Ui.tr("BRAVIA CORE button"),
                    Ui.tr("Netflix button"), Ui.tr("Disney+ button"),
                    Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                    Ui.tr("Crunchyroll button"), Ui.tr("Google Play button")};
            return new String[] {Ui.tr("Netflix button"), Ui.tr("YouTube button"),
                    Ui.tr("Prime Video button"), Ui.tr("TCL Channel button")};
        }

        private String[] keyValues() {
            String[] v = new String[Prefs.KEYS.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Not installed");
            }
            return v;
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            List<String[]> found = new ArrayList<>();
            for (String[] app : HomeApps.launchable(getContext())) {
                if (!app[0].equals(getContext().getPackageName())) found.add(app);
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            // On Sony and Xiaomi, "nothing" leaves the key to the TV, which opens its usual app.
            appLabels.add(RemoteKeys.SONY ? Ui.tr("Sony default") : RemoteKeys.XIAOMI ? Ui.tr("Xiaomi default") : Ui.tr("Nothing"));
            // On Xiaomi the Mi button is the remote's Home button: it can open this home screen.
            if (RemoteKeys.XIAOMI) {
                appPkgs.add(HomeMode.PKG);
                appLabels.add("Free My TV");
            }
            if (RemoteKeys.HISENSE) {
                appPkgs.add(Prefs.ALL_APPS);
                appLabels.add(Ui.tr("All Apps"));
            }
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            // Refused or without Free My TV mode, Now Playing can still be turned off (and the refusal is then forgotten).
            boolean turnOff = screen == HOME_SETTINGS && hsRow(pickScroll + focus) == PLAYING_ROW && Prefs.nowPlaying();
            if (unavailable(focus) != null && !turnOff) return;
            if (screen == PICKS) {
                if (focus == 0) {
                    int n = Prefs.cards() - 3, span = Recs.MAX_CARDS - 3 + 1;
                    Prefs.setCards(3 + (n + dir + span) % span);
                }
                return;
            }
            if (screen == POWER) {
                int row = powerRow();
                if (row == P_SLEEP) {
                    SleepTimer.step(getContext(), dir);
                    sleepLeft = SleepTimer.minutesLeft(getContext());
                } else if (row == P_SHOW) {
                    Prefs.setSleepShown(!Prefs.sleepShown());
                    SleepOverlay.update(getContext());
                } else if (row == P_WHERE) {
                    Prefs.setSleepPlace((Prefs.sleepPlace() + dir + SleepOverlay.PLACES) % SleepOverlay.PLACES);
                    SleepOverlay.update(getContext());
                }
                focus = powerFocus(row);   // rows come and go with the switches above them
                return;
            }
            if (screen == PICK_FROM) {
                toggleService();
                return;
            }
            if (screen == REMOTE_KEYS) {
                int n = appPkgs.size();
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[focus]));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setKeyApp(Prefs.KEYS[focus], appPkgs.get(next));
                HomeMode.ensureRemoteService(getContext(), 3_000);   // all on Nothing: the service goes off
                return;
            }
            if (screen == THEME_SCREEN) {
                adjustTheme(dir);
                return;
            }
            int row = hsRow(pickScroll + focus);
            switch (row) {
                case 0: break;   // OK opens the language picker.
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case 2: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    SleepOverlay.update(getContext());   // the pill's glass follows
                    break;
                }
                case PHOTOS_ROW:
                    if (Photos.files(getContext()).isEmpty()) return;
                    Prefs.setPhotos(!Prefs.photos());
                    return;
                case 4: Prefs.setSounds(!Prefs.sounds()); break;
                case PICKS_ROW: return;
                case SAVER_ROW: Prefs.setSaverWatch(!Prefs.saverWatch()); break;
                case WEATHER_ROW: Prefs.setWeather(!Prefs.weather()); break;
                case CITY_ROW: return;
                case TITLE_ROW: return;
                case CLOCK_ROW: Prefs.setTopClock(!Prefs.topClock()); break;
                case CAMS_ROW: toggleCams(); break;
                case CAMS_EDIT_ROW: case CAMS_RESET_ROW: case IPTV_ROW: case IPTV_BACK_ROW: return;   // agem só no OK
                case FILES_ROW: toggleFiles(); break;
                case ASSIST_ROW:   // o botão do microfone: o Google Assistente ligado ou desativado no sistema
                    Prefs.setAssistant(!Prefs.assistant());
                    HomeMode.assistant(getContext());
                    break;
                case CAST_ROW:   // transmitir do celular: o app do Chromecast ligado ou desativado no sistema
                    Prefs.setCast(!Prefs.cast());
                    HomeMode.cast(getContext());
                    break;
                case FORECAST_ROW:   // both are in memory: nothing to fetch
                    Prefs.setForecast(Prefs.forecast().equals("days") ? "hours" : "days");
                    return;
                case PLAYING_ROW:   // the listener follows the widget: allowed while it's on, taken back when it goes off
                    Prefs.setNowPlaying(!Prefs.nowPlaying());
                    HomeMode.nowPlayingAccess(getContext());
                    break;
                case QUOTES_ROW: Prefs.setQuotes(!Prefs.quotes()); break;
                default:
                    if (row >= keyRow() || !Prefs.quotes()) return;   // the key and Updates act on OK only
                    Prefs.setCoin(row - COIN_ROWS, !Prefs.coin(row - COIN_ROWS));
                    break;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        private void askCity() {
            if (Ui.tr("Searching…").equals(cityStatus)) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Type the city's name"));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("City"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Search"), (d, which) -> searchCity(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchCity(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        /** O título da tela inicial (no lugar de "Para você"); vazio volta ao padrão. */
        private void askTitle() {
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setText(Prefs.title());
            field.setHint("Melo TV");
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            Runnable save = () -> {
                Prefs.setTitle(field.getText().toString().trim());
                invalidate();
                spoken.focusMoved();
            };
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Título")
                    .setView(field)
                    .setPositiveButton("OK", (d, which) -> save.run())
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                save.run();
                return true;
            });
            dialog.show();
        }

        // MARK: - Updates (GitHub's releases)

        /**
         * OK on Updates: installs one that's ready, else checks now. Both run as jobs in the main process, as the week's
         * check does, so one process writes the state; this screen reads it in poll.
         */
        private void updateNow() {
            Context c = getContext();
            updates = Updates.state(c);
            long now = System.currentTimeMillis();
            if (Updates.ready(updates, installed)) {
                if (now - installAskedAt < 5000) return;   // Android's installer is on its way
                installAskedAt = now;
                ModeJob.schedule(c, ModeJob.INSTALL, "", 0);
            } else {
                if (Updates.busy(updates) || checkAsked()) return;
                checkAskedAt = now;
                ModeJob.schedule(c, ModeJob.UPDATE, "", 0);
            }
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, 500);
            invalidate();
            spoken.focusMoved();
        }

        /** OK asked for a check that hasn't started yet (the job writes "checking" as it starts). */
        private boolean checkAsked() {
            long now = System.currentTimeMillis();
            return now - checkAskedAt < 10_000 && updates.optLong("at") < checkAskedAt;
        }

        /** The Updates row: what the last check found, or "Checking…" from OK until the check starts. */
        private String updatesValue() {
            return checkAsked() ? Ui.tr("Checking…") : Updates.summary(updates, installed);
        }

        /** A check or an install on its way or under way: poll reads the file more often. */
        private boolean updating() {
            return Updates.busy(updates) || System.currentTimeMillis() - Math.max(checkAskedAt, installAskedAt) < 10_000;
        }

        // MARK: - TMDB key (from a phone through KeyPage's QR code, or typed with the remote)

        private final Paint keyOk = new Paint(Paint.ANTI_ALIAS_FLAG);   // o círculo verde da chave ativa
        { keyOk.setColor(0xFF34C759); }
        private static final int K_NEW = 0, K_TYPE = 1, K_REMOVE = 2, K_HOME = 3;
        private static final float QR_SIDE = 300;

        private static String keyLabel(int id) {
            if (id == K_HOME) return "Ir para o Início";
            if (id == K_NEW && keyActive()) return "Trocar a chave pelo celular";
            return id == K_NEW ? Ui.tr("New code") : id == K_TYPE ? Ui.tr("Type it with the remote") : Ui.tr("Remove the key");
        }

        /** Já há uma chave e nenhum código aberto: a tela mostra que está tudo certo, sem o QR nem os passos. */
        private static boolean keyActive() {
            return !Recs.key().isEmpty();
        }

        private boolean keyRemoved;   // a chave acabou de ser removida: a tela oferece voltar ao Início

        private boolean keyDone() {
            return keyActive() && keyPage == null && !keyChecking;
        }

        void openKeyFromHome() {
            if (getWidth() == 0 || notice == null) {   // ainda sem tamanho (as fontes nascem no layout): tenta no próximo quadro
                post(this::openKeyFromHome);
                return;
            }
            hsGroup = 1;   // Adicionais: onde a chave fica (o Voltar cai lá)
            openKeyScreen();
            invalidate();
        }

        private void openKeyScreen() {
            screen = TMDB_KEY;
            pickScroll = 0;
            focus = 0;
            keyStatus = null;
            keyRemoved = false;
            keyRowIds = new int[0];
            // The steps, in the TV's language, beside the code; the phone's page says the same.
            String steps = Ui.tr("Free, and it takes two minutes:") + "\n1. " + Ui.tr("Create an account at themoviedb.org.")
                    + "\n2. " + Ui.tr("Open Settings → API and request a developer key.")
                    + "\n3. " + Ui.tr("Scan this code with your phone and paste the API Read Access Token (or the API Key) there.");
            int width = Math.round((LIST_R - LIST_X - 60 - QR_SIDE - 40) * k);
            keySteps = StaticLayout.Builder.obtain(steps, 0, steps.length(), notice, width).setLineSpacing(6 * k, 1).build();
            String purpose = Ui.tr("The key brings the picks: what's trending on your streaming apps, with their photos on the screensaver.");
            keyPurpose = StaticLayout.Builder.obtain(purpose, 0, purpose.length(), notice, Math.round((LIST_R - LIST_X - 60) * k))
                    .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
            keyHelp = purpose + " " + steps.replace('\n', ' ');
            if (keyActive()) keyRowsChanged(); else startKeyPage();   // com chave: a tela de chave ativa
        }

        private void closeKeyScreen() {
            stopKeyPage();
            screen = HOME_SETTINGS;
            int row = hsIndex(keyRow()), rows = settingLabels().length;
            pickScroll = Math.max(0, Math.min(row - 3, rows - Math.min(SETTINGS_VISIBLE, rows)));
            focus = row - pickScroll;
        }

        /** A new page and its code (a new port and token); the old one stops. */
        private void startKeyPage() {
            stopKeyPage();
            InetAddress lan = KeyPage.lanAddress(getContext());
            if (lan == null) {
                setKeyStatus(Ui.tr("The TV isn't on a network: connect it, or type the key."), true);
            } else {
                try {
                    keyPage = new KeyPage(lan, keyListener);
                    keyQr = qrBitmap(Qr.encode(keyPage.url));
                    // While the code waits (5 minutes at most), the TV's screensaver doesn't cover it.
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    setKeyStatus(Ui.tr("Waiting for your phone. The code works for 5 minutes."), false);
                } catch (IOException | RuntimeException e) {
                    stopKeyPage();
                    setKeyStatus(Ui.trf("Error: {error}.", "error", e.getMessage()), true);
                }
            }
            keyRowsChanged();
        }

        void stopKeyPage() {
            if (keyPage != null) keyPage.close();
            keyPage = null;
            keyQr = null;
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        /** The QR code's modules as a bitmap, one pixel each, with the light margin readers need (4 modules). */
        private static Bitmap qrBitmap(boolean[][] modules) {
            int quiet = 4, n = modules.length + 2 * quiet;
            int[] pixels = new int[n * n];
            java.util.Arrays.fill(pixels, 0xFFFFFFFF);
            for (int y = 0; y < modules.length; y++) {
                for (int x = 0; x < modules.length; x++) if (modules[y][x]) pixels[(y + quiet) * n + x + quiet] = 0xFF000000;
            }
            return Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888);
        }

        private final KeyPage.Listener keyListener = new KeyPage.Listener() {
            @Override public void checking() {
                handler.post(() -> {
                    keyChecking = true;
                    setKeyStatus(Ui.tr("Checking the key with TMDB…"), false);
                    keyRowsChanged();
                });
            }

            @Override public String accept(String key) {
                return saveKey(key);
            }

            @Override public void closed(KeyPage page, boolean expired) {
                handler.post(() -> {
                    if (page != keyPage) return;   // an older page, or the key came another way
                    stopKeyPage();
                    if (expired) setKeyStatus(Ui.tr("This code has expired."), false);
                    keyRowsChanged();
                });
            }
        };

        /** Off the UI thread: checks the key with TMDB and saves it if it works. Null when it's saved, else why not. */
        private String saveKey(String pasted) {
            String key = Recs.cleanKey(pasted);
            String why = key.isEmpty() ? Ui.tr("That doesn't look like a TMDB key.") : Recs.checkKey(key);
            if (why == null) Prefs.setTmdbKey(key);
            handler.post(() -> {
                keyChecking = false;
                if (why == null) {
                    stopKeyPage();   // typed: the phone's page has nothing left to do
                    keyStatus = null;   // a tela de chave ativa já diz
                    Recs.get(getContext()).refreshIfStale();
                } else {
                    setKeyStatus(why, true);
                }
                keyRowsChanged();
            });
            return why;
        }

        /** The rows for now: a new code once the last one is used up, typing, and removing a key given on the TV. */
        private void keyRowsChanged() {
            int was = focus < keyRowIds.length ? keyRowIds[focus] : K_TYPE;
            int[] ids = new int[4];
            int n = 0;
            if (keyDone() || keyRemoved) ids[n++] = K_HOME;   // chave ativa (ou acabou de sair): o Início primeiro
            if (keyPage == null && !keyChecking) ids[n++] = K_NEW;
            ids[n++] = K_TYPE;
            if (!Prefs.tmdbKey().isEmpty()) ids[n++] = K_REMOVE;
            boolean added = n > 0 && ids[0] == K_NEW && (keyRowIds.length == 0 || keyRowIds[0] != K_NEW);
            keyRowIds = java.util.Arrays.copyOf(ids, n);
            if (screen != TMDB_KEY) return;
            focus = 0;   // a new code just became possible: there first
            if (!added && ids[0] != K_HOME) for (int i = 0; i < n; i++) if (ids[i] == was) focus = i;
            invalidate();
            spoken.focusMoved();   // the rows changed under TalkBack: it reads the focused one again
        }

        private void setKeyStatus(String text, boolean error) {
            int width = Math.round((LIST_R - LIST_X - 60) * k);
            TextPaint paint = error ? new TextPaint(warn) : notice;
            keyStatus = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                    .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
            if (screen == TMDB_KEY && running) announceForAccessibility(text);   // not over another app
            invalidate();
        }

        private void activateKey() {
            if (focus >= keyRowIds.length) return;
            switch (keyRowIds[focus]) {
                case K_NEW: startKeyPage(); break;
                case K_HOME:
                    activity.startActivity(new Intent(activity, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    activity.finish();
                    break;
                case K_TYPE: askKey(); break;
                default: askRemoveKey();
            }
        }

        /** The fallback: the key typed with the remote (the on-screen keyboard), then checked as a phone's would be. */
        private void askKey() {
            if (keyChecking) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("API Read Access Token or API Key"));
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("TMDB key"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Save"), (d, which) -> typedKey(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                typedKey(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void typedKey(String text) {
            if (text.trim().isEmpty() || keyChecking) return;
            keyChecking = true;
            setKeyStatus(Ui.tr("Checking the key with TMDB…"), false);
            keyRowsChanged();
            new Thread(() -> saveKey(text), "TmdbKey").start();
        }

        /** Asks, then forgets the key given on the TV (one built in from config.properties is used again). */
        private void askRemoveKey() {
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Remove the TMDB key?"))
                    .setPositiveButton(Ui.tr("Remove"), (d, which) -> {
                        Prefs.setTmdbKey("");
                        openKeyScreen();   // a tela inteira de novo: o QR novo e os passos para pôr outra chave
                        keyRemoved = true;
                        setKeyStatus("Chave removida. Os destaques saíram da tela inicial; para voltar a vê-los, adicione uma nova chave.", false);
                        keyRowsChanged();
                        spoken.focusMoved();
                    })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();   // one stray OK keeps it
        }

        /** The rows, the code with the steps beside it, the address under it for a phone without a camera, and the status. */
        private void drawKey(Canvas c) {
            float x = LIST_X * k + 30 * k, pitch = LIST_ROW_H + LIST_ROW_GAP;
            c.drawText(Ui.tr("TMDB key"), x, (LIST_TOP - 24) * k, keyHeading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                float y = (LIST_TOP + 20 + i * pitch) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                c.drawText(keyLabel(keyRowIds[i]), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
            }
            float top = (LIST_TOP + 20 + rows * pitch + 16) * k;
            if (keyDone()) {   // chave ativa: o ✓, os 4 últimos caracteres e o que acontece agora
                top += 50 * k;
                float side = 64 * k;
                tmp.set(x, top, x + side, top + side);
                c.drawOval(tmp, keyOk);
                Symbols.draw(getContext(), c, "check", tmp.centerX(), tmp.centerY(), 34 * k, 0xFFFFFFFF);
                String key = Recs.key();
                c.drawText("Chave ativa", x + side + 28 * k, top + 30 * k, keyHeading);
                c.drawText("•••• " + key.substring(Math.max(0, key.length() - 4)), x + side + 28 * k, top + 70 * k, notice);
                c.drawText("Os destaques já aparecem na tela inicial (filmes e séries em alta nos seus apps).", x, top + side + 70 * k, notice);
                StaticLayout st = keyStatus;
                if (st != null) {
                    c.save();
                    c.translate(x, top + side + 120 * k);
                    st.draw(c);
                    c.restore();
                }
                return;
            }
            if (keyPurpose != null) {   // what the key is for, then how to get one
                c.save();
                c.translate(x, top);
                keyPurpose.draw(c);
                c.restore();
                top += keyPurpose.getHeight() + 26 * k;
            }
            Bitmap qr = keyQr;
            if (qr != null) {
                int n = qr.getWidth();
                float side = Math.max(1, (int) (QR_SIDE * k / n)) * n;   // whole pixels per module
                tmp.set(x, top, x + side, top + side);
                c.drawBitmap(qr, null, tmp, qrPaint);
            } else {   // used up or expired: where the code goes, until New code
                tmp.set(x, top, x + QR_SIDE * k, top + QR_SIDE * k);
                c.drawRoundRect(tmp, 12 * k, 12 * k, qrSlot);
            }
            float below = top + QR_SIDE * k;
            if (keySteps != null) {
                c.save();
                c.translate(x + (QR_SIDE + 40) * k, top);
                keySteps.draw(c);
                c.restore();
                below = Math.max(below, top + keySteps.getHeight());
            }
            below += 44 * k;
            KeyPage page = keyPage;
            if (page != null) {
                c.drawText(Ui.tr("No camera? On a phone on the same network, open:"), x, below, notice);
                c.drawText(page.url, x, below + 38 * k, keyUrl);
                below += 90 * k;
            }
            StaticLayout status = keyStatus;
            if (status != null) {
                c.save();
                c.translate(x, below - status.getLineBaseline(0));
                status.draw(c);
                c.restore();
            }
        }

        // MARK: - Enviar fotos pelo celular (PhotoPage: QR code, mesma rede)

        private static final int F_NEW = 0, F_PICK = 1;
        private PhotoPage photoPage;
        private Bitmap photoQr;
        private String photoStatus = "";
        private int[] photoRowIds = new int[0];
        private StaticLayout photoSteps;

        private static String photoLabel(int id) {
            return id == F_NEW ? "Novo código" : "Escolher fotos para apagar";
        }

        private void openPhotoScreen() {
            screen = PHOTO_SEND;
            pickScroll = 0;
            focus = 0;
            String steps = "1. Conecte o celular na mesma rede Wi-Fi da TV.\n2. Aponte a câmera do celular para este código."
                    + "\n3. Toque em Escolher fotos e depois em Enviar.\n\nAs fotos aparecem na tela de descanso e no fundo da tela inicial (até "
                    + Photos.MAX + ").";
            int width = Math.round((LIST_R - LIST_X - 60 - QR_SIDE - 40) * k);
            photoSteps = StaticLayout.Builder.obtain(steps, 0, steps.length(), notice, width).setLineSpacing(6 * k, 1).build();
            startPhotoPage();
        }

        private void closePhotoScreen() {
            stopPhotoPage();
            screen = HOME_SETTINGS;
            int row = hsIndex(PHOTOS_ROW), rows = settingLabels().length;
            pickScroll = Math.max(0, Math.min(row - 3, rows - Math.min(SETTINGS_VISIBLE, rows)));
            focus = row - pickScroll;
        }

        private void startPhotoPage() {
            stopPhotoPage();
            InetAddress lan = KeyPage.lanAddress(getContext());
            if (lan == null) {
                photoStatus = "A TV não está numa rede. Conecte o Wi-Fi e escolha Novo código.";
            } else {
                try {
                    photoPage = new PhotoPage(getContext(), lan, photoListener);
                    photoQr = qrBitmap(Qr.encode(photoPage.url));
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    photoStatus = "Aguardando o celular. O código vale por 10 minutos.";
                } catch (IOException | RuntimeException e) {
                    stopPhotoPage();
                    photoStatus = "Erro: " + e.getMessage();
                }
            }
            photoRowsChanged();
        }

        void stopPhotoPage() {
            if (photoPage != null) photoPage.close();
            photoPage = null;
            photoQr = null;
            if (keyPage == null) activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        private final PhotoPage.Listener photoListener = new PhotoPage.Listener() {
            @Override public void receiving(int number) {
                handler.post(() -> {
                    photoStatus = "Recebendo foto " + number + "…";
                    invalidate();
                });
            }

            @Override public void received(int total) {
                handler.post(() -> {
                    String got = (total == 1 ? "1 foto recebida" : total + " fotos recebidas");
                    photoStatus = got + ". Preparando…";
                    invalidate();
                    // Importadas aqui mesmo: já aparecem, e a contagem da tela fica certa.
                    Photos.importInbox(getContext(), () -> {
                        int have = Photos.files(getContext()).size();
                        photoStatus = got + ". Na TV: " + have + (have == 1 ? " foto." : " fotos.")
                                + (have >= Photos.MAX ? " Limite de " + Photos.MAX + " atingido." : "");
                        photoRowsChanged();
                        if (running) announceForAccessibility(photoStatus);
                    });
                });
            }

            @Override public void closed(PhotoPage page, boolean expired) {
                handler.post(() -> {
                    if (page != photoPage) return;
                    stopPhotoPage();
                    if (expired) photoStatus = "O código expirou. Escolha Novo código.";
                    photoRowsChanged();
                });
            }
        };

        /** As linhas: Novo código (quando o anterior acabou) e escolher fotos para apagar (com fotos). */
        private void photoRowsChanged() {
            int was = focus < photoRowIds.length ? photoRowIds[focus] : F_PICK;
            int[] ids = new int[2];
            int n = 0;
            if (photoPage == null) ids[n++] = F_NEW;
            if (!Photos.files(getContext()).isEmpty()) ids[n++] = F_PICK;
            photoRowIds = java.util.Arrays.copyOf(ids, n);
            if (screen != PHOTO_SEND) return;
            focus = 0;
            for (int i = 0; i < n; i++) if (ids[i] == was) focus = i;
            invalidate();
            spoken.focusMoved();
        }

        private void activatePhoto() {
            if (focus >= photoRowIds.length) return;
            if (photoRowIds[focus] == F_NEW) startPhotoPage(); else openPhotoPick();
        }

        /** As linhas, o código com os passos ao lado, o endereço para quem não tem câmera e o andamento. */
        private void drawPhotoSend(Canvas c) {
            float x = LIST_X * k + 30 * k, pitch = LIST_ROW_H + LIST_ROW_GAP;
            c.drawText("Enviar fotos pelo celular", x, (LIST_TOP - 24) * k, keyHeading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                float y = (LIST_TOP + 20 + i * pitch) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                c.drawText(photoLabel(photoRowIds[i]), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
            }
            float top = (LIST_TOP + 20 + rows * pitch + 30) * k;
            Bitmap qr = photoQr;
            if (qr != null) {
                int n = qr.getWidth();
                float side = Math.max(1, (int) (QR_SIDE * k / n)) * n;
                tmp.set(x, top, x + side, top + side);
                c.drawBitmap(qr, null, tmp, qrPaint);
            } else {
                tmp.set(x, top, x + QR_SIDE * k, top + QR_SIDE * k);
                c.drawRoundRect(tmp, 12 * k, 12 * k, qrSlot);
            }
            float below = top + QR_SIDE * k;
            if (photoSteps != null) {
                c.save();
                c.translate(x + (QR_SIDE + 40) * k, top);
                photoSteps.draw(c);
                c.restore();
                below = Math.max(below, top + photoSteps.getHeight());
            }
            below += 44 * k;
            PhotoPage page = photoPage;
            if (page != null) {
                c.drawText("Sem câmera? No celular, na mesma rede, abra:", x, below, notice);
                c.drawText(page.url, x, below + 38 * k, keyUrl);
                below += 90 * k;
            }
            if (!photoStatus.isEmpty()) c.drawText(photoStatus, x, below, notice);
        }

        // MARK: - Tela de descanso: quando começa (o tempo do Android, gravado pelo ADB) e ver agora

        private static final int NEVER = Integer.MAX_VALUE;
        private static final int[] SAVER_TIMES = {60_000, 2 * 60_000, 5 * 60_000, 10 * 60_000, 15 * 60_000, 30 * 60_000, 60 * 60_000, NEVER};
        private int saverMs;

        private int currentSaverMs() {
            return android.provider.Settings.System.getInt(getContext().getContentResolver(),
                    android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 15 * 60_000);
        }

        private static String saverTimeLabel(int ms) {
            if (ms >= NEVER / 2) return "Nunca";
            int min = Math.round(ms / 60_000f);
            return min >= 60 ? (min / 60 == 1 ? "1 hora" : min / 60 + " horas") : min == 1 ? "1 minuto" : min + " minutos";
        }

        /** Vai para o tempo seguinte (ou o anterior) da lista; o que estava fora dela vai para o mais próximo. */
        private void stepSaverTime(int dir) {
            int at = 0;
            for (int i = 0; i < SAVER_TIMES.length; i++) if (Math.abs((long) SAVER_TIMES[i] - saverMs) < Math.abs((long) SAVER_TIMES[at] - saverMs)) at = i;
            at = Math.max(0, Math.min(SAVER_TIMES.length - 1, at + dir));
            saverMs = SAVER_TIMES[at];
            HomeMode.saverTime(getContext(), saverMs);
            announceForAccessibility(saverTimeLabel(saverMs));
        }

        // MARK: - Escolher fotos para apagar (as miniaturas em grade; OK marca, o botão de cima apaga as marcadas)

        private static final int PICK_COLS = 5;
        private List<java.io.File> pickFiles = new ArrayList<>();
        private final java.util.Set<java.io.File> marked = new java.util.HashSet<>();
        private final java.util.Map<java.io.File, Bitmap> thumbs = new java.util.HashMap<>();
        private int pickRow;   // a primeira linha de miniaturas na tela
        private final android.graphics.Path clip = new android.graphics.Path();
        private java.util.concurrent.ExecutorService thumbLoader;

        private void openPhotoPick() {
            pickFiles = new ArrayList<>(Photos.files(getContext()));
            marked.clear();
            pickRow = 0;
            screen = PHOTO_PICK;
            focus = pickFiles.isEmpty() ? 0 : 1;
            if (thumbLoader == null) thumbLoader = java.util.concurrent.Executors.newSingleThreadExecutor();
            for (java.io.File f : pickFiles) {
                if (thumbs.containsKey(f)) continue;
                thumbLoader.execute(() -> {   // 1/8 do tamanho (264 × 148), uma por vez: leve para o stick
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 8;
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                    handler.post(() -> {
                        if (b == null) return;
                        if (screen != PHOTO_PICK) { b.recycle(); return; }
                        thumbs.put(f, b);
                        invalidate();
                    });
                });
            }
        }

        private void closePhotoPick() {
            for (Bitmap b : thumbs.values()) b.recycle();
            thumbs.clear();
            screen = PHOTO_SEND;
            photoRowsChanged();
            focus = 0;
            for (int i = 0; i < photoRowIds.length; i++) if (photoRowIds[i] == F_PICK) focus = i;
        }

        private String pickButton() {
            int n = marked.size();
            return n == 0 ? "Apagar selecionadas" : n == 1 ? "Apagar 1 foto selecionada" : "Apagar " + n + " fotos selecionadas";
        }

        /** Rola a grade para a linha da foto focada (quatro linhas na tela). */
        private void pickReveal() {
            if (focus == 0) { pickRow = 0; return; }
            int row = (focus - 1) / PICK_COLS;
            if (row < pickRow) pickRow = row;
            if (row > pickRow + 3) pickRow = row - 3;
        }

        private void activatePick() {
            if (focus >= 1 && focus <= pickFiles.size()) {   // marca ou desmarca
                java.io.File f = pickFiles.get(focus - 1);
                if (!marked.remove(f)) marked.add(f);
                return;
            }
            if (marked.isEmpty()) return;
            int n = marked.size();
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(n == 1 ? "Apagar 1 foto?" : "Apagar " + n + " fotos?")
                    .setPositiveButton("Apagar", (d, which) -> {
                        List<java.io.File> gone = new ArrayList<>(marked);
                        Photos.remove(getContext(), gone);
                        for (java.io.File f : gone) {
                            Bitmap b = thumbs.remove(f);
                            if (b != null) b.recycle();
                        }
                        pickFiles.removeAll(gone);
                        marked.clear();
                        photoStatus = (n == 1 ? "1 foto apagada" : n + " fotos apagadas") + ". Na TV: " + pickFiles.size()
                                + (pickFiles.size() == 1 ? " foto." : " fotos.");
                        if (pickFiles.isEmpty()) closePhotoPick();
                        else { focus = 0; pickRow = 0; }
                        invalidate();
                        spoken.focusMoved();
                    })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();   // um OK sem querer não apaga
        }

        /** O botão de apagar em cima e as miniaturas em grade: a focada com a borda branca, as marcadas com o ✓. */
        private void drawPhotoPick(Canvas c) {
            float x = LIST_X * k + 30 * k;
            c.drawText("Escolher fotos para apagar", x, (LIST_TOP - 24) * k, keyHeading);
            float y0 = (LIST_TOP + 20) * k;
            rowRect.set(LIST_X * k, y0, LIST_R * k, y0 + LIST_ROW_H * k);
            float f0 = drawRowBackground(c, 0);
            rowLabel.setColor(blend(marked.isEmpty() ? 0x80FFFFFF : 0xF2FFFFFF, INK, f0));
            c.drawText(pickButton(), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
            float gap = 22 * k, left = (LIST_X + 10) * k, width = (LIST_R - LIST_X - 20) * k;
            float cw = (width - gap * (PICK_COLS - 1)) / PICK_COLS, ch = cw * 9 / 16;
            float top = y0 + (LIST_ROW_H + 30) * k;
            for (int i = pickRow * PICK_COLS; i < Math.min(pickFiles.size(), (pickRow + 4) * PICK_COLS); i++) {
                int col = i % PICK_COLS, row = i / PICK_COLS - pickRow;
                float cx = left + col * (cw + gap), cy = top + row * (ch + gap);
                boolean on = focus == i + 1;
                float s = on ? 1.06f : 1;
                tmp.set(cx + cw / 2 - cw * s / 2, cy + ch / 2 - ch * s / 2, cx + cw / 2 + cw * s / 2, cy + ch / 2 + ch * s / 2);
                java.io.File f = pickFiles.get(i);
                Bitmap b = thumbs.get(f);
                float r = 12 * k;
                c.save();
                clip.reset();
                clip.addRoundRect(tmp, r, r, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                if (b != null) c.drawBitmap(b, null, tmp, qrPaint);
                else { cardFill.setColor(0x33FFFFFF); c.drawRect(tmp, cardFill); }
                boolean m = marked.contains(f);
                if (m) { cardFill.setColor(0x66000000); c.drawRect(tmp, cardFill); }
                c.restore();
                if (m) {   // ✓ no canto
                    float cs = 40 * k, bx = tmp.right - cs * 0.75f, by = tmp.top + cs * 0.75f;
                    cardFill.setColor(0xFFFF453A);
                    c.drawCircle(bx, by, cs / 2, cardFill);
                    Symbols.draw(getContext(), c, "check", bx, by, cs * 0.6f, 0xFFFFFFFF);
                }
                if (on || m) {
                    cardRim.setShader(null);
                    cardRim.setColor(on ? 0xFFFFFFFF : 0xFFFF453A);
                    cardRim.setStrokeWidth(4 * k);
                    c.drawRoundRect(tmp, r, r, cardRim);
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            int rows = (pickFiles.size() + PICK_COLS - 1) / PICK_COLS;
            String more = rows > 4 ? "  ·  Linhas " + (pickRow + 1) + "–" + Math.min(rows, pickRow + 4) + " de " + rows : "";
            c.drawText("OK marca ou desmarca a foto. Depois, ↑ até o botão e OK para apagar." + more,
                    x, top + 4 * (ch + gap) + 20 * k, hint);
        }

        // MARK: - My Photos (sent from a computer: tools/photos.py)

        /** OK on My Photos: asks, then deletes them all. */
        /** Liga ou desliga o card de câmeras; ao ligar sem o Onvifer instalado, abre a página dele na Play Store. */
        private void toggleCams() {
            boolean on = !Prefs.camsOn();
            Prefs.setCamsOn(on);
            if (on && getContext().getPackageManager().getLaunchIntentForPackage("net.biyee.onvifer") == null
                    && getContext().getPackageManager().getLeanbackLaunchIntentForPackage("net.biyee.onvifer") == null) {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=net.biyee.onvifer")));
                } catch (RuntimeException ignored) {
                }
            }
        }

        /** Apaga as câmeras salvas (com confirmação); o card volta a "Configurar câmeras". */
        private void askResetCams() {
            if (Onvif.saved().isEmpty()) return;
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Apagar os dados das câmeras?")
                    .setPositiveButton("Apagar", (d, which) -> {
                        Prefs.setCams("[]");
                        invalidate();
                        spoken.focusMoved();
                    })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();   // um OK sem querer não apaga
        }

        // MARK: - Temas

        private String[] themeLabels() {
            return Prefs.themeFixedMode() ? new String[] {"Modo", "Tema"} : new String[] {"Modo", "Tema de dia", "Tema de noite"};
        }

        private String[] themeValues() {
            if (Prefs.themeFixedMode()) return new String[] {"Um tema só", Themes.byId(Prefs.themeFixed()).name};
            return new String[] {"Automático (dia e noite)", Themes.byId(Prefs.themeDay()).name, Themes.byId(Prefs.themeNight()).name};
        }

        /** ← → (e OK) trocam o modo ou passam pelos 8 temas. */
        private void adjustTheme(int dir) {
            int n = Themes.ALL.length;
            if (focus == 0) {
                Prefs.setThemeFixedMode(!Prefs.themeFixedMode());
            } else if (Prefs.themeFixedMode()) {
                Prefs.setThemeFixed(Themes.ALL[Math.min(n, focus) - 1].id);   // o card focado
            } else if (focus == 1) {
                Prefs.setThemeDay(Themes.ALL[(Themes.index(Prefs.themeDay()) + dir + n) % n].id);
            } else {
                Prefs.setThemeNight(Themes.ALL[(Themes.index(Prefs.themeNight()) + dir + n) % n].id);
            }
            if (!Prefs.themeFixedMode()) focus = Math.min(focus, themeLabels().length - 1);
            SleepOverlay.update(getContext());
        }

        private static final int CARDS_PER_ROW = 4;

        /** Um tema só: a linha Modo e os 8 temas em cards (o fundo, o painel e a barra de cada um); OK escolhe. */
        private void drawThemeCards(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText("Temas", LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            float y0 = (LIST_TOP + 20) * k;
            rowRect.set(LIST_X * k, y0, LIST_R * k, y0 + LIST_ROW_H * k);
            float f0 = drawRowBackground(c, 0);
            rowLabel.setColor(blend(0xF2FFFFFF, INK, f0));
            rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f0));
            float base = rowRect.centerY() + Ui.halfCap(rowLabel);
            c.drawText("Modo", rowRect.left + 30 * k, base, rowLabel);
            c.drawText("Um tema só", rowRect.right - 30 * k, base, rowValue);

            float gap = 24 * k, left = (LIST_X + 10) * k, width = (LIST_R - LIST_X - 20) * k;
            float cw = (width - gap * (CARDS_PER_ROW - 1)) / CARDS_PER_ROW, ch = cw * 0.62f;
            float top = y0 + (LIST_ROW_H + 34) * k;
            String current = Prefs.themeFixed();
            Paint name = Ui.text(Ui.displaySemibold, 24 * k, 0xFFFFFFFF);
            for (int i = 0; i < Themes.ALL.length; i++) {
                Themes.T t = Themes.ALL[i];
                float x = left + (i % CARDS_PER_ROW) * (cw + gap), y = top + (i / CARDS_PER_ROW) * (ch + 64 * k);
                float f = Ui.clamp(pop[i + 1].value), s = 1 + 0.05f * f;
                tmp.set(x + cw / 2 - cw * s / 2, y + ch / 2 - ch * s / 2, x + cw / 2 + cw * s / 2, y + ch / 2 + ch * s / 2);
                float r = 16 * k;
                cardFill.setShader(new android.graphics.LinearGradient(0, tmp.top, 0, tmp.bottom, t.bgTop, t.bgBottom,
                        android.graphics.Shader.TileMode.CLAMP));
                c.drawRoundRect(tmp, r, r, cardFill);
                cardFill.setShader(null);
                // a miniatura da tela inicial: o painel do carrossel em cima, a barra de apps embaixo
                float px = tmp.left + tmp.width() * 0.08f, pw = tmp.width() * 0.84f;
                cardFill.setColor(t.panel);
                c.drawRoundRect(px, tmp.top + tmp.height() * 0.14f, px + pw, tmp.top + tmp.height() * 0.56f, 8 * k, 8 * k, cardFill);
                cardFill.setColor(t.shelf);
                c.drawRoundRect(px, tmp.top + tmp.height() * 0.66f, px + pw, tmp.top + tmp.height() * 0.86f, 8 * k, 8 * k, cardFill);
                if (f > 0.01f) {
                    cardRim.setShader(null);
                    cardRim.setColor(Ui.alpha(0xFFFFFFFF, f));
                    cardRim.setStrokeWidth(4 * k);
                    c.drawRoundRect(tmp, r, r, cardRim);
                }
                String label = t.name + (t.dark ? " · escuro" : " · claro");
                name.setColor(f > 0.01f || t.id.equals(current) ? 0xFFFFFFFF : 0xB3FFFFFF);
                c.drawText(label, x, y + ch + 38 * k, name);
                if (t.id.equals(current)) {   // o tema em uso
                    float cs = 22 * k;
                    Symbols.draw(getContext(), c, "check", x + cw - cs / 2, y + ch + 30 * k, cs, 0xFFFFFFFF);
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            c.drawText("Setas escolhem o card · OK usa o tema · em Modo, OK volta ao automático.",
                    LIST_X * k + 30 * k, top + 2 * (ch + 64 * k) + 20 * k, hint);
        }

        /** A aba Layout: um card por modelo, com o desenho da tela inicial dele; OK escolhe. */
        private void drawLayoutCards(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText("Layout", LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            float gap = 28 * k, left = (LIST_X + 10) * k, width = (LIST_R - LIST_X - 20) * k;
            float cw = (width - gap * 2) / 3, ch = cw * 9 / 16, top = (LIST_TOP + 20) * k;
            Paint name = Ui.text(Ui.displaySemibold, 25 * k, 0xFFFFFFFF), tag = Ui.text(Ui.regular, 21 * k, 0x99FFFFFF);
            for (int i = 0; i < LAYOUTS.length; i++) {
                float x = left + (i % 3) * (cw + gap), y = top + (i / 3) * (ch + 78 * k);
                float f = Ui.clamp(pop[i].value), s = 1 + 0.04f * f;
                tmp.set(x + cw / 2 - cw * s / 2, y + ch / 2 - ch * s / 2, x + cw / 2 + cw * s / 2, y + ch / 2 + ch * s / 2);
                boolean soon = !LAYOUTS[i][2].isEmpty();
                cardFill.setShader(null);
                cardFill.setColor(soon ? 0x33FFFFFF : 0x4DFFFFFF);
                c.drawRoundRect(tmp, 14 * k, 14 * k, cardFill);
                drawLayoutSketch(c, LAYOUTS[i][0], tmp, soon ? 0x40FFFFFF : 0x99FFFFFF, soon ? 0x40FFFFFF : 0xCC8FA6FF);
                if (f > 0.01f) {
                    cardRim.setShader(null);
                    cardRim.setColor(Ui.alpha(0xFFFFFFFF, f));
                    cardRim.setStrokeWidth(4 * k);
                    c.drawRoundRect(tmp, 14 * k, 14 * k, cardRim);
                }
                boolean cur = LAYOUTS[i][0].equals(Prefs.layout());
                name.setColor(soon ? 0x80FFFFFF : 0xFFFFFFFF);
                c.drawText(LAYOUTS[i][1], x, y + ch + 38 * k, name);
                if (soon) c.drawText(LAYOUTS[i][2], x + name.measureText(LAYOUTS[i][1]) + 14 * k, y + ch + 38 * k, tag);
                if (cur) Symbols.draw(getContext(), c, "check", x + cw - 12 * k, y + ch + 30 * k, 22 * k, 0xFFFFFFFF);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            c.drawText("Setas escolhem o card · OK usa o layout. Os temas valem para todos.",
                    LIST_X * k + 30 * k, top + 2 * (ch + 78 * k) + 10 * k, hint);
        }

        /** A miniatura de cada layout: blocos claros (apps, títulos) e de destaque (filmes, câmera). */
        private void drawLayoutSketch(Canvas c, String id, RectF r, int block, int accent) {
            float w = r.width(), h = r.height();
            java.util.function.BiConsumer<float[], Integer> b = (q, col) -> {
                cardFill.setColor(col);
                c.drawRoundRect(r.left + q[0] * w, r.top + q[1] * h, r.left + (q[0] + q[2]) * w, r.top + (q[1] + q[3]) * h, 4 * k, 4 * k, cardFill);
            };
            switch (id) {
                case "classico":
                    b.accept(new float[] {.04f, .06f, .22f, .07f}, block);
                    b.accept(new float[] {.05f, .26f, .38f, .32f}, accent);
                    for (int i = 0; i < 4; i++) b.accept(new float[] {.46f + i * .12f, .26f, .10f, .32f}, block);
                    for (int i = 0; i < 7; i++) b.accept(new float[] {.06f + i * .13f, .76f, .11f, .14f}, block);
                    break;
                case "cinema":
                    b.accept(new float[] {0, 0, 1, .70f}, accent);
                    b.accept(new float[] {.05f, .34f, .32f, .08f}, block);
                    for (int i = 0; i < 8; i++) b.accept(new float[] {.05f + i * .115f, .78f, .10f, .13f}, block);
                    break;
                case "vitrine":   // a foto cobrindo tudo, a fileira de destaques e a de apps
                    b.accept(new float[] {0, 0, 1, 1}, 0x40FFFFFF & accent);
                    b.accept(new float[] {.05f, .10f, .30f, .07f}, block);
                    b.accept(new float[] {.05f, .34f, .30f, .26f}, accent);
                    for (int i = 0; i < 4; i++) b.accept(new float[] {.38f + i * .14f, .34f, .11f, .26f}, block);
                    for (int i = 0; i < 6; i++) b.accept(new float[] {.05f + i * .15f, .74f, .13f, .14f}, block);
                    break;
                case "flutuante":   // a foto cobrindo tudo e o menu de vidro flutuando à esquerda
                    b.accept(new float[] {0, 0, 1, 1}, 0x40FFFFFF & accent);
                    b.accept(new float[] {.03f, .05f, .22f, .90f}, 0x40FFFFFF);
                    for (int i = 0; i < 6; i++) b.accept(new float[] {.06f, .14f + i * .11f, .15f, .06f}, block);
                    b.accept(new float[] {.30f, .30f, .36f, .08f}, block);
                    b.accept(new float[] {.30f, .62f, .28f, .26f}, accent);
                    for (int i = 0; i < 3; i++) b.accept(new float[] {.61f + i * .12f, .62f, .10f, .26f}, block);
                    break;
                case "lateral":
                    b.accept(new float[] {0, 0, .20f, 1}, 0x26FFFFFF);
                    for (int i = 0; i < 5; i++) b.accept(new float[] {.03f, .20f + i * .11f, .14f, .06f}, block);
                    b.accept(new float[] {.25f, .08f, .70f, .40f}, accent);
                    for (int i = 0; i < 4; i++) b.accept(new float[] {.25f + i * .18f, .56f, .16f, .17f}, block);
                    break;
                default:   // minimalista
                    b.accept(new float[] {.06f, .22f, .34f, .20f}, block);
                    b.accept(new float[] {.06f, .46f, .24f, .05f}, block);
                    b.accept(new float[] {.55f, .22f, .39f, .38f}, accent);
                    for (int i = 0; i < 7; i++) b.accept(new float[] {.06f + i * .13f, .76f, .11f, .14f}, block);
            }
        }

        // MARK: - Arquivos e lista IPTV

        private static final String FILES_APP = "nextapp.fx";   // o FX File Explorer
        private static final String IPTV_URL = "https://raw.githubusercontent.com/PabloAlvesMelo/freetv/main/tv/lista.m3u";
        private String iptvStatus = "", iptvBackStatus = "";

        private static java.io.File iptvFile() {
            return new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "MeloFreeTV-lista.m3u");
        }

        private static java.io.File iptvBackup() {
            return new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "MeloFreeTV-lista-anterior.m3u");
        }

        /** Troca a lista atual pela anterior: as duas trocam de nome, então dá para desfazer com outro OK. */
        private void restoreIptv() {
            java.io.File cur = iptvFile(), back = iptvBackup(), tmp = new java.io.File(cur.getParentFile(), "MeloFreeTV-lista.tmp");
            if (!back.exists()) {
                iptvBackStatus = "Sem backup";
            } else if ((!cur.exists() || cur.renameTo(tmp)) && back.renameTo(cur) && (!tmp.exists() || tmp.renameTo(back))) {
                iptvBackStatus = "Lista anterior restaurada";
                iptvStatus = "";
            } else {
                iptvBackStatus = "Não foi possível trocar";
            }
            invalidate();
        }

        /** Liga ou desliga Arquivos; ao ligar sem o FX, abre a página dele na Play Store. */
        private void toggleFiles() {
            boolean on = !Prefs.files();
            Prefs.setFiles(on);
            android.content.pm.PackageManager pm = getContext().getPackageManager();
            if (on && pm.getLaunchIntentForPackage(FILES_APP) == null && pm.getLeanbackLaunchIntentForPackage(FILES_APP) == null) {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + FILES_APP)));
                } catch (RuntimeException ignored) {
                }
            }
        }

        /** Baixa a lista IPTV do repositório para a pasta do app (Android/data/io.freemytv/files), sem pedir permissão. */
        private void downloadIptv() {
            if ("Baixando…".equals(iptvStatus)) return;
            // A pasta Download pede a permissão de armazenamento (Android 9): a TV pergunta uma vez.
            if (getContext().checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
                iptvStatus = "Permita o acesso e aperte OK de novo";
                invalidate();
                return;
            }
            iptvStatus = "Baixando…";
            invalidate();
            new Thread(() -> {
                String result;
                try {
                    java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("sem a pasta Download");
                    java.io.File out = iptvFile(), part = new java.io.File(dir, "MeloFreeTV-lista.part");
                    java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(IPTV_URL).openConnection();
                    con.setConnectTimeout(8000);
                    con.setReadTimeout(15000);
                    if (con.getResponseCode() != 200) throw new java.io.IOException("o repositório respondeu " + con.getResponseCode());
                    try (java.io.InputStream in = con.getInputStream(); java.io.OutputStream o = new java.io.FileOutputStream(part)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    }
                    con.disconnect();
                    // Só troca com a lista já baixada inteira: a de antes vira o backup (Voltar à lista anterior).
                    java.io.File back = iptvBackup();
                    if (out.exists()) {
                        if (back.exists() && !back.delete()) throw new java.io.IOException("não apagou o backup antigo");
                        if (!out.renameTo(back)) throw new java.io.IOException("não guardou o backup");
                    }
                    if (!part.renameTo(out)) throw new java.io.IOException("não salvou a lista");
                    handler.post(() -> iptvBackStatus = "");
                    result = "Salva em Download/MeloFreeTV-lista.m3u";
                } catch (Exception e) {
                    result = "Não baixou: " + e.getMessage();
                }
                String r = result;
                handler.post(() -> {
                    iptvStatus = r;
                    invalidate();
                });
            }).start();
        }


        private void searchCity(String name) {
            if (name.trim().isEmpty()) return;
            cityStatus = Ui.tr("Searching…");
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? Ui.tr("No city with that name.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.trf("Couldn't search now: {error}", "error", e.getMessage());
                    AppLog.w("city search: " + e.getMessage());
                }
                List<String[]> result = found;
                String message = error;
                handler.post(() -> {
                    cityStatus = message;
                    if (!result.isEmpty() && screen == HOME_SETTINGS) {
                        cities.clear();
                        cities.addAll(result);
                        openPicker(PICK_CITY);
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(() -> { cityStatus = ""; invalidate(); }, 4000);
                });
            }).start();
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT || screen == HOME_SETTINGS || screen == PICK_FROM;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : screen == HOME_SETTINGS ? settingLabels().length : screen == PICK_FROM ? services.size() : pickLabels.size();
        }

        // MARK: - Shelf editor

        private void openShelfEditor(boolean top) {
            editingTop = top;
            shelfOrder.clear();
            if (top) {
                shelfOrder.addAll(HomeApps.topButtonKeys());
                rebuildShelfRows(HomeApps.topCandidates());
            } else {
                // Only the apps the shelf shows: one that was uninstalled would hold a place (and count toward the
                // limit) without a row to take it off.
                PackageManager pm = getContext().getPackageManager();
                for (String pkg : HomeApps.shelfPackages(getContext())) if (HomeApps.launchIntent(pm, pkg) != null) shelfOrder.add(pkg);
                rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            }
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            if (editingTop) Prefs.setTopButtons(shelfOrder); else Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (editingTop || shelfOrder.size() < HomeApps.maxShelf(getContext())) shelfOrder.add(pkg);   // three buttons: no limit to reach
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void drawShelfEditor(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(editingTop ? Ui.tr("Top Buttons") : Ui.tr("Shelf"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                String[] app = shelfRows.get(pickScroll + i);
                int place = shelfOrder.indexOf(app[0]);
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                boolean off = unavailable(i) != null;
                rowLabel.setColor(off ? blend(0x73FFFFFF, 0x731C1C1E, f)
                        : blend(place >= 0 ? 0xF2FFFFFF : 0x80FFFFFF, place >= 0 ? INK : 0x801C1C1E, f));
                rowValue.setColor(off ? blend(0x59FFFFFF, 0x591C1C1E, f) : blend(0xB3FFFFFF, 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(TextUtils.ellipsize(app[1], new TextPaint(rowLabel), 520 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (place >= 0 && !off) {
                    // On the shelf: a check, as in the pickers, after its place.
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", right - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                    right -= cw + 16 * k;
                }
                c.drawText(off ? Ui.tr("Unavailable") : place >= 0 ? "#" + (place + 1) : Ui.tr("shelf|Off"), right, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            if (!editingTop && HomeApps.lowRam(getContext())) {
                c.drawText(Ui.tr("This TV has little memory, so the shelf holds fewer apps."), LIST_X * k + 30 * k, hy + 38 * k, hint);
            }
            c.drawText(editingTop ? Ui.tr("OK adds or removes a button. ← → change the order.")
                    : HomeApps.maxShelf(getContext()) == Integer.MAX_VALUE ? Ui.tr("OK adds or removes an app. ← → change the order.")
                    : Ui.trf("OK adds or removes (up to {n}). ← → change the order.", "n", HomeApps.maxShelf(getContext())),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            if (which == PICK_CITY) {
                for (String[] city : cities) pickLabels.add(city[0]);
                pickCurrent = -1;
            } else if (which == PICK_LANGUAGE) {
                String[] choices = languageChoices();
                for (String code : choices) {
                    pickLabels.add(code.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(code));
                }
                pickCurrent = java.util.Arrays.asList(choices).indexOf(Prefs.language());
            } else if (which <= PICK_SLEEP) {
                pickCurrent = -1;
                for (int i = 0; i < choices.size(); i++) {
                    pickLabels.add(choices.get(i)[1]);
                    if (choices.get(i)[0].equals(choiceNow)) pickCurrent = i;
                }
            } else {
                pickLabels.addAll(appLabels);
                pickCurrent = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[which - 1]));
            }
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /** A long list (the apps): nine rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            String title = pickFor == PICK_CITY ? Ui.tr("City")
                    : pickFor == PICK_LANGUAGE ? Ui.tr("Language")
                    : pickFor == PICK_SLEEP ? Ui.tr("Sleep Timer") : pickFor == PICK_PLACE ? Ui.tr("Position")
                    : keyLabels()[pickFor - 1];
            c.drawText(title, LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                boolean off = unavailable(i) != null;
                rowLabel.setColor(off ? blend(0x73FFFFFF, 0x731C1C1E, f) : blend(0xF2FFFFFF, INK, f));
                c.drawText(pickLabels.get(at), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (off) {
                    rowValue.setColor(blend(0x59FFFFFF, 0x591C1C1E, f));
                    c.drawText(Ui.tr("Unavailable"), rowRect.right - 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowValue);
                } else if (at == pickCurrent) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText((pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK picks, Back cancels."),
                    LIST_X * k + 30 * k, hy, hint);
        }

        /** "Some" only when one of the services the TV has is off (a service it doesn't have, or a typo in hide_services, doesn't count). */
        private void countServicesOff() {
            someServicesOff = false;
            for (String[] service : Recs.installedServices(getContext())) someServicesOff |= Prefs.serviceOff(service[0]);
        }

        private void toggleService() {
            int at = pickScroll + focus;
            if (at >= services.size()) return;
            String key = services.get(at)[0];
            Prefs.setServiceOff(key, !Prefs.serviceOff(key));
        }

        /** Picks from: one row per streaming service on the TV, On or Off. */
        private void drawServices(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Picks from"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = Math.min(PICK_VISIBLE, services.size());
            for (int i = 0; i < rows; i++) {
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                String[] service = services.get(pickScroll + i);   // the list scrolls past 9 services, like the pickers
                c.drawText(service[1], rowRect.left + 30 * k, base, rowLabel);
                c.drawText(onOff(!Prefs.serviceOff(service[0])), rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(services.isEmpty() ? Ui.tr("No streaming apps on this TV.")
                    : Ui.tr("Titles from a service that is off are left out of the picks."),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void drawList(Canvas c) {
            boolean keys = screen == REMOTE_KEYS;
            String[] labels = listLabels();
            String[] values = listValues();
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(keys ? Ui.tr("Remote Buttons") : screen == POWER ? "Desligamento Automático" : screen == PICKS ? Ui.tr("Picks")
                    : screen == THEME_SCREEN ? "Temas" : screen == SAVER_SCREEN ? "Tela de descanso" : hsGroup == 0 ? "Configurações" : "Adicionais",
                    LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            float pitch = LIST_ROW_H + LIST_ROW_GAP;
            int rows = keys ? labels.length : rowCount(), from = keys ? 0 : pickScroll;
            for (int i = 0; i < rows; i++) {
                float y = (LIST_TOP + 20 + i * pitch) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                boolean locked = unavailable(i) != null;
                if (locked) values[from + i] = Ui.tr("Unavailable");
                rowValue.setColor(blend(locked ? 0x59FFFFFF : 0xB3FFFFFF, locked ? 0x591C1C1E : 0xB31C1C1E, f));
                if (locked) rowLabel.setColor(blend(0x73FFFFFF, 0x731C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[from + i], rowRect.left + 30 * k, base, rowLabel);
                // A long value (an update that failed, a city) stops short of its label.
                float room = rowRect.width() - 100 * k - rowLabel.measureText(labels[from + i]);
                c.drawText(TextUtils.ellipsize(values[from + i], rowValueFit, room, TextUtils.TruncateAt.END).toString(),
                        rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * pitch + 36) * k;
            // A list longer than the screen says where we are in it, as the app picker does. Photos say where they come
            // from; on the Sleep Timer screen, the timer and Position open a list on OK.
            String where = rows < labels.length ? (from + focus + 1) + " / " + labels.length + "  ·  " : "";
            c.drawText(where + (screen == HOME_SETTINGS && hsRow(from + focus) == PHOTOS_ROW ? (Photos.files(getContext()).isEmpty()
                    ? "OK: envie fotos do celular pelo QR code (mesma rede Wi-Fi)."
                    : "OK envia mais fotos pelo celular ou apaga. As setas ligam ou desligam.")
                    : screen == POWER && powerRow() != P_SHOW && unavailable(focus) == null ? Ui.tr("OK opens the list. ← → change the value.")
                    : screen == SAVER_SCREEN && focus == 0 ? "← → mudam o tempo sem usar o controle até a tela de descanso começar."
                    : screen == SAVER_SCREEN ? "OK mostra a tela de descanso agora. Qualquer tecla volta."
                    : screen == HOME_SETTINGS && hsRow(from + focus) == keyRow() ? "OK mostra um QR code: leia com a câmera do celular e cole a chave na página (mesma rede), ou digite."
                    : screen == HOME_SETTINGS && hsRow(from + focus) == ASSIST_ROW ? "Ligado, o botão do microfone abre o Google Assistente. Desligado, ele não faz nada (e libera memória)."
                    : screen == HOME_SETTINGS && hsRow(from + focus) == CAST_ROW ? "Ligado, dá para transmitir do celular para a TV. Desligado, a TV some da lista (e libera memória)."
                    : screen == HOME_SETTINGS && hsRow(from + focus) == IPTV_BACK_ROW ? "OK troca a lista atual pela anterior (e guarda a atual como backup)."
                    : screen == HOME_SETTINGS && hsRow(from + focus) == IPTV_ROW ? "OK baixa a lista para Download. No TiviMate: Adicionar playlist > M3U > Selecionar playlist local."
                    : Ui.tr("OK or the arrows change the value.")), LIST_X * k + 30 * k, hy, hint);
            if (keys) {
                c.drawText(RemoteKeys.VESTEL ? Ui.tr("YouTube, Prime Video and Google Play: the TV itself controls those buttons.")
                        : RemoteKeys.KTC ? Ui.tr("Netflix, YouTube and Prime Video: the TV itself controls those buttons.")
                        : RemoteKeys.SONY ? Ui.tr("Sony default: the button opens what it always did.")
                        : RemoteKeys.FORMOVIE ? Ui.tr("The other buttons: the TV itself controls them.")
                        : RemoteKeys.XIAOMI ? Ui.tr("Xiaomi default: the button opens what it always did.")
                        : RemoteKeys.HISENSE ? Ui.tr("Netflix: the TV itself controls that button.")
                        : RemoteKeys.supported() ? Ui.tr("Disney and Globoplay: the TV itself controls those buttons.")
                        : Ui.tr("TCL remote buttons: on this TV they work as always."), LIST_X * k + 30 * k, hy + 38 * k, hint);
                // No key service on this TV or in this APK (RemoteKeys.SERVICE): inside other apps the buttons do nothing.
                if (!RemoteKeys.SERVICE) c.drawText(Ui.tr("On this TV they work on Free My TV's home screen only."),
                        LIST_X * k + 30 * k, hy + 76 * k, hint);
            }
        }

        private void activate() {
            Context c = getContext();
            if (tellWhy()) return;
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                boolean already = wantFree ? freeActive() : HomeMode.ORIGINAL.equals(state.optString("mode", HomeMode.ORIGINAL));
                if (!already && !busy()) {
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                screen = MAIN;
                focus = 0;
            } else if (screen == PICKER && pickFor == PICK_CITY) {
                String[] city = cities.get(pickScroll + focus);
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(c).settingsChanged();
                focus = hsIndex(CITY_ROW);
                pickScroll = 0;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER && pickFor <= PICK_SLEEP) {
                pickPower();
            } else if (screen == PICKER && pickFor == PICK_LANGUAGE) {
                Prefs.setLanguage(languageChoices()[pickScroll + focus]);
                SleepOverlay.update(c);   // the pill speaks it too
                focus = hsIndex(0);
                pickScroll = 0;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
                HomeMode.ensureRemoteService(c, 3_000);
                focus = pickFor - 1;
                screen = REMOTE_KEYS;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == REMOTE_KEYS) {
                openPicker(focus + 1);
            } else if (screen == HOME_SETTINGS && hsRow(pickScroll + focus) == 0) {
                openPicker(PICK_LANGUAGE);
            } else if (screen == HOME_SETTINGS && hsRow(pickScroll + focus) == CITY_ROW) {
                askCity();
                return;
            } else if (screen == HOME_SETTINGS && hsRow(pickScroll + focus) == TITLE_ROW) {
                askTitle();
                return;
            } else if (screen == HOME_SETTINGS && hsRow(pickScroll + focus) == CAMS_EDIT_ROW) {
                activity.startActivity(new Intent(getContext(), CamerasActivity.class));
                return;
            } else if (screen == HOME_SETTINGS && hsRow(pickScroll + focus) == CAMS_RESET_ROW) {
                askResetCams();
                return;
            } else if (screen == HOME_SETTINGS && hsRow(pickScroll + focus) == PHOTOS_ROW) {
                openPhotoScreen();
                invalidate();
                return;
            } else if (screen == SAVER_SCREEN) {
                if (focus == 0) {
                    stepSaverTime(1);
                } else {
                    pause();
                    activity.showPreview();
                }
                invalidate();
                return;
            } else if (screen == PHOTO_PICK) {
                activatePick();
                invalidate();
                spoken.focusMoved();
                return;
            } else if (screen == PHOTO_SEND) {
                activatePhoto();
                invalidate();
                spoken.focusMoved();
                return;
            } else if (screen == HOME_SETTINGS && hsRow(pickScroll + focus) == IPTV_ROW) {
                downloadIptv();
                return;
            } else if (screen == HOME_SETTINGS && hsRow(pickScroll + focus) == IPTV_BACK_ROW) {
                restoreIptv();
                return;
            } else if (screen == THEME_SCREEN) {
                adjustTheme(1);
                invalidate();
                return;
            } else if (screen == LAYOUT_SCREEN) {
                if (LAYOUTS[focus][2].isEmpty()) Prefs.setLayout(LAYOUTS[focus][0]);   // os "em breve" ainda não
                invalidate();
                return;
            } else if (screen == HOME_SETTINGS && hsRow(pickScroll + focus) == keyRow()) {
                openKeyScreen();
            } else if (screen == TMDB_KEY) {
                activateKey();
                invalidate();
                spoken.focusMoved();
                return;
            } else if (screen == HOME_SETTINGS && hsRow(pickScroll + focus) == PICKS_ROW) {
                countServicesOff();
                screen = PICKS;
                pickScroll = 0;   // this list is short: it draws from row 0
                focus = 0;
            } else if (screen == PICKS && focus == 1) {
                services.clear();
                services.addAll(Recs.installedServices(c));
                screen = PICK_FROM;
                pickScroll = 0;
                focus = 0;
            } else if (screen == PICK_FROM) {
                toggleService();
                invalidate();
                return;
            } else if (screen == POWER) {
                activatePower();
            } else if (screen == PICKS) {
                adjust(1);
                invalidate();
                return;
            } else if (screen == HOME_SETTINGS) {
                adjust(1);
                invalidate();
                return;
            } else if (focus == M_HOME) {
                if (busy()) return;
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (focus == M_SAVER) {
                saverMs = currentSaverMs();
                screen = SAVER_SCREEN;
                focus = 0;
                pickScroll = 0;
            } else if (focus == M_SETTINGS || focus == M_EXTRAS) {
                hsGroup = focus == M_SETTINGS ? 0 : 1;
                screen = HOME_SETTINGS;
                focus = 0;
                pickScroll = 0;
            } else if (focus == M_SHELF) {
                openShelfEditor(false);
            } else if (focus == M_TOP) {
                openShelfEditor(true);
            } else if (focus == M_POWER) {
                pickScroll = 0;
                screen = POWER;
                focus = 0;
            } else if (focus == M_THEMES) {
                screen = THEME_SCREEN;
                focus = 0;
                pickScroll = 0;
            } else if (focus == M_LAYOUT) {
                screen = LAYOUT_SCREEN;
                focus = 0;
                for (int i = 0; i < LAYOUTS.length; i++) if (LAYOUTS[i][0].equals(Prefs.layout())) focus = i;
                pickScroll = 0;
            } else if (focus == M_UPDATES) {
                updateNow();
                return;
            } else if (focus == M_DONATE) {
                return;   // o QR já está à direita
            } else {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
