package moe.nepnep.hduhelper.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.nepnep.hduhelper.R
import moe.nepnep.hduhelper.ui.components.PlaceholderPage
import moe.nepnep.hduhelper.ui.navigation.AppDestination

@Composable
fun CampusCodeScreen(modifier: Modifier = Modifier) {
    PlaceholderPage(
        icon = AppDestination.CAMPUS_CODE.icon,
        titleRes = R.string.campus_code_title,
        descriptionRes = R.string.campus_code_description,
        modifier = modifier,
    )
}
