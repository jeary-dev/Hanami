package dev.jeary.hanami.command.parser

import dev.jeary.hanami.api.cosmetic.CosmeticId
import dev.jeary.hanami.api.cosmetic.CosmeticType
import dev.jeary.hanami.cosmetics.CosmeticDefinition
import dev.jeary.hanami.cosmetics.CosmeticRegistry
import org.bukkit.command.CommandSender
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.context.CommandInput
import org.incendo.cloud.parser.ArgumentParseResult
import org.incendo.cloud.parser.ArgumentParser
import org.incendo.cloud.parser.ParserDescriptor
import org.incendo.cloud.suggestion.BlockingSuggestionProvider

/** Thrown (as a parse failure) when the input names no loaded cosmetic. */
class UnknownCosmeticException(val input: String) : IllegalArgumentException("Unknown cosmetic: $input")

/** Thrown when the cosmetic exists but belongs to a different slot than the command's `<type>`. */
class WrongCosmeticTypeException(val cosmetic: CosmeticDefinition, val expected: CosmeticType) :
    IllegalArgumentException("${cosmetic.id} is not a ${expected.id}")

/**
 * Parses a cosmetic id. When the command has a preceding `type` argument, both parsing and tab
 * completion are narrowed to that type — `/hanami equip wings <TAB>` only lists wings.
 */
class CosmeticArgumentParser(
    private val registry: () -> CosmeticRegistry,
    private val typeArgument: String? = null,
) : ArgumentParser<CommandSender, CosmeticDefinition>, BlockingSuggestionProvider.Strings<CommandSender> {

    override fun parse(
        context: CommandContext<CommandSender>,
        input: CommandInput,
    ): ArgumentParseResult<CosmeticDefinition> {
        val raw = input.peekString()
        val cosmetic = CosmeticId.parse(raw)?.let(registry()::get)
            ?: return ArgumentParseResult.failure(UnknownCosmeticException(raw))
        val expected = expectedType(context)
        if (expected != null && cosmetic.type != expected) {
            return ArgumentParseResult.failure(WrongCosmeticTypeException(cosmetic, expected))
        }
        input.readString()
        return ArgumentParseResult.success(cosmetic)
    }

    override fun stringSuggestions(context: CommandContext<CommandSender>, input: CommandInput): Iterable<String> {
        val expected = expectedType(context)
        return registry().all.asSequence()
            .filter { expected == null || it.type == expected }
            .map { it.id.value }
            .toList()
    }

    private fun expectedType(context: CommandContext<CommandSender>): CosmeticType? =
        typeArgument?.let { context.optional<CosmeticType>(it).orElse(null) }

    companion object {
        fun cosmeticParser(registry: () -> CosmeticRegistry, typeArgument: String? = null) =
            ParserDescriptor.of(CosmeticArgumentParser(registry, typeArgument), CosmeticDefinition::class.java)
    }
}

/** Parses a cosmetic type by its id (`trail`, `wings`, ...), case-insensitively. */
class CosmeticTypeParser : ArgumentParser<CommandSender, CosmeticType>, BlockingSuggestionProvider.Strings<CommandSender> {

    override fun parse(context: CommandContext<CommandSender>, input: CommandInput): ArgumentParseResult<CosmeticType> {
        val raw = input.peekString()
        val type = CosmeticType.byId(raw) ?: return ArgumentParseResult.failure(UnknownTypeException(raw))
        input.readString()
        return ArgumentParseResult.success(type)
    }

    override fun stringSuggestions(context: CommandContext<CommandSender>, input: CommandInput): Iterable<String> =
        CosmeticType.entries.map { it.id }

    companion object {
        fun typeParser(): ParserDescriptor<CommandSender, CosmeticType> =
            ParserDescriptor.of(CosmeticTypeParser(), CosmeticType::class.java)
    }
}

class UnknownTypeException(val input: String) : IllegalArgumentException("Unknown cosmetic type: $input")
