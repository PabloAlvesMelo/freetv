"""Monta tv.m3u: os canais de canais.txt, na ordem e nos grupos dali, com o guia (EPG) embutido no cabeçalho."""
import re, sys, urllib.request, concurrent.futures as cf
EPG = "https://iptv-epg.org/files/epg-br.xml"
SRC = "https://iptv-org.github.io/iptv/countries/br.m3u"
testar = "--sem-teste" not in sys.argv

lines = urllib.request.urlopen(SRC, timeout=60).read().decode("utf-8").splitlines()
fonte = {}
for i, l in enumerate(lines):
    if l.startswith("#EXTINF") and i + 1 < len(lines):
        nome = l.rsplit(",", 1)[1].strip()
        j = i + 1
        extra = []
        while j < len(lines) and lines[j].startswith("#"):
            extra.append(lines[j]); j += 1
        fonte[nome] = (l, extra, lines[j].strip() if j < len(lines) else "")

pedidos = []
for l in open("canais.txt", encoding="utf-8"):
    l = l.strip()
    if not l or l.startswith("#"): continue
    grupo, nome = [p.strip() for p in l.split("|", 1)]
    pedidos.append((grupo, nome))

def ok(url):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=12) as r:
            return r.status < 400
    except Exception:
        return False

faltando = [n for g, n in pedidos if n not in fonte]
validos = [(g, n) for g, n in pedidos if n in fonte]
vivos = dict(zip(validos, cf.ThreadPoolExecutor(12).map(lambda p: ok(fonte[p[1]][2]) if testar else True, validos)))

out = ['#EXTM3U x-tvg-url="%s" url-tvg="%s"' % (EPG, EPG)]
fora = []
for g, n in validos:
    if not vivos[(g, n)]:
        fora.append(n); continue
    inf, extra, url = fonte[n]
    inf = re.sub(r'group-title="[^"]*"', 'group-title="%s"' % g, inf) if "group-title" in inf else inf.replace(",", ' group-title="%s",' % g, 1)
    out += [inf] + extra + [url]
open("tv.m3u", "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
print("no ar:", len(validos) - len(fora), "| fora do ar:", fora, "| nome não achado:", faltando)
