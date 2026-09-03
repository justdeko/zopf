package com.dk.zopf.cli

private const val BRAID_ORANGE = "\u001B[38;5;166m"

private const val RESET = "\u001B[0m"

private val BRAID =
    listOf(
        "██▄▄       ▄▄██",
        "▀█████▄▄▄█████▀",
        "  ▄█████████▄",
        "▄████▀▀▀▀▀████▄",
        "███▄       ▄███",
        "▀█████▄▄▄█████▀",
        "  ▄█████████▄",
        "▄████▀▀▀▀▀████▄",
        "███▄       ▄███",
        "▀█████▄▄▄█████▀",
        "  ▄█████████▄",
        "▄████▀▀▀▀▀████▄",
        "███▄▄     ▄▄███",
        " ▀████▄ ▄████▀",
        "    ▀█████▀",
        "      ▀▀▀",
    )

fun braid(colour: Boolean): String =
    BRAID.joinToString("\n") { strand ->
        if (colour) "  $BRAID_ORANGE$strand$RESET" else "  $strand"
    }
