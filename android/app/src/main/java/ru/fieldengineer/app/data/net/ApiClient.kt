package ru.fieldengineer.app.data.net

import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Сервер отклонил токен обновления (сотрудник заблокирован или токен истёк): нужен вход по паролю. */
class SessionExpiredException : IOException("Сессия завершена сервером")

/**
 * Клиент API «Сервисного пульта»: OkHttp (соединение, токен, повтор после обновления токена)
 * + Retrofit (методы ApiService, JSON через kotlinx.serialization).
 * [onServerTime] получает время сервера из ответа на обновление токена — для доверенного времени.
 */
class ApiClient(
    baseUrl: String,
    private val tokens: TokenStore,
    private val onServerTime: (Instant) -> Unit,
) {
    private val converter = ApiJson.asConverterFactory("application/json".toMediaType())

    // «голый» клиент без токена — для обновления токена, чтобы не зациклиться на 401
    private val plainClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)   // загрузка фото на медленной мобильной сети
        .apply { debugInterceptors().forEach(::addInterceptor) }
        .build()

    private val refreshService: RefreshService = Retrofit.Builder()
        .baseUrl(baseUrl).client(plainClient).addConverterFactory(converter).build()
        .create(RefreshService::class.java)

    private val client = plainClient.newBuilder()
        .addInterceptor(AccessTokenInterceptor())
        .authenticator(TokenAuthenticator())
        .build()

    val api: ApiService = Retrofit.Builder()
        .baseUrl(baseUrl).client(client).addConverterFactory(converter).build()
        .create(ApiService::class.java)

    /** Добавляет к запросу заголовок Authorization: Bearer <токен доступа>. */
    private inner class AccessTokenInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val token = tokens.accessToken
            if (token == null || isAuthCall(request)) return chain.proceed(request)
            return chain.proceed(request.withToken(token))
        }
    }

    /**
     * OkHttp вызывает его, когда сервер ответил 401: токен доступа (30 мин) истёк. Обновляем пару
     * токенов и повторяем запрос один раз. Если сервер отказал в обновлении — сессия завершена.
     */
    private inner class TokenAuthenticator : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            // 401 на вход — это неверный пароль, а повторный 401 после обновления — не повторяем
            if (isAuthCall(response.request) || response.priorResponse != null) return null
            synchronized(this) {
                val sent = response.request.header(HEADER)?.removePrefix(BEARER)
                val current = tokens.accessToken ?: throw SessionExpiredException()
                // пока ждали, другой запрос уже обновил токен — просто повторяем с новым
                if (current != sent) return response.request.withToken(current)

                val refreshToken = tokens.refreshToken ?: throw SessionExpiredException()
                val answer = try {
                    refreshService.refresh(RefreshBody(refreshToken)).execute()
                } catch (e: Exception) {
                    // нет сети — IOException уходит дальше, обмен повторится; любой другой сбой
                    // (например, прокси вернул не JSON) — как сбой сервера: исходный 401, а не падение
                    if (e is IOException) throw e
                    return null
                }
                val body = answer.body()
                if (answer.code() == 400 || answer.code() == 401 || answer.code() == 403) {
                    // отклонён прежний токен, а инженер уже вошёл заново — новый сеанс не трогаем
                    if (!tokens.clearIfCurrent(refreshToken)) return null
                    throw SessionExpiredException()
                }
                if (!answer.isSuccessful || body == null) return null   // сбой сервера — вернётся исходный 401
                // пока шёл запрос, сеанс могли заблокировать (токены удалены) — тогда новые не сохраняем
                if (!tokens.saveIfCurrent(refreshToken, body.accessToken, body.refreshToken)) return null
                onServerTime(body.serverTime)
                return response.request.withToken(body.accessToken)
            }
        }
    }

    private fun isAuthCall(request: Request) = request.url.encodedPath.startsWith("/api/auth/")

    private fun Request.withToken(token: String) = newBuilder().header(HEADER, BEARER + token).build()

    private companion object {
        const val HEADER = "Authorization"
        const val BEARER = "Bearer "
    }
}
