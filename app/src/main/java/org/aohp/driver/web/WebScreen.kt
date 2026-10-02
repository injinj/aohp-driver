package org.aohp.driver.web

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.aohp.driver.Tab
import org.aohp.driver.ui.CenteredMessage

@Composable
fun WebScreen(modifier: Modifier = Modifier, onGoToTab: (Tab) -> Unit) { CenteredMessage("Web (todo)", modifier) }
