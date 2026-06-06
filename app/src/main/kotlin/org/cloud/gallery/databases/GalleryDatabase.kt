package org.fossify.gallery.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.fossify.gallery.interfaces.*
import org.fossify.gallery.models.*

@Database(entities = [Directory::class, Medium::class, Widget::class, DateTaken::class, Favorite::class], version = 11)
abstract class GalleryDatabase : RoomDatabase() {

    abstract fun DirectoryDao(): DirectoryDao

    abstract fun MediumDao(): MediumDao

    abstract fun WidgetsDao(): WidgetsDao

    abstract fun DateTakensDao(): DateTakensDao

    abstract fun FavoritesDao(): FavoritesDao

    companion object {
        private var db: GalleryDatabase? = null

        fun getInstance(context: Context): GalleryDatabase {
            if (db == null) {
                synchronized(GalleryDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, GalleryDatabase::class.java, "gallery.db")
                            .fallbackToDestructiveMigration()
                            .addMigrations(MIGRATION_4_5)
                            .addMigrations(MIGRATION_5_6)
                            .addMigrations(MIGRATION_6_7)
                            .addMigrations(MIGRATION_7_8)
                            .addMigrations(MIGRATION_8_9)
                            .addMigrations(MIGRATION_9_10)
                            .addMigrations(MIGRATION_10_11)
                            .build()
                    }
                }
            }
            return db!!
        }

        fun destroyInstance() {
            if (db?.isOpen == true) {
                db?.close()
            }
            db = null
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE media ADD COLUMN video_duration INTEGER default 0 NOT NULL")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `widgets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `widget_id` INTEGER NOT NULL, `folder_path` TEXT NOT NULL)")
                database.execSQL("CREATE UNIQUE INDEX `index_widgets_widget_id` ON `widgets` (`widget_id`)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `date_takens` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `full_path` TEXT NOT NULL, `filename` TEXT NOT NULL, `parent_path` TEXT NOT NULL, `date_taken` INTEGER NOT NULL, `last_fixed` INTEGER NOT NULL)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `favorites` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `full_path` TEXT NOT NULL, `filename` TEXT NOT NULL, `parent_path` TEXT NOT NULL)")

                database.execSQL("CREATE UNIQUE INDEX `index_date_takens_full_path` ON `date_takens` (`full_path`)")
                database.execSQL("CREATE UNIQUE INDEX `index_favorites_full_path` ON `favorites` (`full_path`)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE directories ADD COLUMN sort_value TEXT default '' NOT NULL")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE date_takens ADD COLUMN last_modified INTEGER default 0 NOT NULL")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE media ADD COLUMN media_store_id INTEGER default 0 NOT NULL")
            }
        }

        /**
         * Migration 10→11: Deduplicate rows caused by case-insensitive emulated storage paths.
         * Android emulated storage treats /WeiXin/ and /weixin/ as the same folder, but SQLite's
         * default UNIQUE index is case-sensitive, causing duplicate rows. This migration deletes
         * duplicate rows keeping the one with the lowest id per lowercase path.
         *
         * Note: We cannot change the index to COLLATE NOCASE because Room's @Entity annotation
         * doesn't support it. Case-insensitive deduplication is handled in code (MediaActivity.gotMedia).
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Deduplicate media: delete rows whose lowercase path duplicates a lower-id row
                database.execSQL("""
                    DELETE FROM media WHERE id NOT IN (
                        SELECT MIN(id) FROM media GROUP BY LOWER(full_path)
                    )
                """)

                // Deduplicate directories
                database.execSQL("""
                    DELETE FROM directories WHERE id NOT IN (
                        SELECT MIN(id) FROM directories GROUP BY LOWER(path)
                    )
                """)

                // Deduplicate favorites
                database.execSQL("""
                    DELETE FROM favorites WHERE id NOT IN (
                        SELECT MIN(id) FROM favorites GROUP BY LOWER(full_path)
                    )
                """)

                // Deduplicate date_takens
                database.execSQL("""
                    DELETE FROM date_takens WHERE id NOT IN (
                        SELECT MIN(id) FROM date_takens GROUP BY LOWER(full_path)
                    )
                """)
            }
        }
    }
}
