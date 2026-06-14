package id.hivenet.shared.db

import android.content.Context
import id.hivenet.db.HiveNetDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual fun createHiveNetDatabase(): HiveNetDatabase {
    error("Android database requires a Context. Use createHiveNetDatabase(context) from the Android app module.")
}

fun createHiveNetDatabase(context: Context): HiveNetDatabase {
    val driver = AndroidSqliteDriver(HiveNetDatabase.Schema, context, "hivenet.db")
    return HiveNetDatabase(driver)
}
