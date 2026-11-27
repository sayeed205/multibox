package com.hitarashi.multibox.core.telegram.di

import com.hitarashi.multibox.core.model.telegram.TelegramAuthGateway
import com.hitarashi.multibox.core.telegram.TdlibAuthGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TelegramModule {

    @Binds
    @Singleton
    abstract fun bindTelegramAuthGateway(impl: TdlibAuthGateway): TelegramAuthGateway
}
