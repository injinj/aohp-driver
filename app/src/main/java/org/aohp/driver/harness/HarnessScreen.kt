package org.aohp.driver.harness

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.aohp.driver.Tab
import org.aohp.driver.ui.CenteredMessage

@Composable
fun HarnessScreen(modifier: Modifier = Modifier, onGoToTab: (Tab) -> Unit) { CenteredMessage("Harness (todo)", modifier) }
