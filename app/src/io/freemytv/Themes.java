package io.freemytv;

/**
 * Os 8 temas do Melo Free TV: Pérola (claro) e Noite (escuro), mais seis coloridos. No modo automático, um tema de
 * dia e um de noite (trocam no nascer e no pôr do sol); no modo fixo, um só. Cada tema dá o fundo, o painel do
 * carrossel, a barra de apps e se o desenho é claro ou escuro.
 */
final class Themes {
    private Themes() {}

    static final class T {
        final String id, name;
        final boolean dark;
        final int bgTop, bgBottom, panel, shelf;
        // O fundo do painel de configurações (texto claro): o do tema nos escuros; nos claros, a mesma cor mais fechada.
        int setTop, setBottom;

        T(String id, String name, boolean dark, int bgTop, int bgBottom, int panel, int shelf) {
            this.id = id;
            this.name = name;
            this.dark = dark;
            this.bgTop = bgTop;
            this.bgBottom = bgBottom;
            this.panel = panel;
            this.shelf = shelf;
            this.setTop = bgTop;
            this.setBottom = bgBottom;
        }

        T settings(int top, int bottom) {
            setTop = top;
            setBottom = bottom;
            return this;
        }
    }

    static final T[] ALL = {
            new T("perola", "Pérola", false, 0xFFF5F5F7, 0xFFE2E2E7, 0xE6DCE2F4, 0xFFC9D3F0).settings(0xFF4C5470, 0xFF2C3146),
            new T("noite", "Noite", true, 0xFF1C1C1E, 0xFF0B0B0D, 0xE6232326, 0xFF2E2E33),
            new T("azul", "Azul", true, 0xFF0E1B2E, 0xFF070D17, 0xE6122C50, 0xFF183A66),
            new T("esmeralda", "Esmeralda", true, 0xFF0C221D, 0xFF06120F, 0xE60F3A30, 0xFF15493C),
            new T("ambar", "Âmbar", false, 0xFFFBF4E8, 0xFFF1E4CC, 0xE6F6E3C2, 0xFFF0D29E).settings(0xFF6A4E2A, 0xFF3E2D17),
            new T("indigo", "Índigo", true, 0xFF141826, 0xFF0A0C14, 0xE61A2034, 0xFF262E4A),
            new T("mostarda", "Mostarda", false, 0xFFFAF6E2, 0xFFEFE7C2, 0xE6EFE3A8, 0xFFE3D27A).settings(0xFF5E5626, 0xFF37321A),
            new T("grafite", "Grafite", true, 0xFF2A2C30, 0xFF161719, 0xE62E3136, 0xFF3A3E44),
    };

    static T byId(String id) {
        for (T t : ALL) if (t.id.equals(id)) return t;
        return ALL[0];
    }

    static int index(String id) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i].id.equals(id)) return i;
        return 0;
    }

    /** O tema em uso: o fixo, ou no automático o de dia ou o de noite (`timeNight`: do pôr ao nascer do sol). */
    static T active(boolean timeNight) {
        if (Prefs.themeFixedMode()) return byId(Prefs.themeFixed());
        return byId(timeNight ? Prefs.themeNight() : Prefs.themeDay());
    }
}
