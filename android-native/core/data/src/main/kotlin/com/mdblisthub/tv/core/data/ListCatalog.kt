package com.mdblisthub.tv.core.data

import com.mdblisthub.tv.core.network.dto.MdbListDto
import java.text.Collator
import java.util.Locale

/**
 * The curated set of lists shown **to the owner**, keyed by the exact mdblist
 * name (lowercased) and mapped to a Portuguese label. Lists outside this map
 * are hidden and rows are ordered by the translated label.
 *
 * Every other account sees all of its own lists instead, untouched — the
 * curation is the owner's home, not a rule imposed on visitors.
 */
object ListCatalog {

    private val CATALOG: Map<String, String> = mapOf(
        "ação e aventura" to "Ação e Aventura",
        "animation" to "Animação",
        "combina com você" to "Combina com Você",
        "trending movies" to "Em Alta",
        "fantasia" to "Fantasia",
        "science fiction" to "Ficção Científica",
        "can't go wrong movies" to "Filmes Que Não Têm Erro",
        "surprise me" to "Me Surpreenda",
        "best of super heroe" to "O Melhor dos Super-Heróis",
        "best ever" to "Os Melhores de Todos os Tempos",
        "series can't go wrong" to "Séries Que Não Têm Erro",
        "supernatural" to "Sobrenatural",
        "suspense" to "Suspense",
        "horror" to "Terror",
        "lastest movie releases" to "Últimos Lançamentos",
        "zombies and outbreak" to "Zumbis e Epidemias",
    )

    private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("pt-BR")).apply {
        strength = Collator.PRIMARY
    }

    /** The display name for a list, and whether it belongs on a curated home. */
    fun displayName(originalName: String): String? = CATALOG[key(originalName)]

    /**
     * Orders and names the lists for whoever signed in. Empty lists are
     * dropped in both cases: a row that paints nothing is worse than no row.
     */
    fun arrange(lists: List<MdbListDto>, isOwner: Boolean): List<Pair<MdbListDto, String>> {
        val withNames = lists
            .filter { it.items > 0 }
            .mapNotNull { list ->
                val curated = displayName(list.name)
                when {
                    !isOwner -> list to list.name
                    curated != null -> list to curated
                    else -> null
                }
            }

        return withNames.sortedWith(compareBy(collator) { it.second })
    }

    private fun key(name: String): String = name.trim().lowercase()
}
