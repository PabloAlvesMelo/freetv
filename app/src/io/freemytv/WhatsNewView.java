package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

/**
 * "Novidades": a card shown once after an update, over the home screen, in the app's style (no shaders): a dim over the
 * home and a dark rounded card with the version's highlights. OK or Back closes it. Added over the home by HomeActivity.
 */
final class WhatsNewView extends View {
    interface OnDone { void run(); }

    static final String TITLE = "Novidades";
    /** Os destaques da versão atual (português, o idioma base). Vazio: nada a mostrar. Atualize a cada versão. */
    static final String[] ITEMS = {
            "Abrir ao iniciar: escolha um app para abrir sozinho quando a TV liga (em Configurações).",
            "Lista IPTV: baixe a lista de canais para a pasta Download e selecione no TiviMate.",
            "Botão de TV: sem o TiviMate instalado, leva à Play Store para instalá-lo.",
    };

    private final OnDone onDone;
    private final RectF card = new RectF(), btn = new RectF();
    private final Paint scrim = new Paint();
    private final Paint cardFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint title, version, btnText;
    private TextPaint body;
    private float k = 1;
    private int w, h;
    private boolean okDown;

    WhatsNewView(Context c, OnDone onDone) {
        super(c);
        this.onDone = onDone;
        setFocusable(true);
        setContentDescription(TITLE);
        cardRim.setStyle(Paint.Style.STROKE);
    }

    @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = nw;
        h = nh;
        k = w / 1920f;
        scrim.setColor(0xCC000000);
        cardFill.setColor(0xF21C1C1E);
        cardRim.setColor(0x24FFFFFF);
        cardRim.setStrokeWidth(1.5f * k);
        btnFill.setColor(0xFFF5F5F7);
        dot.setColor(0xFFFFC266);
        title = Ui.text(Ui.displaySemibold, 48 * k, 0xFFFFFFFF);
        version = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
        btnText = Ui.text(Ui.medium, 30 * k, 0xFF1C1C1E);
        btnText.setTextAlign(Paint.Align.CENTER);
        body = new TextPaint(Ui.text(Ui.regular, 30 * k, 0xE6FFFFFF));
    }

    @Override protected void onDraw(Canvas c) {
        if (w == 0) return;
        c.drawRect(0, 0, w, h, scrim);

        float cw = 1120 * k, pad = 56 * k, bullet = 36 * k;
        int textW = (int) (cw - pad * 2 - bullet);
        float lineGap = 24 * k;
        StaticLayout[] lays = new StaticLayout[ITEMS.length];
        float itemsH = 0;
        for (int i = 0; i < ITEMS.length; i++) {
            lays[i] = new StaticLayout(ITEMS[i], body, textW, Layout.Alignment.ALIGN_NORMAL, 1.12f, 0, false);
            itemsH += lays[i].getHeight() + (i > 0 ? lineGap : 0);
        }
        float headerH = 110 * k;   // ícone + título + versão
        float btnH = 80 * k;
        float ch = pad + headerH + 30 * k + itemsH + 48 * k + btnH + pad;
        card.set((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2);
        float r = 36 * k;
        c.drawRoundRect(card, r, r, cardFill);
        c.drawRoundRect(card, r, r, cardRim);

        float x = card.left + pad, y = card.top + pad;
        Symbols.draw(getContext(), c, "sparkles", x + 26 * k, y + 26 * k, 52 * k, 0xFFFFC266);
        c.drawText(TITLE, x + 66 * k, y + 26 * k + Ui.halfCap(title), title);
        c.drawText("Melo Free TV " + versionName(), x + 66 * k, y + 76 * k + Ui.halfCap(version), version);

        y += headerH + 30 * k;
        for (StaticLayout lay : lays) {
            c.drawCircle(x + 7 * k, y + body.getTextSize() * 0.6f, 5 * k, dot);
            c.save();
            c.translate(x + bullet, y);
            lay.draw(c);
            c.restore();
            y += lay.getHeight() + lineGap;
        }

        float bw = 260 * k;
        btn.set(card.centerX() - bw / 2, card.bottom - pad - btnH, card.centerX() + bw / 2, card.bottom - pad);
        c.drawRoundRect(btn, btnH / 2, btnH / 2, btnFill);
        c.drawText("OK", btn.centerX(), btn.centerY() + Ui.halfCap(btnText), btnText);
    }

    private String versionName() {
        try {
            return getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** Modal: OK/Center/Back close it; arrows are swallowed (the home behind must not move); other keys pass through. */
    boolean onKey(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else {
                    if (okDown && !e.isCanceled()) onDone.run();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) onDone.run();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return false;
        }
    }
}
