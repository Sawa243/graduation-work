package ru.fieldengineer.app.data.net

import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

/** Методы API «Сервисного пульта» (проект системы, раздел 10). Retrofit сам делает из них запросы. */
interface ApiService {
    @POST("api/auth/login")
    suspend fun login(@Body body: LoginBody): LoginResponse

    /** Изменения с момента [since] (время прошлого pull); null — полная выгрузка. */
    @GET("api/sync/pull")
    suspend fun pull(@Query("since") since: String?): PullResponse

    @POST("api/sync/push")
    suspend fun push(@Body body: PushBody): PushResponse

    /** Загрузка файла: [kind] — photo, signature или act; повтор того же файла — не ошибка. */
    @Multipart
    @POST("api/files")
    suspend fun uploadFile(
        @Query("kind") kind: String,
        @Query("name") name: String,
        @Part file: MultipartBody.Part,
    ): FileUploadResponse
}

/**
 * Обновление токена — отдельно и синхронным вызовом: его делает TokenAuthenticator
 * внутри OkHttp, когда сервер ответил 401 на обычный запрос.
 */
interface RefreshService {
    @POST("api/auth/refresh")
    fun refresh(@Body body: RefreshBody): Call<TokenResponse>
}
