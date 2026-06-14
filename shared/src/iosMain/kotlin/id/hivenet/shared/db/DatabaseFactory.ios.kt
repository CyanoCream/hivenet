package id.hivenet.shared.db

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import id.hivenet.db.HiveNetDatabase

actual fun createHiveNetDatabase(): HiveNetDatabase {
    val driver = NativeSqliteDriver(HiveNetDatabase.Schema, "hivenet.db")
    return HiveNetDatabase(driver)
}
