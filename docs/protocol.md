# Loungo Protokolü — v1 (donduruldu, T16, 2026-10-31)

> Bu belgeyi Claude değiştirir. Değişiklik gerekiyorsa sürüm numarası (`v`) artırılır.

## 1. Bağlantı
- TV ağda `loungo-xxxx.local` adıyla duyurulur (mDNS); eski `phantom-xxxx.local` adı da yanıtlanmaya devam eder, böylece eski adresi kaydeden telefonlar çalışır.
- Bağlantı adresi: `ws://<TV-IP>:8765/ws?k=<token>`. Tek port kullanılır; aynı portta `GET /` PWA'yı döner.
- Token yanlışsa ya da yoksa upgrade **HTTP 401** ile reddedilir.
- **Tek istemci:** Yeni bir bağlantı kabul edilince eski bağlantı `4001 replaced` koduyla kapatılır.
- **El sıkışma:** PWA bağlantı açılınca `hello` gönderir, TV de `hello` ile cevap verir. TV, `hello` gelmeden başka mesaj işlemez.
- **Sürüm uyuşmazlığı:** `v` farklıysa TV `err{code:"version"}` gönderir ve bağlantıyı `4002` koduyla kapatır.
- **Kapanış kodları:** `4001` başka istemci bağlandı · `4002` sürüm uyuşmazlığı · `4003` eşleşme sıfırlandı ya da token geçersiz/eksik (PWA token'ı siler, yeniden eşleşme ister).
- **Canlılık:** Tarayıcılar WebSocket ping frame'i gönderemez. Bu yüzden PWA 5 sn'de bir `{"t":"ping"}` gönderir, TV de `{"t":"pong"}` ile cevap verir. TV 15 sn boyunca hiçbir frame almazsa bağlantıyı kapatır. PWA ise 10 sn boyunca hiçbir şey almazsa yeniden bağlanır. *(v1 düzeltmesi, T19 öncesi; henüz bu bölümü kullanan bir uygulama yok.)*
- **Yeniden bağlanma:** Bağlantı koparsa PWA 0,5 sn → 1 sn → 2 sn → 5 sn (üst sınır) aralıklarla yeniden dener.
- **Wi-Fi:** İstemci bağlıyken TV'de `WifiLock(WIFI_MODE_FULL_LOW_LATENCY)` tutulur. Gerekçe: T12'de boşta p95 55 ms, yük altında 9 ms çıktı; bu, Wi-Fi güç tasarrufunun etkisi.

## 2. Binary mesajlar (little-endian, ilk bayt = tip)

| Tip | Yön | Yük | Boyut | Anlam |
|---|---|---|---|---|
| `0x01` MOVE | 📱→📺 | `dx:i16, dy:i16` | 5 B | İmleç göreli hareketi, TV piksel cinsinden. TV: `x = clamp(x+dx, 0, w-1)` |
| `0x02` SCROLL | 📱→📺 | `sx:i16, sy:i16` | 5 B | Kaydırma, CSS px cinsinden. `sy>0` → içerik aşağı kayar, yani sayfa ilerler. TV bunu `AXIS_VSCROLL/HSCROLL` değerine çevirir; çevirme katsayısı T21'de kalibre edilir |
| `0x03` BUTTON | 📱→📺 | `state:u8 (0 up, 1 down), btn:u8 (0 sol, 1 sağ)` | 3 B | Tık = down + up. Sürükleme = down + MOVE'lar + up |
| `0x20` THUMB | 📺→📱 | `reqId:u16` + JPEG baytları | değişken | `snap` isteğinin küçük resmi (320×180, RGB_565, JPEG kalite 70) |

- PWA, MOVE ve SCROLL mesajlarını `requestAnimationFrame` başına birleştirir; en fazla 60 Hz gönderilir. Değer i16 sınırını aşarsa birden fazla mesaja bölünür.
- İvme ve momentum PWA'da hesaplanır. TV ham değeri uygular.

## 3. JSON mesajlar (text frame, her mesajda `t` alanı)

### 📱 → 📺
| `t` | Alanlar | Anlam |
|---|---|---|
| `hello` | `v:1`, `client:"pwa"` | El sıkışma |
| `ping` | — | Canlılık |
| `nav` | `url` | Adrese git. Şema yoksa `https://` eklenir; boşluk içeriyorsa ya da nokta yoksa arama motoruna gönderilir |
| `back` · `fwd` · `reload` · `stop` | — | Gezinme |
| `text` | `value` | Odaklı alanın **tüm** değeri. React native setter + `input` olayı kullanılır |
| `key` | `k`: `Enter`, `Tab`, `Escape`, `Backspace`, `ArrowUp/Down/Left/Right` | Tek tuş |
| `tab` | `url`, `scrollY` | Sekme değişimi: TV URL'i yükler, sonra `scrollY` konumuna gider |
| `snap` | `reqId:u16` | TV önce `snap` JSON'unu, ardından aynı `reqId` ile `THUMB` binary mesajını gönderir |
| `zoom` | `text:int` (%, 50–300) | `textZoom` |
| `ua` | `mode:"mobile"\|"desktop"` | Geçerli host için kaydedilir ve sayfa yeniden yüklenir |
| `popup` | `id`, `action:"open"\|"deny"\|"newtab"` | Kapı bekçisi kararı |
| `media_open` | `url` | Native oynatıcıda aç (Faz 6) |
| `reset_pair` | — | TV yeni token üretir, bağlantıyı `4003` ile kapatır, QR'ı gösterir *(v1 eki, T29)* |
| `adblock` | `on:bool` | Geçerli host için reklam engellemeyi aç/kapat (SitePrefs) *(v1 eki, T32)* |
| `lite` | `mode:"auto"\|"on"\|"off"` | Hafif görünüm (arka plan bulanıklığını kapatır). TV kaydeder; `hello.lite` güncel değeri bildirir |
| `clear_data` | — | Çerezler, site depolaması, önbellek, form verisi, geri listesi ve olay kaydı silinir; eşleşme, reklam ayarları ve kaldığınız yerler kalır. TV `{t:"cleared"}` ile yanıt verir |
| `adblock_update` | — | TV hosts listesini arka planda StevenBlack'ten günceller *(v1 eki, T33b)* |
| `player_cmd` | `a:"play"\|"pause"\|"seek"\|"close"`, `ms?` (seek için mutlak konum) | Native oynatıcı kontrolü *(v1 eki, T39)* |
| `pointer` | `x,y` (0–1 arası oran) | İmleci mutlak konuma taşır. Örnek: bağlantıdan sonra merkeze almak |

### 📺 → 📱
| `t` | Alanlar | Anlam |
|---|---|---|
| `hello` | `v:1`, `w`, `h` (WebView px), `dpr`, `ua` | El sıkışma cevabı |
| `pong` | — | `ping` cevabı |
| `state` | `url`, `title`, `canBack`, `canFwd`, `loading:bool`, `progress:0–100`, `ua:"mobile"\|"desktop"`, `zoom:int`, `popupOpen:bool`, `adblock:bool`, `blocked:int` (bu sayfada engellenen istek) *(son beşi v1 eki, T25/T27/T32)* | Değiştiği anda gönderilir, `progress` için en fazla 5 Hz |
| `editable` | `on:bool`, `src:"hover"\|"focus"`, `value?`, `input?` (`text`/`password`/`email`/`search`/`url`/`number`), `enter?` (`go`/`search`/`next`/`done`) | `hover`: imlecin altındaki öğe düzenlenebilir mi (tahmin, ~10 Hz kontrol, yalnızca değişince gönderilir). `focus`: sayfada odak değişti; `on:true` ise `value` dolu gelir. Telefondan gelen `text` için yankı gönderilmez *(v1 eki, T24)* |
| `snap` | `reqId`, `url`, `title`, `scrollY` | Sekme kaydı. Ardından `THUMB` gelir |
| `popup` | `id`, `url`, `gesture:bool` | Onay ister. 12 sn içinde cevap gelmezse TV pop-up'ı yok eder ve `popup_gone` gönderir |
| `popup_gone` | `id`, `reason:"timeout"\|"closed"\|"blocked"` | Pop-up kapandı |
| `fullscreen` | `on:bool` | Tam ekran video |
| `player` | `on:bool`, `playing:bool`, `pos:ms`, `dur:ms`, `title?` | Native oynatıcı durumu; açıkken 1 Hz ve her değişimde *(v1 eki, T39)* |
| `media` | `url`, `kind:"hls"\|"dash"\|"mp4"` | Oynatılabilir medya bulundu (Faz 6) |
| `err` | `code`, `msg` | Hata |

## 4. Kurallar
- Bilinmeyen `t` değeri ya da binary tip görmezden gelinir ve loglanır. Bağlantı kapatılmaz.
- Tüm JSON alanları küçük harf camelCase yazılır. Zorunlu olmayan alanlar gönderilmeyebilir.
- Sürüm 1'de sıkıştırma ve şifreleme yok, iletişim yalnızca LAN'da yapılır. Güvenlik token'a dayanır.
- **`back` önceliği (T26):** tam ekran video açıksa kapanır → görünür pop-up varsa (geçmişi yoksa) kapanır, varsa onda geri gidilir → ana sayfada `goBack`. Fiziksel kumandanın Geri tuşu da aynı sırayı izler; ana sayfada geçmiş yoksa uygulama arka plana alınır.


### `hello.pwa` (2026-11-05)
TV → telefon `hello` mesajında isteğe bağlı `pwa` alanı bulunur: TV'nin sunduğu telefon arayüzünün derleme kimliği (`assets/pwa/build.txt`). Telefondaki arayüzün kendi kimliği (`__PWA_BUILD__`) farklıysa sayfa bir kez yenilenir.

### TV → telefon: `tips`
`{t:"tips"}`: TV'deki ilk açılış turu ekrandayken telefon bağlanırsa (ya da tur bağlı bir telefonla başlarsa) gönderilir; telefon trackpad ipuçlarını ve düğme balonlarını yeniden gösterir.

## Android telefon uygulaması: keşif ve eşleşme (QR'sız)

- **Keşif:** TV, `_http._tcp.` kaydına ek olarak `_loungo._tcp.` DNS-SD kaydı yapar. Servis adı `loungo-xxxx`. TXT kaydındaki `n`, TV'nin görünen adıdır (varsa).
- **Kayıtlı token:** Telefonda token varsa önce `GET /pair/check?k=<token>` çağrılır. Cevap `200` ise TV'de soru çıkmaz.
- **`GET|POST /pair/request?name=<cihaz adı>`:**
  - `200 {"id":"..."}`: TV'de "Bu telefon bağlansın mı?" penceresi açılır. Aynı IP'den tekrar istenirse aynı `id` döner.
  - `429 {"status":"busy"}`: başka bir telefonun isteği bekliyor.
  - `503 {"status":"tv_busy"}`: o an TV'de Loungo ekranı yok.
- **`GET /pair/status?id=<id>`:** telefon bunu yaklaşık 1 sn arayla sorar. `id`, isteği yapan IP'ye bağlıdır.
  - `200 {"status":"pending"}`: cevap bekleniyor.
  - `200 {"status":"approved","token":"..."}`: onaylandı. Token, QR'daki ortak tokendir; "Eşleşmeyi sıfırla" ikisini birden geçersiz kılar.
  - `403 {"status":"denied"}`: reddedildi ya da 30 sn doldu.
  - `404 {"status":"unknown"}`: istek bilinmiyor.
  - `403` ve `404` cevapları `RateLimiter`'da hata sayılır.
- Bir cevap yalnızca bir kez okunur.
