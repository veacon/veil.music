package com.veil.music.di

import android.content.Context
import com.veil.music.data.store.ScriptStore
import com.veil.music.source.OtherSourceFinder
import com.veil.music.source.SourceManager
import com.veil.music.source.builtin.BuiltInLyricResolver
import com.veil.music.source.builtin.BuiltInResolver
import com.veil.music.source.catalog.Boards
import com.veil.music.source.catalog.CatalogHttp
import com.veil.music.source.catalog.Catalogs
import com.veil.music.source.catalog.HotSearch
import com.veil.music.source.catalog.Songlists
import com.veil.music.source.js.ScriptHttpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 音源层的依赖装配。
 *
 * 这里的 OkHttpClient 是「干净」的：没有 Apple 那套 Authorization / Media-User-Token 拦截器。
 * 音源请求只带浏览器 UA（见 CatalogHttp / ScriptHttpClient），与 lx 的行为一致。
 */
@Module
@InstallIn(SingletonComponent::class)
object SourceModule {

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideCatalogHttp(client: OkHttpClient) = CatalogHttp(client)

    @Provides
    @Singleton
    fun provideScriptHttp(client: OkHttpClient) = ScriptHttpClient(client)

    @Provides
    @Singleton
    fun provideCatalogs(http: CatalogHttp) = Catalogs(http)

    @Provides
    @Singleton
    fun provideSonglists(http: CatalogHttp) = Songlists(http)

    @Provides
    @Singleton
    fun provideBoards(http: CatalogHttp) = Boards(http)

    @Provides
    @Singleton
    fun provideHotSearch(http: CatalogHttp) = HotSearch(http)

    @Provides
    @Singleton
    fun provideBuiltInResolver(client: OkHttpClient) = BuiltInResolver(client)

    @Provides
    @Singleton
    fun provideBuiltInLyrics(http: CatalogHttp) = BuiltInLyricResolver(http)

    @Provides
    @Singleton
    fun provideOtherSourceFinder(catalogs: Catalogs) = OtherSourceFinder(catalogs)

    /** v4 脚本的宿主胶水层，随包提供，脚本本身由用户导入或来自 assets。 */
    @Provides
    @Singleton
    @PreloadScript
    fun providePreloadScript(@ApplicationContext context: Context): String =
        context.assets.open("script/user-api-preload.js").bufferedReader().use { it.readText() }

    @Provides
    @Singleton
    fun provideSourceManager(
        @PreloadScript preload: String,
        http: ScriptHttpClient,
        finder: OtherSourceFinder,
        builtIn: BuiltInResolver,
    ) = SourceManager(preload, http, finder, builtIn)

    @Provides
    @Singleton
    fun provideScriptStore(@ApplicationContext context: Context, manager: SourceManager) =
        ScriptStore(context, manager)
}

@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PreloadScript
