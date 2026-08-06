# mdblist hub — Android TV

App nativo em Kotlin para Android TV: player **libVLC** embutido, interface no
vocabulário do **Kodi/Estuary**, **workers dedicados** de metadados e **cache
persistente** em Room.

Não é o app web empacotado. Não há WebView em lugar nenhum — o projeto Capacitor
em [`../android`](../android) e o site em
[`../../mdblist-hub/src`](../../mdblist-hub/src) continuam existindo e
independentes deste.

```bash
cd android-native
./gradlew assembleDebug     # APKs por ABI em app/build/outputs/apk/debug
./gradlew assembleRelease   # com R8; ~56 MB por ABI
```

Precisa de JDK 17+ e do Android SDK. O `local.properties` aponta para
`/opt/android-sdk`.

---

## Por que nativo

Três coisas que o app web não conseguia fazer, e que motivaram o projeto:

1. **Containers.** MKV, AVI, TS e HLS não tocam em `<video>`. A maior parte do
   que os addons devolvem é MKV. Com libVLC embutido, qualquer link direto toca.
2. **Cold start.** O site pedia ~25 requisições antes de pintar a home. Aqui a
   home vem do Room no primeiro frame, e a rede nunca está no caminho crítico.
3. **CORS.** As escritas de watchlist precisam de um proxy no navegador porque o
   mdblist responde 405 ao preflight OPTIONS. Um cliente nativo não tem essa
   regra — o proxy simplesmente não existe aqui.

---

## Módulos

```
:core:model      Tipos de domínio. Kotlin puro, sem Android.
:core:network    Retrofit/OkHttp: mdblist, TMDB, OMDb, Stremio. Cache de disco HTTP.
:core:database   Room. É a fonte da verdade de tudo que a tela lê.
:core:data       Repositórios cache-first, sessão em DataStore, workers.
:core:ui         Design system de 10 pés (Compose + androidx.tv).
:player          libVLC. O único módulo que o vê.
:app             Telas, navegação, grafo de objetos.
```

Sem framework de DI. O grafo é raso o bastante para caber em construtores —
veja [`DataGraph`](core/data/src/main/kotlin/com/mdblisthub/tv/core/data/DataGraph.kt).
Isso também poupa um processador de anotações no build.

---

## O player nunca pergunta qual fonte usar

Esta é a decisão de produto que mais amarra código, então vale explicitar.

Ao dar play, o app pergunta a todos os addons instalados em paralelo, ordena o
que voltou (melhor qualidade primeiro, depois maior arquivo) e entrega **a lista
inteira** ao [`PlaybackController`](player/src/main/kotlin/com/mdblisthub/tv/player/PlaybackController.kt).
Ele percorre a fila até uma fonte produzir imagem. Duas passadas antes de
desistir — um CDN que devolve 403 na primeira volta costuma ter emitido um token
novo quando a fila retorna.

Sucesso é **saída de vídeo** (`MediaPlayer.Event.Vout`), não o VLC dizer
"playing": o VLC diz playing assim que abre uma conexão, e uma página de erro com
200 satisfaz isso tão bem quanto um arquivo real. O primeiro frame decodificado
não mente.

Tudo isso acontece atrás de um véu com o fanart do título. Nove mirrors podem ser
testados e descartados sem que nada apareça além de "Preparando a reprodução…".
Se a cascata fosse visível seria só uma versão mais lenta de um seletor.

O app web tem o mesmo comportamento, em
[`features/player`](../../mdblist-hub/src/app/features/player/player.ts).

---

## Cache e workers

O Room é a fonte da verdade. `observe*` sempre emite do banco, imediatamente;
`refresh*` escreve por cima em background. Nenhuma tela espera a rede.

A tabela de metadados é dividida em duas de propósito:

- `media` — o card, que o sync de listas já traz de graça. Barato de escrever
  para mil títulos de uma vez.
- `media_detail` — a ficha: elenco, notas, artwork, temporadas. Caro, vem de três
  APIs, e só vale a pena para títulos que alguém abre.

Assim o refresh noturno reescreve todos os cards sem tocar nas fichas que um
worker levou minutos para montar, e cada uma envelhece no seu próprio ritmo
([`CachePolicy`](core/data/src/main/kotlin/com/mdblisthub/tv/core/data/CachePolicy.kt)).

| Worker | Quando | O que faz |
| --- | --- | --- |
| `ListSyncWorker` | 6/6 h + ao abrir | Listas e seus itens → Room |
| `MetadataWorker` | 4/4 h, bateria ok | Hidrata 40 fichas por passada |
| `ArtworkWorker` | após cada sync | Pré-aquece os 8 primeiros pôsteres de cada fileira |
| `ResumeSyncWorker` | 3/3 h | "Continuar assistindo" |
| `CachePruneWorker` | 24/24 h | Descarta fichas órfãs |

Prefetch por foco **não** é WorkManager. É a
[`MetadataPrefetcher`](core/data/src/main/kotlin/com/mdblisthub/tv/core/data/MetadataPrefetcher.kt),
com escopo de processo: só serve nos próximos segundos, e se o usuário seguir
adiante deve ser descartado. É o que faz abrir uma ficha parecer instantâneo.

---

## Tamanho

| | arm64-v8a | armeabi-v7a | universal |
| --- | --- | --- | --- |
| release | 56 MB | 41 MB | 155 MB |

Dentro do arm64: `libvlc.so` 44 MB, `libc++_shared.so` 8,9 MB, nosso dex 3,5 MB.
Ou seja: o app é 3,5 MB e o VLC é o resto. É o preço de decodificar tudo, e por
isso o build tem splits por ABI — instalar o universal é carregar três ABIs sem
usar duas.

---

## Versões travadas

`compileSdk` está em 36 porque é a plataforma instalada na máquina de build. Os
artefatos AndroidX lançados após o salto para SDK 37 (`core` 1.18+, `lifecycle`
2.11+) se recusam a compilar contra 36 e exigem AGP 9.1, então esses dois estão
segurados uma versão atrás em
[`gradle/libs.versions.toml`](gradle/libs.versions.toml). Subir plataforma, AGP e
esses dois é uma mudança única e coordenada.

libVLC está no ramo estável **3.7.5**, não no `4.0.0-eap`.
