package com.example.location_producer.ble

import android.util.Log
import com.example.location_producer.service.SensorStreamingService
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

data class ZeppDeviceInfo(
    val mac: String,
    val authKey: String,
    val deviceName: String = ""
)

object HuamiCloudClient {

    private const val TAG = "HuamiCloudClient"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"

    private fun log(msg: String) {
        Log.d(TAG, msg)
        SensorStreamingService.addLog("[Zepp Cloud] $msg")
    }

    fun fetchAuthKeys(emailOrPhone: String, password: String): Result<List<ZeppDeviceInfo>> {
        return try {
            log("1/3 Zepp 계정 로그인 시도중: $emailOrPhone")
            val accessToken = loginAccount(emailOrPhone, password)
            log("1/3 로그인 성공! Access Token 획득")

            log("2/3 App Token 발급 시도중...")
            val appToken = getAppToken(accessToken)
            log("2/3 App Token 발급 성공!")

            log("3/3 등록된 기기 목록 조회중...")
            val devices = getUserDevices(appToken)
            log("3/3 총 ${devices.size}개 기기 정보 조회됨")

            if (devices.isEmpty()) {
                Result.failure(Exception("Zepp 계정에 등록된 기기(Mi Band)가 없습니다."))
            } else {
                for (dev in devices) {
                    log("  - 기기: ${dev.deviceName} (${dev.mac}), AuthKey: ${dev.authKey.take(6)}...")
                }
                Result.success(devices)
            }
        } catch (e: Exception) {
            val err = e.message ?: e.toString()
            log("오류 발생: $err")
            Result.failure(e)
        }
    }

    private fun loginAccount(emailOrPhone: String, password: String): String {
        val loginUrl = "https://account.huami.com/v2/client/login"
        val params = mapOf(
            "emailOrPhone" to emailOrPhone,
            "password" to password,
            "state" to "REDIRECTION",
            "client_id" to "HuaMi",
            "redirect_uri" to "https://s3-us-west-2.amazonaws.com/hm-registration/successsignin.html",
            "region" to "us-west-2",
            "token" to "access",
            "country_code" to "US"
        )

        val postData = buildFormUrlEncoded(params) + "&token=refresh"
        val url = URL(loginUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 10000
            readTimeout = 10000
        }

        conn.outputStream.use { os ->
            os.write(postData.toByteArray(Charsets.UTF_8))
        }

        val responseCode = conn.responseCode
        val redirectUrl = conn.getHeaderField("Location") ?: ""

        val responseText = if (responseCode in 200..302) {
            conn.inputStream?.use { readStream(it) } ?: ""
        } else {
            conn.errorStream?.use { readStream(it) } ?: ""
        }

        log("로그인 응답 코드: $responseCode")

        var accessToken = parseUrlParam(redirectUrl, "access")

        if (accessToken.isEmpty() && redirectUrl.contains("access=")) {
            val match = Regex("[?&]access=([^&]+)").find(redirectUrl)
            if (match != null) {
                accessToken = URLDecoder.decode(match.groupValues[1], "UTF-8")
            }
        }

        if (accessToken.isEmpty() && responseText.isNotEmpty()) {
            try {
                val json = JSONObject(responseText)
                if (json.has("access")) accessToken = json.getString("access")
                if (accessToken.isEmpty() && json.has("access_token")) accessToken = json.getString("access_token")
            } catch (_: Exception) {
            }
        }

        if (accessToken.isEmpty()) {
            val details = if (responseText.isNotBlank()) responseText else redirectUrl
            throw Exception("Zepp 로그인 실패 (응답: $details)")
        }

        return accessToken
    }

    private fun getAppToken(accessToken: String): String {
        val endpoints = listOf(
            "https://account.huami.com/v1/client/login",
            "https://api-mifit.huami.com/v1/client/login"
        )

        var lastException: Exception? = null

        for (endpoint in endpoints) {
            try {
                val params = mapOf(
                    "grant_type" to "access_token",
                    "app_name" to "com.huami.midong",
                    "code" to accessToken,
                    "code_type" to "access_token",
                    "country_code" to "US",
                    "dn" to "api-mifit.huami.com,account.huami.com,app-analytics.huami.com"
                )

                val postData = buildFormUrlEncoded(params)
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("User-Agent", USER_AGENT)
                    connectTimeout = 10000
                    readTimeout = 10000
                }

                conn.outputStream.use { os ->
                    os.write(postData.toByteArray(Charsets.UTF_8))
                }

                val responseText = conn.inputStream.use { readStream(it) }
                val json = JSONObject(responseText)

                val tokenInfo = json.optJSONObject("token_info")
                if (tokenInfo != null && tokenInfo.has("app_token")) {
                    return tokenInfo.getString("app_token")
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        throw lastException ?: Exception("App Token 발급 실패")
    }

    private fun getUserDevices(appToken: String): List<ZeppDeviceInfo> {
        val urlStr = "https://api-mifit.huami.com/v1/user/devices?enable_mac=true"
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("apptoken", appToken)
            connectTimeout = 10000
            readTimeout = 10000
        }

        val responseText = conn.inputStream.use { readStream(it) }
        val result = mutableListOf<ZeppDeviceInfo>()

        val json = JSONObject(responseText)
        val items = json.optJSONArray("items") ?: JSONArray()

        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val mac = item.optString("mac", "").uppercase()
            val addInfoStr = item.optString("additional_info", "")

            var authKey = ""
            val deviceName = item.optString("device_name", "Mi Band")

            if (addInfoStr.isNotEmpty()) {
                try {
                    val addInfoJson = JSONObject(addInfoStr)
                    if (addInfoJson.has("auth_key")) {
                        authKey = addInfoJson.getString("auth_key")
                    } else if (addInfoJson.has("authKey")) {
                        authKey = addInfoJson.getString("authKey")
                    }
                } catch (_: Exception) {
                    val match = Regex("\"auth_key\"\\s*:\\s*\"([^\"]+)\"").find(addInfoStr)
                        ?: Regex("\"authKey\"\\s*:\\s*\"([^\"]+)\"").find(addInfoStr)
                    if (match != null) {
                        authKey = match.groupValues[1]
                    }
                }
            }

            if (authKey.isNotBlank()) {
                result.add(
                    ZeppDeviceInfo(
                        mac = mac,
                        authKey = authKey,
                        deviceName = deviceName
                    )
                )
            }
        }

        return result
    }

    private fun buildFormUrlEncoded(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    private fun parseUrlParam(url: String, paramName: String): String {
        val match = Regex("[?&]$paramName=([^&]+)").find(url)
        return match?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
    }

    private fun readStream(isStream: java.io.InputStream): String {
        return BufferedReader(InputStreamReader(isStream, Charsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }
}
