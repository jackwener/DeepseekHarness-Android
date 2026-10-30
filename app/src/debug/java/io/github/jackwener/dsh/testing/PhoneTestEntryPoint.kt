package io.github.jackwener.dsh.testing

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jackwener.dsh.core.capability.CapabilityHub
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.data.RuleDao

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PhoneTestEntryPoint {
    fun capabilities(): CapabilityHub
    fun settings(): SettingsRepository
    fun rules(): RuleDao
}
