package com.dk.zopf.runtime

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

        for (operator in OPERATORS) {
            val at = text.indexOf(operator)
            if (at < 0) continue
            val left = interpolate(unquote(text.substring(0, at)))
            val right = interpolate(unquote(text.substring(at + operator.length)))
            val taken = if (operator == "!=") left != right else left == right
            return BranchVerdict(taken, "\"$left\" $operator \"$right\" → $taken")
        }

        val resolved = interpolate(text)
        val taken = resolved.lowercase() !in FALSEY
        return BranchVerdict(taken, "\"$resolved\" is ${if (taken) "set" else "empty or false"} → $taken")
    }

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        val quoted =
            trimmed.length >= 2 &&
                (trimmed.startsWith('"') && trimmed.endsWith('"') || trimmed.startsWith('\'') && trimmed.endsWith('\''))
        return if (quoted) trimmed.substring(1, trimmed.length - 1) else trimmed
    }
}
