package com.elyndra.launcher.di

import android.content.Context
import androidx.room.Room
import com.elyndra.launcher.data.LibraryStore
import com.elyndra.launcher.data.db.ElyndraDatabase
import com.elyndra.launcher.data.db.RoomLibraryStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ElyndraDatabase =
        Room.databaseBuilder(context, ElyndraDatabase::class.java, ElyndraDatabase.NAME).build()

    @Provides
    @Singleton
    fun libraryStore(db: ElyndraDatabase, @LegacyLibraryFile legacy: File): LibraryStore = RoomLibraryStore(db, legacy)

    @Provides fun libraryDao(db: ElyndraDatabase) = db.libraryDao()
    @Provides fun emulatorDao(db: ElyndraDatabase) = db.emulatorDao()
    @Provides fun mashaDao(db: ElyndraDatabase) = db.mashaDao()
    @Provides fun smartListDao(db: ElyndraDatabase) = db.smartListDao()
    @Provides fun arcDao(db: ElyndraDatabase) = db.arcDao()
    @Provides fun aiCacheDao(db: ElyndraDatabase) = db.aiCacheDao()
}
