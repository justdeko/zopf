package com.dk.zopf.runtime

data class BranchVerdict(
    val taken: Boolean,
    val explanation: String,
)

object Branches {
    private val FALSEY = setOf("", "false", "0", "no", "off", "null")

    fun evaluate(expression: String): BranchVerdict {
        val text = expression.trim()

        operatorSplit(text, "!=")?.let { (left, right) ->
            val taken = left != right
            return BranchVerdict(taken, "\"$left\" != \"$right\" → $taken")
        }
        operatorSplit(text, "==")?.let { (left, right) ->
            val taken = left == right
            return BranchVerdict(taken, "\"$left\" == \"$right\" → $taken")
        }

        val taken = text.lowercase() !in FALSEY
        return BranchVerdict(taken, "\"$text\" is ${if (taken) "set" else "empty or false"} → $taken")
    }

    private fun operatorSplit(
        text: String,
        operator: String,
    ): Pair<String, String>? {
        val at = text.indexOf(operator)
        if (at < 0) return null
        return unquote(text.substring(0, at)) to unquote(text.substring(at + operator.length))
    }

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        val quoted =
            trimmed.length >= 2 &&
                (trimmed.startsWith('"') && trimmed.endsWith('"') || trimmed.startsWith('\'') && trimmed.endsWith('\''))
        return if (quoted) trimmed.substring(1, trimmed.length - 1) else trimmed
    }
}
