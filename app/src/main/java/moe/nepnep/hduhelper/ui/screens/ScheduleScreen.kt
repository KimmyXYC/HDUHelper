package moe.nepnep.hduhelper.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.nepnep.hduhelper.R
import moe.nepnep.hduhelper.ui.components.PlaceholderPage
import moe.nepnep.hduhelper.ui.navigation.AppDestination

@Composable
fun ScheduleScreen(modifier: Modifier = Modifier) {
    PlaceholderPage(
        icon = AppDestination.SCHEDULE.icon,
        titleRes = R.string.schedule_title,
        descriptionRes = R.string.schedule_description,
        modifier = modifier,
    )
}
