package com.yasincarplay.audio.service

/**
 * "Önceki / sonraki" için çalma sırası.
 *
 * Servis, kullanıcıya (telefon uygulaması veya Android Auto) gösterdiği her çalınabilir listeyi
 * bir anahtarla kaydeder ([remember]). Bir öğe çalınınca ([select]) öğenin içinde bulunduğu liste
 * çalma sırası olur; önceki/sonraki bu listenin sırasını izler ve uçlarda başa/sona sarar.
 *
 * Android sınıflarına bağımlı değildir, bu yüzden birim testle sınanır.
 */
class PlayQueue(private val maxLists: Int = 12) {

    /** Anahtar -> çalınabilir öğe kimlikleri. Ekleme sırası = yenilik sırası (en yeni sonda). */
    private val lists = LinkedHashMap<String, List<String>>()

    private var ids: List<String> = emptyList()
    private var index = -1

    /** Sırada birden fazla öğe varsa önceki/sonraki anlamlıdır. */
    val hasMultiple: Boolean get() = ids.size > 1

    val currentId: String? get() = ids.getOrNull(index)

    /** Gösterilen bir listeyi kaydeder. Aynı anahtar tekrar gelirse listeyi yeniler ve en yeni yapar. */
    fun remember(key: String, playableIds: List<String>) {
        lists.remove(key)
        if (playableIds.isEmpty()) return
        lists[key] = playableIds.toList()
        while (lists.size > maxLists) {
            lists.remove(lists.keys.first())
        }
    }

    /**
     * Çalınan öğeyi sıraya yerleştirir.
     * [listKey] verilmişse ve o liste öğeyi içeriyorsa o liste kullanılır (telefon arayüzü hangi listeden
     * çaldığını bildirir). Aksi halde öğeyi içeren en yeni liste seçilir (Android Auto bunu bildirmez).
     * Hiçbir listede yoksa sıra yalnızca bu öğeden oluşur.
     */
    fun select(mediaId: String, listKey: String? = null) {
        val preferred = listKey?.let { lists[it] }?.takeIf { mediaId in it }
        val chosen = preferred ?: lists.values.toList().asReversed().firstOrNull { mediaId in it }
        if (chosen != null) {
            ids = chosen
            index = chosen.indexOf(mediaId)
        } else {
            ids = listOf(mediaId)
            index = 0
        }
    }

    /** Sıradaki öğenin kimliği; tek öğe varsa veya sıra boşsa null. Sonda başa sarar. */
    fun next(): String? {
        if (ids.size < 2) return null
        index = (index + 1) % ids.size
        return ids[index]
    }

    /** Önceki öğenin kimliği; tek öğe varsa veya sıra boşsa null. Başta sona sarar. */
    fun previous(): String? {
        if (ids.size < 2) return null
        index = (index - 1 + ids.size) % ids.size
        return ids[index]
    }

    /** Çalma sırasını boşaltır; kaydedilmiş listeler (gezinme ağacı) korunur. */
    fun clear() {
        ids = emptyList()
        index = -1
    }
}
