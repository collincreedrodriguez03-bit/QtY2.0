package com.example.data

import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class BinanceTradeResponse(
    val id: Long,
    val price: String,
    val qty: String,
    val time: Long
)

interface BinanceApi {
    @GET("api/v3/trades")
    suspend fun getRawTrades(
        @Query("symbol") symbol: String = "BTCUSDT",
        @Query("limit") limit: Int = 1
    ): ResponseBody
}

object BinanceClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    val api: BinanceApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.binance.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(BinanceApi::class.java)
    }
}
