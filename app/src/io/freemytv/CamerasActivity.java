package io.freemytv;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuração das câmeras (aberta pelo card de câmeras ou pelo painel): busca as câmeras ONVIF da rede, você marca
 * até duas (ou digita o IP), informa usuário e senha uma vez e salva; a tela inicial já começa a mostrar as imagens.
 */
public final class CamerasActivity extends Activity {
    private static final int MAX = 2;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Uma câmera da lista: a caixa para marcar e, quando marcada, o usuário e a senha dela. */
    private static final class Row {
        String xaddr;
        CheckBox box;
        LinearLayout creds;
        EditText user, pass;
    }

    private final List<Row> found = new ArrayList<>();
    private LinearLayout list;
    private TextView status;
    private EditText manual;
    private Button scan, save;
    private boolean busy;
    // Tema do app (claro de dia, escuro à noite), em tons neutros como o resto da tela inicial.
    private int bg, ink, dim, accent, button, buttonOn, buttonInk;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean night = HomeData.get(this).isNight(System.currentTimeMillis());
        bg = night ? 0xFF1C1C1E : 0xFFF5F5F7;
        ink = night ? 0xFFF5F5F7 : 0xFF1D1D1F;
        dim = night ? 0x99F5F5F7 : 0xFF6E6E73;
        accent = night ? 0xFFAEAEB2 : 0xFF48484A;
        button = night ? 0xFF2C2C2E : 0xFFE5E5EA;
        buttonOn = night ? 0xFFF5F5F7 : 0xFF1D1D1F;   // focado: invertido, como o foco da tela inicial
        buttonInk = night ? 0xFF1C1C1E : 0xFFF5F5F7;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(64), dp(40), dp(64), dp(40));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text(root, "Câmeras", 34, ink);
        title.setPadding(0, 0, 0, dp(6));
        text(root, "A busca mostra todas as câmeras da rede. Marque até duas e informe o usuário e a senha de cada uma (os mesmos do Onvifer).", 18, dim);
        status = text(root, "", 18, accent);
        status.setPadding(0, dp(14), 0, dp(6));

        scan = button(root, "Buscar câmeras na rede", v -> scan());
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, dp(8));
        root.addView(list);

        manual = field(root, "IP da câmera (se não aparecer na busca)", InputType.TYPE_CLASS_TEXT);
        Button add = button(root, "Adicionar IP à lista", v -> addManual());
        manual.setOnEditorActionListener((v, action, event) -> {   // o "confirmar" do teclado já adiciona
            addManual();
            return true;
        });
        save = button(root, "Salvar", v -> save());

        for (Onvif.Cam c : Onvif.saved()) addFound(c.xaddr, c);   // editando: as de antes, marcadas e com a senha
        scan();
    }

    private void scan() {
        if (busy) return;
        busy = true;
        status.setText("Buscando câmeras na rede…");
        new Thread(() -> {
            List<String> xs;
            String error = null;
            try {
                xs = Onvif.discover(4000);
            } catch (Exception e) {
                xs = new ArrayList<>();
                error = e.getMessage();
            }
            List<String> result = xs;
            String err = error;
            ui.post(() -> {
                busy = false;
                for (String x : result) addFound(x, null);
                status.setText(err != null ? "Não foi possível buscar: " + err
                        : found.isEmpty() ? "Nenhuma câmera encontrada. Digite o IP abaixo." : found.size() + " câmera(s). Marque até duas.");
                if (!found.isEmpty()) found.get(0).box.requestFocus();
            });
        }).start();
    }

    private Row addFound(String xaddr, Onvif.Cam saved) {
        for (Row r : found) if (xaddr.equals(r.xaddr)) return r;
        Row row = new Row();
        row.xaddr = xaddr;
        CheckBox b = new CheckBox(this);
        b.setText("Câmera " + Onvif.host(xaddr));
        b.setTag(xaddr);
        b.setTextColor(ink);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setPadding(dp(8), dp(6), 0, dp(6));
        row.box = b;
        list.addView(b);
        row.creds = new LinearLayout(this);
        row.creds.setOrientation(LinearLayout.VERTICAL);
        row.creds.setPadding(dp(40), 0, 0, dp(10));
        row.user = field(row.creds, "Usuário desta câmera", InputType.TYPE_CLASS_TEXT);
        row.pass = field(row.creds, "Senha desta câmera", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        row.creds.setVisibility(View.GONE);
        list.addView(row.creds);
        b.setOnCheckedChangeListener((v, on) -> {
            if (on && checkedCount() > MAX) {
                v.setChecked(false);
                status.setText("No máximo duas câmeras.");
                return;
            }
            row.creds.setVisibility(on ? View.VISIBLE : View.GONE);   // marcou: pede os dados dela
            if (on && row.user.getText().length() == 0) row.user.requestFocus();
        });
        found.add(row);
        if (saved != null) {
            row.user.setText(saved.user);
            row.pass.setText(saved.pass);
            b.setChecked(true);
        }
        return row;
    }

    private void addManual() {
        String ip = manual.getText().toString().trim();
        if (ip.isEmpty()) return;
        hideKeyboard(manual);
        Row r = addFound(Onvif.xaddrFor(ip), null);
        manual.setText("");
        if (checkedCount() < MAX) r.box.setChecked(true);
        else r.box.requestFocus();
    }

    private void hideKeyboard(View v) {
        android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private int checkedCount() {
        int n = 0;
        for (Row r : found) if (r.box.isChecked()) n++;
        return n;
    }

    private void save() {
        if (busy) return;
        List<String[]> picked = new ArrayList<>();   // {endereço, usuário, senha} de cada câmera marcada
        for (Row r : found) if (r.box.isChecked()) picked.add(new String[] {r.xaddr, r.user.getText().toString().trim(), r.pass.getText().toString()});
        if (picked.isEmpty()) {
            status.setText("Marque uma câmera (ou adicione o IP).");
            return;
        }
        busy = true;
        status.setText("Testando as câmeras…");
        new Thread(() -> {
            List<Onvif.Cam> cams = new ArrayList<>();
            String error = null;
            for (String[] p : picked) {
                String x = p[0];
                try {
                    Onvif.Cam c = new Onvif.Cam();
                    c.xaddr = x;
                    c.user = p[1];
                    c.pass = p[2];
                    c.snapshot = Onvif.snapshotUri(x, c.user, c.pass);
                    Onvif.snapshot(c, 320).recycle();   // a foto chega: pode salvar
                    c.name = "Câmera " + (cams.size() + 1);
                    cams.add(c);
                } catch (Exception e) {
                    error = Onvif.host(x) + ": " + e.getMessage();
                }
            }
            String err = error;
            ui.post(() -> {
                busy = false;
                if (cams.isEmpty()) {
                    status.setText("Não foi possível concluir. " + err);
                    return;
                }
                Onvif.save(cams);
                Prefs.setCamsOn(true);
                finish();
            });
        }).start();
    }

    // MARK: - Visual

    private TextView text(ViewGroup parent, String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        parent.addView(t);
        return t;
    }

    private EditText field(ViewGroup parent, String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextColor(ink);
        e.setHintTextColor(dim);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        // No controle remoto: o "confirmar" do teclado fecha o teclado e leva o foco ao próximo item da tela.
        e.setOnEditorActionListener((v, action, event) -> {
            hideKeyboard(v);
            View next = v.focusSearch(View.FOCUS_DOWN);
            if (next != null) next.requestFocus();
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        parent.addView(e, lp);
        return e;
    }

    private Button button(ViewGroup parent, String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setTextColor(ink);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(14));
        shape.setColor(button);
        b.setBackground(shape);
        b.setOnFocusChangeListener((v, focus) -> {
            shape.setColor(focus ? buttonOn : button);
            b.setTextColor(focus ? buttonInk : ink);
        });
        b.setOnClickListener(click);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(420), dp(56));
        lp.topMargin = dp(16);
        parent.addView(b, lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
