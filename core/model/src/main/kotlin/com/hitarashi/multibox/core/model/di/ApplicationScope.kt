package com.hitarashi.multibox.core.model.di

import javax.inject.Qualifier

/**
 * Qualifier for the process-lifetime application coroutine scope shared across core modules.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
