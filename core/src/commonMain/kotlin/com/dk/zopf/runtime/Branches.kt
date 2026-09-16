package com.dk.zopf.runtime

import com.dk.zopf.util.Strings

data class BranchVerdict(
    val taken: Boolean,
    val explanation: String,
)

object Branches {
    private val FALSEY = setOf("", "false", "0", "no", "off", "null")
    private val OPERATORS = listOf("!=", "==")

    fun evaluate(
        expression: String,
        interpolate: (String) -> String = { it },
    ): BranchVerdict {
        val text = expression.trim()

        operatorOutsideQuotes(text)?.let { (at, operator) ->
            val left = interpolate(unquote(text.substring(0, at)))
            val right = interpolate(unquote(text.substring(at + operator.length)))
            val taken = if (operator == "!=") left != right else left == right
            return BranchVerdict(taken, Strings.Transcript.branchCompared(left, operator, right, taken))
        }

        val resolved = interpolate(text)
        val taken = resolved.lowercase() !in FALSEY
        return BranchVerdict(taken, Strings.Transcript.branchTruthy(resolved, taken))
    }

    private fun operatorOutsideQuotes(text: String): Pair<Int, String>? {
        var quote: Char? = null
        text.forEachIndexed { at, char ->
            when {
                quote != null -> if (char == quote) quote = null
                char == '"' || char == '\'' -> quote = char
                else -> OPERATORS.firstOrNull { text.startsWith(it, at) }?.let { return at to it }
            }
        }
        return null
    }

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        val quoted =
            trimmed.length >= 2 &&
                (trimmed.startsWith('"') && trimmed.endsWith('"') || trimmed.startsWith('\'') && trimmed.endsWith('\''))
        return if (quoted) trimmed.substring(1, trimmed.length - 1) else trimmed
    }
}
