package moe.nepnep.hduhelper.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.nepnep.hduhelper.R
import moe.nepnep.hduhelper.ui.components.PlaceholderPage
import moe.nepnep.hduhelper.ui.navigation.AppDestination

@Composable
fun ApplicationsScreen(modifier: Modifier = Modifier) {
    PlaceholderPage(
        icon = AppDestination.APPLICATIONS.icon,
        titleRes = R.string.applications_title,
        descriptionRes = R.string.applications_description,
        modifier = modifier,
    )
}
