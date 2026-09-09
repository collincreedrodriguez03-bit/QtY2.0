package com.example.data

import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class BinanceTickerResponse(
    val symbol: String,
    val price: String
)

interface BinanceApi {
    @GET("api/v3/ticker/price")
    suspend fun getBtcPrice(@Query("symbol") symbol: String = "BTCUSDT"): BinanceTickerResponse
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
