package id.kampungnet.shared.db

import android.content.Context
import id.kampungnet.db.KampungNetDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual fun createKampungNetDatabase(): KampungNetDatabase {
    error("Android database requires a Context. Use createKampungNetDatabase(context) from the Android app module.")
}

fun createKampungNetDatabase(context: Context): KampungNetDatabase {
    val driver = AndroidSqliteDriver(KampungNetDatabase.Schema, context, "kampungnet.db")
    return KampungNetDatabase(driver)
}
