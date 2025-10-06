package com.example.cosc3001

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth // use Auth plugin object
import io.github.jan.supabase.gotrue.auth // extension property
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage

/**
 * Centralized singleton access to the Supabase client.
 * Installs Auth (was GoTrue) for authentication.
 */
object SupabaseProvider {
    val client: SupabaseClient by lazy {
        val url = BuildConfig.SUPABASE_URL
        val key = BuildConfig.SUPABASE_ANON_KEY
        require(url.isNotBlank() && key.isNotBlank()) {
            "Supabase not configured. Set SUPABASE_URL and SUPABASE_ANON_KEY in ~/.gradle/gradle.properties or environment variables."
        }
        createSupabaseClient(supabaseUrl = url, supabaseKey = key) {
            install(Auth)
            install(Postgrest)
            install(Storage)
        }
    }
}
