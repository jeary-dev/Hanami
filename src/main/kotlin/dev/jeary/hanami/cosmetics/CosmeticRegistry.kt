package dev.jeary.hanami.cosmetics

import dev.jeary.hanami.api.cosmetic.CosmeticId
import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.math.shape.Shape

/**
 * An immutable snapshot of every loaded cosmetic. A reload builds a brand new registry and swaps
 * it in atomically, so the compute thread never sees a half-loaded state.
 */
class CosmeticRegistry(definitions: Collection<CosmeticDefinition>) {

    private val byId: Map<CosmeticId, CosmeticDefinition> = definitions.associateBy { it.id }

    private val byType: Map<CosmeticType, List<CosmeticDefinition>> = CosmeticType.entries.associateWith { type ->
        definitions.filter { it.type == type }.sortedWith(compareBy({ it.order }, { it.id }))
    }

    val all: List<CosmeticDefinition> = CosmeticType.entries.flatMap { byType.getValue(it) }

    val size: Int get() = byId.size

    operator fun get(id: CosmeticId): CosmeticDefinition? = byId[id]

    fun ofType(type: CosmeticType): List<CosmeticDefinition> = byType.getValue(type)

    /** Every shape referenced by any layer, for pruning the shape cache after a reload. */
    fun shapes(): Set<Shape> = all.flatMapTo(HashSet()) { def -> def.layers.map { it.shape } }

    companion object {
        val EMPTY = CosmeticRegistry(emptyList())
    }
}
