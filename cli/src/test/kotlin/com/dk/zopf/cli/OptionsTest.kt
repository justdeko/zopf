package com.dk.zopf.cli

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OptionsTest {
    private val known = setOf("workspace", "answer", "concurrency", "on-gate")

    @Test
    fun `a flag takes its value either way round`() {
        val spaced = Options.parse(listOf("demo", "--workspace", "/tmp/ws"), known)
        val joined = Options.parse(listOf("demo", "--workspace=/tmp/ws"), known)

        assertEquals("/tmp/ws", spaced.one("workspace"))
        assertEquals("/tmp/ws", joined.one("workspace"))
        assertEquals(listOf("demo"), spaced.positionals)
    }

    @Test
    fun `a repeatable flag keeps every occurrence`() {
        val options = Options.parse(listOf("--answer", "a=1", "--answer", "b=2"), known)

        assertEquals(mapOf("a" to "1", "b" to "2"), options.pairs("answer"))
    }

    @Test
    fun `a value with an equals sign in it survives`() {
        val options = Options.parse(listOf("--answer", "msg=a=b"), known)

        assertEquals(mapOf("msg" to "a=b"), options.pairs("answer"))
    }

    @Test
    fun `a misspelled flag is refused rather than ignored`() {
        val failure = assertFailsWith<UsageError> { Options.parse(listOf("--workspce", "/tmp"), known) }

        assertContains(failure.message!!, "--workspce")
    }

    @Test
    fun `a flag with nothing after it is refused`() {
        assertFailsWith<UsageError> { Options.parse(listOf("--workspace"), known) }
    }

    @Test
    fun `a number outside what the setting allows is refused where it was typed`() {
        val options = Options.parse(listOf("--concurrency", "99"), known)

        assertFailsWith<UsageError> { options.int("concurrency", 1..8) }
        assertFailsWith<UsageError> { Options.parse(listOf("--concurrency", "two"), known).int("concurrency", 1..8) }
        assertNull(Options.parse(emptyList(), known).int("concurrency", 1..8))
    }

    @Test
    fun `a policy is matched however it was cased, and anything else is refused`() {
        assertEquals(GatePolicy.APPROVE, Options.parse(listOf("--on-gate", "Approve"), known).choice("on-gate", GatePolicy.entries.toTypedArray()))

        val failure =
            assertFailsWith<UsageError> {
                Options.parse(listOf("--on-gate", "maybe"), known).choice("on-gate", GatePolicy.entries.toTypedArray())
            }
        assertContains(failure.message!!, "approve|reject|fail")
    }

    @Test
    fun `a pair without a name is refused`() {
        assertFailsWith<UsageError> { Options.parse(listOf("--answer", "justavalue"), known).pairs("answer") }
        assertFailsWith<UsageError> { Options.parse(listOf("--answer", "=value"), known).pairs("answer") }
    }

    @Test
    fun `a flag whose value is another flag is the user forgetting one`() {
        val failure =
            assertFailsWith<UsageError> {
                Options.parse(listOf("demo", "--answer", "--workspace", "/tmp"), known)
            }

        assertContains(failure.message!!, "--answer needs a value")
    }

    @Test
    fun `a value that merely looks like a flag is still a value`() {
        val options = Options.parse(listOf("--answer", "note=--not-an-option"), known)

        assertEquals(mapOf("note" to "--not-an-option"), options.pairs("answer"))
    }

    @Test
    fun `a switch takes no value and reads as present`() {
        val options = Options.parse(listOf("demo", "--dry-run"), known, setOf("dry-run"))

        assertEquals(true, options.has("dry-run"))
        assertEquals(listOf("demo"), options.positionals)
        assertEquals(false, Options.parse(listOf("demo"), known, setOf("dry-run")).has("dry-run"))
    }
}
