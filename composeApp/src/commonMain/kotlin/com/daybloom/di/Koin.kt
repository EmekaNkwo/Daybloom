package com.daybloom.di

import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration

/** Starts Koin once per process. Android calls this from Application.onCreate, iOS from the app initializer. */
fun initKoin(config: KoinAppDeclaration? = null): KoinApplication = startKoin {
    config?.invoke(this)
    modules(sharedModules())
}
