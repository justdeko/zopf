package com.dk.zopf.cli

class UsageError(
    message: String,
) : IllegalArgumentException(message)

class Options(
    val positionals: List<String>,
    private val flags: Map<String, List<String>>,
) {
    fun all(name: String): List<String> = flags[name].orEmpty()

    fun one(name: String): String? = all(name).lastOrNull()

    fun int(
        name: String,
        range: IntRange,
    ): Int? {
        val raw = one(name) ?: return null
        val value = raw.toIntOrNull() ?: throw UsageError("--$name takes a number, not \"$raw\"")
        if (value !in range) {
            throw UsageError("--$name has to be between ${range.first} and ${range.last}")
        }
        return value
    }

    fun <T : Enum<T>> choice(
        name: String,
        values: Array<T>,
    ): T? {
        val raw = one(name) ?: return null
        return values.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw UsageError("--$name takes ${values.joinToString("|") { it.name.lowercase() }}, not \"$raw\"")
    }

    fun pairs(name: String): Map<String, String> =
        all(name).associate { raw ->
            val key = raw.substringBefore('=', "")
            if (key.isBlank() || '=' !in raw) throw UsageError("--$name takes name=value, not \"$raw\"")
            key to raw.substringAfter('=')
        }

    fun has(name: String): Boolean = flags.containsKey(name)

    companion object {
        fun parse(
            args: List<String>,
            known: Set<String>,
            switches: Set<String> = emptySet(),
        ): Options {
            val positionals = mutableListOf<String>()
            val flags = mutableMapOf<String, MutableList<String>>()
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                if (!arg.startsWith("--")) {
                    positionals += arg
                } else {
                    val name = arg.removePrefix("--").substringBefore('=')
                    if (name !in known && name !in switches) {
                        val all = (known + switches).sorted().joinToString { "--$it" }
                        throw UsageError("Unknown option --$name. This command takes $all")
                    }
                    val value =
                        when {
                            name in switches -> {
                                "true"
                            }

                            '=' in arg -> {
                                arg.substringAfter('=')
                            }

                            else -> {
                                index += 1
                                args.getOrNull(index)?.takeUnless { it.isAnotherFlag(known, switches) }
                                    ?: throw UsageError("--$name needs a value")
                            }
                        }
                    flags.getOrPut(name) { mutableListOf() } += value
                }
                index += 1
            }
            return Options(positionals, flags)
        }

        private fun String.isAnotherFlag(
            known: Set<String>,
            switches: Set<String>,
        ): Boolean = startsWith("--") && removePrefix("--").substringBefore('=') in (known + switches)
    }
}
