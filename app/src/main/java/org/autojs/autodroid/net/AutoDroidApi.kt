package org.autojs.autodroid.net

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// 数据模型（仅覆盖当前页面所需字段，缺省字段由 Gson 置空）
// ---------------------------------------------------------------------------

data class LoginResponse(val access_token: String? = null, val token_type: String? = null)

data class CaseItem(
    val id: Int,
    val name: String,
    val description: String? = null,
    val folder_id: Int? = null,
    val last_run_status: String? = null,
    val last_run_time: String? = null,
)

data class PaginatedCases(val total: Int = 0, val items: List<CaseItem> = emptyList())

data class FolderNode(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val is_leaf: Boolean? = null,
    val children: List<FolderNode>? = null,
)

data class FolderTreeResponse(val tree: List<FolderNode> = emptyList(), val all_cases: List<CaseItem> = emptyList())

data class DeviceRegisterResponse(val device_serial: String? = null)

data class ExecutionItem(
    val id: Int,
    val scenario_name: String? = null,
    val status: String? = null,
    val device_serial: String? = null,
    val device_info: String? = null,
    val batch_name: String? = null,
    val start_time: String? = null,
    val duration: Double? = null,
)

data class PaginatedExecutions(val total: Int = 0, val items: List<ExecutionItem> = emptyList())

data class ScriptFileEntry(val path: String = "", val content: String = "")

/** 平台下发的可执行脚本包（与 zip 导出同源）。 */
data class ScriptBundle(
    val format_version: Int = 1,
    val generator: String? = null,
    val kind: String? = null,
    val manifest: com.google.gson.JsonObject? = null,
    val runtime_js: String? = null,
    val main_js: String? = null,
    val case_files: List<ScriptFileEntry> = emptyList(),
    val assets: Map<String, String> = emptyMap(),
)

// ---------------------------------------------------------------------------
// Retrofit 服务（路径基于根前缀；平台同时提供 /api/* 别名，App 使用无前缀路径）
// ---------------------------------------------------------------------------

interface ApiService {

    @FormUrlEncoded
    @POST("auth/token")
    fun login(
        @Field("username") username: String,
        @Field("password") password: String,
    ): Call<LoginResponse>

    @POST("device-runs/register")
    fun registerDevice(@Body payload: Map<String, Any?>): Call<DeviceRegisterResponse>

    @GET("folders/tree")
    fun folderTree(): Call<FolderTreeResponse>

    @GET("cases/")
    fun listCases(
        @Query("skip") skip: Int = 0,
        @Query("limit") limit: Int = 200,
        @Query("keyword") keyword: String? = null,
        @Query("folder_id") folderId: Int? = null,
    ): Call<PaginatedCases>

    @GET("reports/executions")
    fun listExecutions(
        @Query("skip") skip: Int = 0,
        @Query("limit") limit: Int = 50,
    ): Call<PaginatedExecutions>

    @GET("reports/executions/{id}/download")
    fun downloadReport(@Path("id") executionId: Long): Call<ResponseBody>

    @GET("app-runs/cases/{id}/script")
    fun caseScript(
        @Path("id") caseId: Int,
        @Query("env_id") envId: Int? = null,
    ): Call<ScriptBundle>
}

// ---------------------------------------------------------------------------
// 单例入口：按服务器地址重建 Retrofit；请求自动附带登录态
// ---------------------------------------------------------------------------

object AutoDroidApi {

    @Volatile
    private var retrofit: Retrofit? = null

    @Volatile
    private var currentBaseUrl: String = ""

    fun service(serverUrl: String, tokenProvider: () -> String?): ApiService {
        val normalized = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        synchronized(this) {
            if (retrofit == null || normalized != currentBaseUrl) {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val builder = chain.request().newBuilder()
                        tokenProvider()?.takeIf { it.isNotBlank() }?.let {
                            builder.header("Authorization", "Bearer $it")
                        }
                        chain.proceed(builder.build())
                    }
                    .build()
                retrofit = Retrofit.Builder()
                    .baseUrl(normalized)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                currentBaseUrl = normalized
            }
            return retrofit!!.create(ApiService::class.java)
        }
    }
}
