package moe.nepnep.hduhelper.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import moe.nepnep.hduhelper.R
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Weeks

enum class AppDestination(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    SCHEDULE(R.string.nav_schedule, MiuixIcons.Tasks),
    TIMETABLE(R.string.nav_timetable, MiuixIcons.Weeks),
    CAMPUS_CODE(R.string.nav_campus_code, MiuixIcons.Scan),
    APPLICATIONS(R.string.nav_applications, MiuixIcons.GridView),
    PROFILE(R.string.nav_profile, MiuixIcons.Contacts),
}
