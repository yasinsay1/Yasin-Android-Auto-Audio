# Yasin Android Auto Audio

Kotlin ile yazılmış Android medya uygulaması. Paket adı: `com.yasincarplay.audio`.

## Özellikler

| Özellik | Durum |
| --- | --- |
| İnternet radyosu (Radio Browser API, favoriler, arama) | Kodlandı |
| Podcast (iTunes arama, RSS bölümleri, abonelik) | Kodlandı |
| YouTube Music | Resmi YouTube Music uygulamasını (yoksa tarayıcıyı) aramayla açar. Ses bu uygulamada çalınmaz. |
| Android Auto medya servisi (`MediaBrowserServiceCompat` + `MediaSessionCompat`) | Kodlandı |
| Telefon ana ekranı ve başlatıcı simgesi | Kodlandı |
| Bildirim / ön plan servisi (MediaStyle) | Kodlandı |

### YouTube Music hakkında

YouTube Music, ses akışını üçüncü taraf uygulamalara açmaz ve YouTube kullanım koşulları akışın
uygulama içinde çalınmasına izin vermez. Bu yüzden entegrasyon bir başlatıcıdır: **YouTube Music**
sekmesi arama metniyle resmi uygulamayı açar. Arabada YouTube Music dinlemek için YouTube Music'in
kendi Android Auto desteği kullanılmalıdır; bu uygulamanın Android Auto ağacında YouTube Music yoktur.

## Derleme

```
./gradlew testDebugUnitTest assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions (`.github/workflows/build.yml`)
her push'ta aynı komutları çalıştırır ve APK'yı **yasin-android-auto-audio-debug-apk** adlı
artifact olarak yükler (Actions sekmesi → ilgili çalıştırma → Artifacts).

APK, debug anahtarıyla imzalıdır; yalnızca kendi cihazına kurmak içindir, mağaza dağıtımı için değildir.

## Android Auto ile deneme

Mağaza dışı (APK ile kurulan) medya uygulamalarını Android Auto yalnızca geliştirici modunda gösterir:

1. Telefonda Android Auto ayarlarını aç, "Sürüm" satırına art arda dokunarak geliştirici modunu etkinleştir.
2. Sağ üst menü → Geliştirici ayarları → **Bilinmeyen kaynaklar**'ı aç.
3. Telefonu araca (veya Desktop Head Unit emülatörüne) bağla; uygulama medya listesinde görünür.

## Test durumu (dürüst özet)

- Birim testleri: yalnızca `PodcastRepository.parseDurationMs` için (CI'da çalışır).
- Gerçek cihazda, gerçek bir araçta veya Desktop Head Unit'te **test edilmedi**: radyo/podcast oynatma,
  Android Auto'da görünme ve gezinme, bildirim kontrolleri, YouTube Music açma.
- Radio Browser sunucu adresleri ve iTunes API yanıt biçimi koddan beklenen şekilde yazıldı, canlı olarak doğrulanmadı.

## Yapı

```
app/src/main/java/com/yasincarplay/audio/
  data/     Radyo ve podcast depoları, favori/abonelik saklama
  service/  AudioService (MediaBrowserService + MediaSession + ExoPlayer), MediaIds
  ui/       MainActivity ve liste adaptörü
```
