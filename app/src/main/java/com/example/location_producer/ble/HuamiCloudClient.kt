package com.example.location_producer.ble

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
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

    fun fetchAuthKeys(emailOrPhone: String, password: String): Result<List<ZeppDeviceInfo>> {
        return try {
            Log.d(TAG, "Attempting Zepp login for: $emailOrPhone")
            val (accessToken, _) = loginAccount(emailOrPhone, password)
            Log.d(TAG, "Account login successful. Access token obtained.")

            val appToken = getAppToken(accessToken)
            Log.d(TAG, "App token obtained successfully.")

            val devices = getUserDevices(appToken)
            Log.d(TAG, "Fetched ${devices.size} device(s) from Zepp Cloud.")

            if (devices.isEmpty()) {
                Result.failure(Exception("Zepp 계정에 등록된 기기(Mi Band)가 없습니다."))
            } else {
                Result.success(devices)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Zepp Cloud API Error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun loginAccount(emailOrPhone: String, password: String): Pair<String, String> {
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
            conn.inputStream.use { readStream(it) }
        } else {
            conn.errorStream?.use { readStream(it) } ?: ""
        }

        var accessToken = parseUrlParam(redirectUrl, "access")
        var refreshToken = parseUrlParam(redirectUrl, "refresh")

        if (accessToken.isEmpty() && responseText.isNotEmpty()) {
            try {
                val json = JSONObject(responseText)
                if (json.has("access_token")) accessToken = json.getString("access_token")
                if (json.has("refresh_token")) refreshToken = json.getString("refresh_token")
            } catch (_: Exception) {
            }
        }

        if (accessToken.isEmpty()) {
            throw Exception("Zepp 로그인 실패: 아이디 또는 비밀번호를 확인해 주세요.")
        }

        return Pair(accessToken, refreshToken)
    }

    private fun getAppToken(accessToken: String): String {
        val urlStr = "https://api-mifit.huami.com/v1/client/login"
        val params = mapOf(
            "grant_type" to "access_token",
            "app_name" to "com.huami.midong",
            "code" to accessToken,
            "code_type" to "access_token",
            "dn" to "api-mifit.huami.com,account.huami.com,app-analytics.huami.com"
        )

        val postData = buildFormUrlEncoded(params)
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
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
            ?: throw Exception("Zepp App Token 파싱 실패: $responseText")

        return tokenInfo.getString("app_token")
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
        return match?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
    }

    private fun readStream(isStream: java.io.InputStream): String {
        return BufferedReader(InputStreamReader(isStream, Charsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }
}
