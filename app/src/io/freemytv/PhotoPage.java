package io.freemytv;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A página que o celular abre pelo QR code de Adicionais › Fotos, para mandar fotos da galeria sem cabo nem X-plore:
 * http://<o endereço da TV na rede de casa>:<uma porta livre>/<um código aleatório>. O próprio celular reduz cada foto
 * (a TV usa 2112 × 1188) e manda uma por vez; a TV guarda na caixa de entrada de Photos, que importa como as do PC.
 * Fica aberta 10 minutos (ou até sair da tela), só na rede de casa, como a página da chave TMDB (KeyPage).
 */
final class PhotoPage {
    /** Chamado nas threads da página, nunca na da tela. */
    interface Listener {
        /** Uma foto começou a chegar (`number`: a de número tal desde que o código apareceu). */
        void receiving(int number);
        /** Uma foto chegou e já está na caixa de entrada (`total`: quantas desde que o código apareceu). */
        void received(int total);
        /** A página parou: depois de 10 minutos (expired) ou close(). Uma vez só. */
        void closed(PhotoPage page, boolean expired);
    }

    static final long OPEN_FOR = 10 * 60_000L;
    private static final int MAX_HEAD = 8192, MAX_PHOTO = 12 << 20, MAX_REQUESTS = 400, MAX_ANSWERING = 4;
    /** A pedido inteiro (cabeçalho e foto): uma foto de alguns MB numa Wi-Fi lenta ainda cabe. */
    private static final long READ_FOR_NS = 90_000_000_000L;

    final String url;
    private final Context app;
    private final ServerSocket server;
    private final String path;
    private final Listener listener;
    private final AtomicInteger answering = new AtomicInteger(), received = new AtomicInteger();
    private final AtomicBoolean ended = new AtomicBoolean();
    private volatile boolean closed;

    PhotoPage(Context c, InetAddress lan, Listener listener) throws IOException {
        this.app = c.getApplicationContext();
        this.listener = listener;
        byte[] random = new byte[12];
        new SecureRandom().nextBytes(random);
        path = "/" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        server = new ServerSocket(0, 4, lan);
        url = "http://" + lan.getHostAddress() + ":" + server.getLocalPort() + path;
        Thread t = new Thread(this::serve, "PhotoPage");
        t.setDaemon(true);
        t.start();
    }

    void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }

    private void serve() {
        long until = System.nanoTime() + OPEN_FOR * 1_000_000L;
        boolean expired = false;
        int requests = 0;
        try {
            while (!closed) {
                long left = (until - System.nanoTime()) / 1_000_000L;
                if (left <= 0 || requests >= MAX_REQUESTS) {
                    expired = true;
                    break;
                }
                server.setSoTimeout((int) Math.min(left, 30_000));
                Socket socket;
                try {
                    socket = server.accept();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                requests++;
                if (answering.incrementAndGet() > MAX_ANSWERING) {
                    answering.decrementAndGet();
                    socket.close();
                    continue;
                }
                Thread t = new Thread(() -> answer(socket), "PhotoPage answer");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (!closed) AppLog.w("photo page: " + e.getMessage());
        } finally {
            close();
            if (ended.compareAndSet(false, true)) listener.closed(this, expired);
        }
    }

    private void answer(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(15_000);
            handle(s.getInputStream(), s.getOutputStream(), System.nanoTime() + READ_FOR_NS);
        } catch (IOException | RuntimeException e) {
            // O celular saiu no meio: a página continua para o próximo envio (nada escapa desta thread).
        } finally {
            answering.decrementAndGet();
        }
    }

    private void handle(InputStream in, OutputStream out, long deadline) throws IOException {
        String head = readHead(in, deadline);
        if (head == null) return;
        String[] line = head.substring(0, Math.max(0, head.indexOf("\r\n"))).split(" ");
        String method = line.length > 1 ? line[0] : "", target = line.length > 1 ? line[1] : "";
        int query = target.indexOf('?');
        if (query >= 0) target = target.substring(0, query);
        if (!target.equals(path)) {
            respond(out, "404 Not Found", "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (method.equals("GET") || method.equals("HEAD")) {
            respond(out, "200 OK", "text/html; charset=utf-8", method.equals("HEAD") ? new byte[0] : page());
            return;
        }
        if (!method.equals("POST")) {
            respond(out, "405 Method Not Allowed", "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        int length = contentLength(head);
        if (closed) {
            respond(out, "410 Gone", "text/plain; charset=utf-8", "expired".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (length < 1000 || length > MAX_PHOTO) {
            respond(out, "413 Payload Too Large", "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        listener.receiving(received.get() + 1);
        File dir = Photos.privateInbox(app);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no inbox");
        // Escrita com nome oculto (.part: Photos não importa) e renomeada só inteira.
        String name = String.format(Locale.US, "celular-%013d-%03d.jpg", System.currentTimeMillis(), received.get());
        File part = new File(dir, "." + name + ".part"), done = new File(dir, name);
        boolean ok = false;
        try (OutputStream os = new FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int first = -1, second = -1;
            for (int n = 0; n < length; ) {
                int got = in.read(buf, 0, Math.min(buf.length, length - n));
                if (got < 0 || System.nanoTime() > deadline) throw new IOException("cut short");
                if (n == 0) { first = buf[0] & 0xFF; second = got > 1 ? buf[1] & 0xFF : -1; }
                os.write(buf, 0, got);
                n += got;
            }
            ok = first == 0xFF && second == 0xD8;   // só JPEG (o celular sempre manda assim)
        } finally {
            if (!ok || !part.renameTo(done)) part.delete();
        }
        if (!ok) {
            respond(out, "415 Unsupported Media Type", "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        int total = received.incrementAndGet();
        listener.received(total);
        respond(out, "200 OK", "application/json", ("{\"ok\":true,\"total\":" + total + "}").getBytes(StandardCharsets.UTF_8));
    }

    private static String readHead(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0, b;
        while (matched < 4 && (b = in.read()) >= 0) {
            if (head.size() >= MAX_HEAD || System.nanoTime() > deadline) return null;
            head.write(b);
            matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3)) ? matched + 1
                    : b == '\r' ? 1 : 0;
        }
        return matched == 4 ? head.toString(StandardCharsets.ISO_8859_1.name()) : null;
    }

    private static int contentLength(String head) {
        for (String h : head.split("\r\n")) {
            int colon = h.indexOf(':');
            if (colon > 0 && h.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    return Integer.parseInt(h.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static void respond(OutputStream out, String status, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff"
                + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /**
     * A página do celular: escolher fotos, e o script que reduz cada uma (o bastante para 2112 × 1188) e manda uma por
     * vez, com a barra de andamento e a etapa de cada foto (preparando, enviando %).
     */
    private byte[] page() {
        String html = "<!doctype html><html lang=pt><meta charset=utf-8>"
                + "<meta name=viewport content=\"width=device-width,initial-scale=1\"><title>Melo Free TV · Fotos</title><style>"
                + "body{margin:0 auto;max-width:34em;padding:24px;font:17px/1.5 system-ui,-apple-system,sans-serif;background:#1c1c1e;color:#f5f5f7}"
                + "h1{font-size:24px}label,button{display:block;box-sizing:border-box;width:100%;margin:14px 0;padding:16px;border:0;"
                + "border-radius:12px;font:600 18px system-ui,sans-serif;text-align:center}label{background:#3a3a3c;color:#f5f5f7}"
                + "button{background:#f5f5f7;color:#1c1c1e}button:disabled{opacity:.4}input{display:none}"
                + "#g{display:grid;grid-template-columns:repeat(4,1fr);gap:6px}#g img{width:100%;aspect-ratio:1;object-fit:cover;border-radius:6px}"
                + ".s{color:#98989d;font-size:15px}#m{font-size:19px;min-height:1.5em}.ok{color:#7ee08a}.no{color:#ffc266}"
                + "progress{width:100%;height:14px;accent-color:#7ee08a}</style>"
                + "<h1>Enviar fotos para a TV</h1>"
                + "<label for=f>Escolher fotos</label><input id=f type=file accept=\"image/*\" multiple>"
                + "<button id=b disabled>Enviar</button><progress id=p hidden></progress><p id=m></p><div id=g></div>"
                + "<p class=s>As fotos vão para a tela de descanso e o fundo da TV. Elas são reduzidas aqui no celular antes de"
                + " enviar. Esta página funciona por 10 minutos, na mesma rede Wi-Fi da TV.</p>"
                + "<script>"
                + "const f=document.getElementById('f'),g=document.getElementById('g'),b=document.getElementById('b'),"
                + "m=document.getElementById('m'),p=document.getElementById('p');let files=[];"
                // Sem miniaturas das originais: abrir várias fotos de câmera de uma vez travava o celular antes da 1ª.
                + "f.onchange=()=>{files=[...f.files];g.innerHTML='';p.hidden=true;b.disabled=!files.length;"
                + "b.textContent=files.length?'Enviar '+files.length+(files.length>1?' fotos':' foto'):'Enviar';m.className='';"
                + "m.textContent=files.length?(files.length>1?files.length+' fotos escolhidas':'1 foto escolhida')+'. Toque em Enviar.':''};"
                + "function load(x){return new Promise((ok,no)=>{const i=new Image();i.onload=()=>ok(i);i.onerror=no;i.src=URL.createObjectURL(x)})}"
                + "async function shrink(x){const i=await load(x),w=i.naturalWidth,h=i.naturalHeight,s=Math.min(1,Math.max(2112/w,1188/h));"
                + "const c=document.createElement('canvas');c.width=Math.round(w*s);c.height=Math.round(h*s);c.getContext('2d').drawImage(i,0,0,c.width,c.height);"
                + "URL.revokeObjectURL(i.src);const blob=await new Promise(r=>c.toBlob(r,'image/jpeg',.88));return {blob,url:URL.createObjectURL(blob)}}"
                + "function send(blob,on){return new Promise(ok=>{const x=new XMLHttpRequest();x.open('POST',location.pathname);"
                + "x.setRequestHeader('Content-Type','image/jpeg');x.upload.onprogress=e=>{if(e.lengthComputable)on(e.loaded/e.total)};"
                + "x.onload=()=>ok(x.status);x.onerror=()=>ok(0);x.send(blob)})}"
                + "b.onclick=async()=>{const N=files.length;b.disabled=true;f.disabled=true;g.innerHTML='';p.hidden=false;p.max=N;p.value=0;"
                + "let sent=0,bad=0;const say=(n,t)=>{m.className='';m.textContent='Foto '+(n+1)+' de '+N+': '+t};"
                // A próxima já vai sendo preparada enquanto esta sobe.
                + "say(0,'preparando…');let next=shrink(files[0]).catch(()=>null);"
                + "for(let n=0;n<N;n++){const s=await next;if(n+1<N)next=shrink(files[n+1]).catch(()=>null);"
                + "if(!s){bad++;p.value=n+1;continue}"
                + "const st=await send(s.blob,q=>{say(n,'enviando '+Math.round(q*100)+'%');p.value=n+q});"
                + "if(st==410){m.className='no';m.textContent='O código expirou. Na TV, escolha Novo código e leia de novo.';return}"
                + "if(st==200){sent++;const i=new Image();i.src=s.url;g.appendChild(i)}else bad++;"
                + "p.value=n+1;if(n+1<N)say(n+1,'preparando…')}"
                + "m.className=bad?'no':'ok';m.textContent=(sent==1?'1 foto enviada':sent+' fotos enviadas')+(bad?' ('+bad+' com erro)':'')+'. Já aparecem na TV.';"
                + "f.disabled=false;f.value='';files=[];b.textContent='Enviar'};"
                + "</script></html>";
        return html.getBytes(StandardCharsets.UTF_8);
    }

    @Override public String toString() {
        return String.format(Locale.ROOT, "PhotoPage(port %d)", server.getLocalPort());   // nunca o código
    }
}
