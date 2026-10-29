package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Câmeras ONVIF, para o card de câmeras da tela inicial: a busca na rede (WS-Discovery), o endereço da foto de cada
 * câmera (GetCapabilities → GetProfiles → GetSnapshotUri, com a senha em WS-Security) e a foto em si (HTTP com
 * autenticação Basic ou Digest). Tudo bloqueante: chamar fora da thread principal.
 */
final class Onvif {
    private Onvif() {}

    /** Uma câmera salva: o nome, o endereço do serviço ONVIF, o da foto e a senha. */
    static final class Cam {
        String name = "", xaddr = "", snapshot = "", user = "", pass = "";

        JSONObject json() throws org.json.JSONException {
            return new JSONObject().put("name", name).put("xaddr", xaddr).put("snapshot", snapshot).put("user", user).put("pass", pass);
        }

        static Cam from(JSONObject o) {
            Cam c = new Cam();
            c.name = o.optString("name");
            c.xaddr = o.optString("xaddr");
            c.snapshot = o.optString("snapshot");
            c.user = o.optString("user");
            c.pass = o.optString("pass");
            return c;
        }
    }

    static List<Cam> saved() {
        List<Cam> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.cams());
            for (int i = 0; i < a.length(); i++) out.add(Cam.from(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    static void save(List<Cam> cams) {
        JSONArray a = new JSONArray();
        try {
            for (Cam c : cams) a.put(c.json());
        } catch (org.json.JSONException ignored) {
        }
        Prefs.setCams(a.toString());
    }

    // MARK: - Busca (WS-Discovery)

    /** Os endereços ONVIF (XAddrs) das câmeras que responderam em `millis`. */
    static List<String> discover(int millis) throws Exception {
        String probe = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\""
                + " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">"
                + "<e:Header><w:MessageID>uuid:" + UUID.randomUUID() + "</w:MessageID>"
                + "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>"
                + "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>"
                + "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>";
        Set<String> found = new LinkedHashSet<>();
        byte[] data = probe.getBytes(StandardCharsets.UTF_8);
        try (MulticastSocket s = new MulticastSocket()) {
            s.setTimeToLive(4);
            s.setSoTimeout(400);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            for (int i = 0; i < 2; i++) s.send(new DatagramPacket(data, data.length, group, 3702));
            long end = System.currentTimeMillis() + millis;
            byte[] buf = new byte[16384];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                Matcher m = Pattern.compile("XAddrs>([^<]+)<").matcher(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                while (m.find()) {
                    for (String x : m.group(1).trim().split("\\s+")) {
                        if (x.startsWith("http://")) { found.add(x); break; }   // o primeiro http de cada câmera
                    }
                }
            }
        }
        return new ArrayList<>(found);
    }

    /** O endereço ONVIF de um IP digitado à mão (o caminho padrão). */
    static String xaddrFor(String host) {
        return host.startsWith("http") ? host : "http://" + host + "/onvif/device_service";
    }

    static String host(String xaddr) {
        try {
            return new URL(xaddr).getHost();
        } catch (Exception e) {
            return xaddr;
        }
    }

    // MARK: - Endereço da foto (SOAP)

    /** GetCapabilities → GetProfiles → GetSnapshotUri: o endereço da foto do primeiro perfil da câmera. */
    static String snapshotUri(String xaddr, String user, String pass) throws Exception {
        String caps = soap(xaddr, user, pass,
                "<GetCapabilities xmlns=\"http://www.onvif.org/ver10/device/wsdl\"><Category>All</Category></GetCapabilities>");
        String media = first(caps, "(?s)Media>.*?XAddr>([^<]+)<");
        if (media == null) media = xaddr;
        String profiles = soap(media, user, pass, "<GetProfiles xmlns=\"http://www.onvif.org/ver10/media/wsdl\"/>");
        String token = first(profiles, "Profiles[^>]*token=\"([^\"]+)\"");
        if (token == null) throw new Exception("a câmera não informou perfis");
        String snap = soap(media, user, pass, "<GetSnapshotUri xmlns=\"http://www.onvif.org/ver10/media/wsdl\"><ProfileToken>"
                + token + "</ProfileToken></GetSnapshotUri>");
        String uri = first(snap, "Uri>([^<]+)<");
        if (uri == null) throw new Exception("a câmera não tem endereço de foto");
        return uri.replace("&amp;", "&").trim();
    }

    private static String first(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String soap(String url, String user, String pass, String body) throws Exception {
        String header = user.isEmpty() ? "" : "<s:Header>" + security(user, pass) + "</s:Header>";
        String env = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">"
                + header + "<s:Body>" + body + "</s:Body></s:Envelope>";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
        try (OutputStream o = c.getOutputStream()) {
            o.write(env.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String text = in == null ? "" : new String(read(in), StandardCharsets.UTF_8);
        c.disconnect();
        if (code == 401 || text.contains("NotAuthorized")) throw new Exception("usuário ou senha incorretos");
        if (code >= 400) throw new Exception("a câmera respondeu " + code);
        return text;
    }

    /** WS-Security UsernameToken com PasswordDigest: base64(sha1(nonce + created + senha)). */
    private static String security(String user, String pass) throws Exception {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String created = f.format(new Date());
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(nonce);
        sha.update(created.getBytes(StandardCharsets.UTF_8));
        sha.update(pass.getBytes(StandardCharsets.UTF_8));
        String digest = Base64.encodeToString(sha.digest(), Base64.NO_WRAP);
        return "<Security s:mustUnderstand=\"1\" xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\">"
                + "<UsernameToken><Username>" + escape(user) + "</Username>"
                + "<Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">" + digest + "</Password>"
                + "<Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">"
                + Base64.encodeToString(nonce, Base64.NO_WRAP) + "</Nonce>"
                + "<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" + created + "</Created>"
                + "</UsernameToken></Security>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // MARK: - A foto

    /** A foto da câmera, reduzida para no máximo `maxWidth` de largura (o card é pequeno e o stick tem pouca memória). */
    static Bitmap snapshot(Cam cam, int maxWidth) throws Exception {
        byte[] data = get(cam.snapshot, cam.user, cam.pass);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b == null) throw new Exception("a câmera não mandou uma imagem");
        return b;
    }

    /** GET com a senha: sem autenticação primeiro; num 401, Basic ou Digest, como a câmera pedir. */
    private static byte[] get(String url, String user, String pass) throws Exception {
        HttpURLConnection c = open(url, null);
        int code = c.getResponseCode();
        if (code == 401 && !user.isEmpty()) {
            String challenge = c.getHeaderField("WWW-Authenticate");
            c.disconnect();
            String auth;
            if (challenge != null && challenge.trim().toLowerCase(Locale.US).startsWith("digest")) {
                auth = digest(challenge, new URL(url), user, pass);
            } else {
                auth = "Basic " + Base64.encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            }
            c = open(url, auth);
            code = c.getResponseCode();
        }
        if (code == 401) throw new Exception("usuário ou senha incorretos");
        if (code >= 400) throw new Exception("a câmera respondeu " + code);
        byte[] data = read(c.getInputStream());
        c.disconnect();
        return data;
    }

    private static HttpURLConnection open(String url, String auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        if (auth != null) c.setRequestProperty("Authorization", auth);
        return c;
    }

    private static String digest(String challenge, URL url, String user, String pass) throws Exception {
        String realm = param(challenge, "realm"), nonce = param(challenge, "nonce"), qop = param(challenge, "qop");
        String opaque = param(challenge, "opaque");
        String uri = url.getFile().isEmpty() ? "/" : url.getFile();
        String ha1 = md5(user + ":" + realm + ":" + pass), ha2 = md5("GET:" + uri);
        String cnonce = Long.toHexString(new SecureRandom().nextLong()), nc = "00000001";
        boolean auth = qop != null && qop.contains("auth");
        String response = auth ? md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2) : md5(ha1 + ":" + nonce + ":" + ha2);
        StringBuilder h = new StringBuilder("Digest username=\"").append(user).append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce).append("\", uri=\"").append(uri).append("\", response=\"").append(response).append('"');
        if (auth) h.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"');
        if (opaque != null) h.append(", opaque=\"").append(opaque).append('"');
        return h.toString();
    }

    private static String param(String header, String name) {
        Matcher m = Pattern.compile(name + "=\"?([^\",]+)\"?").matcher(header);
        return m.find() ? m.group(1) : null;
    }

    private static String md5(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : d) out.append(String.format("%02x", b));
        return out.toString();
    }

    private static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }
}
