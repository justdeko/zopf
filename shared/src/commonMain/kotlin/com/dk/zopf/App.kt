package com.dk.zopf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.dk.zopf.ui.AppState
import com.dk.zopf.ui.ZopfApp

@Composable
fun App(state: AppState = remember { AppState() }) {
    ZopfApp(state)
}
