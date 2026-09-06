package com.dk.zopf.cli

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OptionsTest {
    private val known = setOf("workspace", "answer", "concurrency", "on-gate")

    @Test
    fun `a flag takes its value spaced or with equals`() {
        val spaced = Options.parse(listOf("demo", "--workspace", "/tmp/ws"), known)
        val joined = Options.parse(listOf("demo", "--workspace=/tmp/ws"), known)

        assertEquals("/tmp/ws", spaced.one("workspace"))
        assertEquals("/tmp/ws", joined.one("workspace"))
        assertEquals(listOf("demo"), spaced.positionals)
    }

    @Test
    fun `a pair keeps every occurrence and every value`() {
        listOf(
            listOf("--answer", "a=1", "--answer", "b=2") to mapOf("a" to "1", "b" to "2"),
            listOf("--answer", "msg=a=b") to mapOf("msg" to "a=b"),
            listOf("--answer", "note=--not-an-option") to mapOf("note" to "--not-an-option"),
        ).forEach { (args, expected) ->
            assertEquals(expected, Options.parse(args, known).pairs("answer"), args.toString())
        }
    }

    @Test
    fun `a malformed flag is refused and named`() {
        listOf(
            listOf("--workspce", "/tmp") to "--workspce",
            listOf("--workspace") to "--workspace needs a value",
            listOf("demo", "--answer", "--workspace", "/tmp") to "--answer needs a value",
        ).forEach { (args, said) ->
            val failure = assertFailsWith<UsageError>(args.toString()) { Options.parse(args, known) }
            assertContains(failure.message!!, said, message = args.toString())
        }

        listOf("justavalue", "=value").forEach { pair ->
            assertFailsWith<UsageError>(pair) { Options.parse(listOf("--answer", pair), known).pairs("answer") }
        }
    }

    @Test
    fun `a number outside the allowed range is refused`() {
        val options = Options.parse(listOf("--concurrency", "99"), known)

        assertFailsWith<UsageError> { options.int("concurrency", 1..8) }
        assertFailsWith<UsageError> { Options.parse(listOf("--concurrency", "two"), known).int("concurrency", 1..8) }
        assertNull(Options.parse(emptyList(), known).int("concurrency", 1..8))
    }

    @Test
    fun `a choice matches any case and refuses the rest`() {
        assertEquals(GatePolicy.APPROVE, Options.parse(listOf("--on-gate", "Approve"), known).choice("on-gate", GatePolicy.entries.toTypedArray()))

        val failure =
            assertFailsWith<UsageError> {
                Options.parse(listOf("--on-gate", "maybe"), known).choice("on-gate", GatePolicy.entries.toTypedArray())
            }
        assertContains(failure.message!!, "approve|reject|fail")
    }

    @Test
    fun `a switch takes no value and reads as present`() {
        val options = Options.parse(listOf("demo", "--dry-run"), known, setOf("dry-run"))

        assertEquals(true, options.has("dry-run"))
        assertEquals(listOf("demo"), options.positionals)
        assertEquals(false, Options.parse(listOf("demo"), known, setOf("dry-run")).has("dry-run"))
    }
}
