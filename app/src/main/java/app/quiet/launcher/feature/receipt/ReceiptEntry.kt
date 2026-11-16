package app.quiet.launcher.feature.receipt

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.designsystem.pressable

/** "Print today's receipt" row for the Screen time page. */
@Composable
fun ReceiptEntry(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Box(
        modifier.fillMaxWidth().heightIn(min = 48.dp).pressable { ReceiptActivity.open(ctx) },
        contentAlignment = Alignment.CenterStart,
    ) { BasicText("Print today's receipt", style = QType.body().copy(color = Q.colors.ink), modifier = Modifier.padding(vertical = 8.dp)) }
}
