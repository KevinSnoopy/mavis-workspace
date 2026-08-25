package com.eareyereading.di

import com.eareyereading.data.repository.*
import com.eareyereading.domain.repository.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindBookRepository(impl: BookRepositoryImpl): BookRepository

    @Binds
    @Singleton
    abstract fun bindVocabularyRepository(impl: VocabularyRepositoryImpl): VocabularyRepository

    @Binds
    @Singleton
    abstract fun bindReadingRepository(impl: ReadingRepositoryImpl): ReadingRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
