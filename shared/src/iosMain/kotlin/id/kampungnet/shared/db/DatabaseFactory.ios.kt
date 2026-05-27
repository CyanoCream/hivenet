package id.kampungnet.shared.db

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import id.kampungnet.db.KampungNetDatabase

actual fun createKampungNetDatabase(): KampungNetDatabase {
    val driver = NativeSqliteDriver(KampungNetDatabase.Schema, "kampungnet.db")
    return KampungNetDatabase(driver)
}
