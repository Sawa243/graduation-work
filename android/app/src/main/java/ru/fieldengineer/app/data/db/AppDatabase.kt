package ru.fieldengineer.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.Instant
import java.time.LocalDate

/** Моменты времени хранятся как миллисекунды UTC, даты — строкой «ГГГГ-ММ-ДД». */
class Converters {
    @TypeConverter fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()
    @TypeConverter fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
    @TypeConverter fun dateToString(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun stringToDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
}

@Database(
    entities = [
        CustomerEntity::class, ContractEntity::class, SlaLevelEntity::class, SiteEntity::class,
        EquipmentEntity::class, WorkTypeEntity::class, ChecklistItemEntity::class, EmployeeEntity::class,
        CalendarDayEntity::class, RequestEntity::class, ChecklistResultEntity::class, PhotoEntity::class,
        WorkLogEntity::class, ActEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nsiDao(): NsiDao
    abstract fun requestDao(): RequestDao
    abstract fun workDao(): WorkDao
    abstract fun syncDao(): SyncDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "field_engineer.db").build()
    }
}
