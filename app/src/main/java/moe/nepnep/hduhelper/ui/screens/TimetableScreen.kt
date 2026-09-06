package moe.nepnep.hduhelper.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.nepnep.hduhelper.R
import moe.nepnep.hduhelper.ui.components.PlaceholderPage
import moe.nepnep.hduhelper.ui.navigation.AppDestination

@Composable
fun TimetableScreen(modifier: Modifier = Modifier) {
    PlaceholderPage(
        icon = AppDestination.TIMETABLE.icon,
        titleRes = R.string.timetable_title,
        descriptionRes = R.string.timetable_description,
        modifier = modifier,
    )
}
