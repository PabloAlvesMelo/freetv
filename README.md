<div align="center">

# Melo Free TV

**Tela inicial sem anúncios para Android TV e Google TV.**

[**⬇ Baixar o Melo Free TV (APK)**](https://github.com/PabloAlvesMelo/freetv/releases/latest/download/MeloFreeTV.apk)

<sub>Sempre a versão mais nova · [todas as versões](https://github.com/PabloAlvesMelo/freetv/releases)</sub>

<img src=".github/melo-vitrine.jpg" width="880" alt="Tela inicial do Melo Free TV no layout Vitrine: a foto do título em destaque cobre a tela, com a fileira de filmes e séries e a barra de apps">

</div>

## O que ele faz

- **Sem anúncios:** nada de fileiras patrocinadas, banners ou vídeos que tocam sozinhos.
- **Seus apps em primeiro lugar:** uma barra com os apps de streaming que você usa, na ordem que você escolher.
- **Destaques:** filmes e séries em alta nos apps que você já tem (Recentes, Explorar, Filmes e Séries), com a chave
  gratuita do TMDB.
- **Seis layouts:** Clássico, Cinema, Menu lateral, Minimalista, Vitrine e Menu flutuante.
- **Oito temas** (Pérola, Noite, Azul, Esmeralda, Âmbar, Índigo, Mostarda e Grafite), com o modo automático de dia e
  de noite.
- **Tela de descanso** com as fotos dos destaques ou com as suas fotos, enviadas pelo celular com um QR code.
- **Extras:** relógio e clima, câmeras ONVIF, arquivos (X-plore), lista IPTV, desligamento automático e ícones de
  atalho no topo.
- **Atualiza sozinho:** procura versões novas neste repositório e instala depois de você confirmar.
- **Seguro:** não precisa de root e não desinstala nada da TV. A opção *Original* devolve a tela inicial de fábrica.

<img src=".github/melo-flutuante.jpg" width="880" alt="Layout Menu flutuante: o menu de vidro à esquerda sobre a foto do destaque">

## Instalar

Você precisa de uma TV ou aparelho com Android TV ou Google TV (Android 8 ou mais novo). Testado no Xiaomi Mi TV Stick.

### 1. Ative a depuração USB na TV

Em **Configurações > Preferências do dispositivo > Sobre**, clique 7 vezes em **Build** (ou *Versão do Android TV OS*).
Depois, em **Opções do desenvolvedor**, ative a **Depuração USB**.

A depuração USB é necessária porque o Melo Free TV usa o ADB da própria TV para trocar a tela inicial de fábrica
pela dele. Na primeira troca, a TV pergunta *Permitir depuração USB?*: marque *Sempre permitir* e confirme.

### 2. Instale o APK

Escolha uma das formas:

- **Pela própria TV:** instale o app *Downloader* pela Play Store, permita que ele instale apps (fontes desconhecidas)
  e abra o endereço `https://github.com/PabloAlvesMelo/freetv/releases/latest/download/MeloFreeTV.apk`.
- **Pelo computador:** baixe o [APK](https://github.com/PabloAlvesMelo/freetv/releases/latest/download/MeloFreeTV.apk)
  e instale com `adb install -r MeloFreeTV.apk`.

### 3. Ative na TV

Abra o **Melo Free TV**, vá em **Início** e escolha **Melo Free TV**. Para ver os destaques, coloque a sua chave
gratuita do TMDB em **Adicionais > Chave do TMDB** (pelo celular, com o QR code, ou digitando com o controle).

## Antes de desinstalar

**Volte para a tela original antes de desinstalar:** **Início > Original**. Se o app for desinstalado no modo Melo
Free TV, a TV fica sem tela inicial e o aparelho pode ficar inutilizado. Não nos responsabilizamos por danos causados
por desinstalar o app sem esse cuidado.

## Doação

Se você gostou do Melo Free TV, pode ajudar com um Pix de qualquer valor: em **Doação**, no fim das configurações do
app, há um QR code. Obrigado!

## Compilar

Sem Gradle: `./build.sh` usa o JDK 17 e o Android SDK (build-tools 34 e android-34) e lê o `config.properties`, que
não vai para o repositório. A versão publicada, sem chave nem cidade, sai de `./build.sh --generic`.

## Lista IPTV

`tv/lista.m3u` traz canais abertos e gratuitos, com o guia (EPG) no cabeçalho. Para editar, use `tv/canais.txt` e
rode `python tv/montar.py`.

## Créditos

O Melo Free TV é baseado no [Free My TV](https://github.com/simbasjungles/free-my-tv), de simbasjungles, sob a
licença MIT (veja [LICENSE](LICENSE)). O projeto original também tem versões para TVs LG (webOS) e Samsung (Tizen).
