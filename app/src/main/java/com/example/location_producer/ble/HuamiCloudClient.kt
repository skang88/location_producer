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
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class ZeppDeviceInfo(
    val mac: String,
    val authKey: String,
    val deviceName: String = ""
)

object HuamiCloudClient {

    private const val TAG = "HuamiCloudClient"
    private const val USER_AGENT = "Zepp/9.12.5 (Pixel 4; Android 12; Density/2.75)"

    private val ZEPP_KEY = "xeNtBVqzDc6tuNTh".toByteArray(Charsets.UTF_8)
    private val ZEPP_IV = "MAAAYAAAAAAAAABg".toByteArray(Charsets.UTF_8)

    private fun log(msg: String) {
        Log.d(TAG, msg)
        SensorStreamingService.addLog("[Zepp Cloud] $msg")
    }

    fun fetchAuthKeys(emailOrPhone: String, password: String): Result<List<ZeppDeviceInfo>> {
        return try {
            log("1/3 Zepp 계정 로그인 시도중: $emailOrPhone")
            val accessToken = getAccessToken(emailOrPhone, password)
            log("1/3 로그인 성공! Access Token 획득")

            log("2/3 App Token 및 User ID 발급 시도중...")
            val (appToken, userId) = getAppTokenAndUserId(accessToken)
            log("2/3 App Token 발급 성공! (User ID: $userId)")

            log("3/3 등록된 기기 및 Auth Key 조회중...")
            val devices = getUserDevices(appToken, userId)
            log("3/3 총 ${devices.size}개 기기 정보 조회 완료")

            if (devices.isEmpty()) {
                Result.failure(Exception("Zepp 계정에 등록된 기기(Mi Band)가 없습니다."))
            } else {
                for (dev in devices) {
                    log("  - 기기: ${dev.deviceName} (${dev.mac}), AuthKey: ${dev.authKey}")
                }
                Result.success(devices)
            }
        } catch (e: Exception) {
            val err = e.message ?: e.toString()
            log("오류 발생: $err")
            Result.failure(e)
        }
    }

    private fun encryptZeppPayload(data: String): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(ZEPP_KEY, "AES")
        val ivSpec = IvParameterSpec(ZEPP_IV)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun getAccessToken(emailOrPhone: String, password: String): String {
        val urlStr = "https://api-user-us2.zepp.com/v2/registrations/tokens"
        val payloadStr = "emailOrPhone=${encode(emailOrPhone)}&state=REDIRECTION&client_id=HuaMi&password=${encode(password)}&redirect_uri=${encode("https://s3-us-west-2.amazonaws.com/hm-registration/successsignin.html")}&region=us-west-2&token=access&token=refresh&country_code=US"

        val encryptedPayload = encryptZeppPayload(payloadStr)

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("app_name", "com.huami.midong")
            setRequestProperty("appname", "com.huami.midong")
            setRequestProperty("cv", "151689_9.12.5")
            setRequestProperty("v", "2.0")
            setRequestProperty("appplatform", "android_phone")
            setRequestProperty("vb", "202509151347")
            setRequestProperty("vn", "9.12.5")
            setRequestProperty("user-agent", USER_AGENT)
            setRequestProperty("x-hm-ekv", "1")
            setRequestProperty("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            connectTimeout = 10000
            readTimeout = 10000
        }

        conn.outputStream.use { os ->
            os.write(encryptedPayload)
        }

        val responseCode = conn.responseCode
        val redirectUrl = conn.getHeaderField("Location") ?: ""

        if (responseCode !in 200..303) {
            val errText = conn.errorStream?.use { readStream(it) } ?: ""
            throw Exception("로그인 HTTP $responseCode 오류: $errText")
        }

        var accessToken = parseUrlParam(redirectUrl, "access")
        if (accessToken.isEmpty()) {
            val match = Regex("[?&]access=([^&]+)").find(redirectUrl)
            if (match != null) {
                accessToken = URLDecoder.decode(match.groupValues[1], "UTF-8")
            }
        }

        if (accessToken.isEmpty()) {
            throw Exception("Zepp 로그인 실패: 아이디 또는 비밀번호를 확인해 주세요. (Location: $redirectUrl)")
        }

        return accessToken
    }

    private fun getAppTokenAndUserId(accessToken: String): Pair<String, String> {
        val loginUrl = "https://api-mifit-us2.zepp.com/v2/client/login"
        val params = mapOf(
            "code" to accessToken,
            "device_id" to UUID.randomUUID().toString(),
            "device_model" to "android_phone",
            "app_version" to "9.12.5",
            "dn" to "api-mifit.zepp.com,api-user.zepp.com,api-mifit.zepp.com,api-watch.zepp.com,app-analytics.zepp.com,auth.zepp.com,api-analytics.zepp.com",
            "third_name" to "huami",
            "source" to "com.huami.watch.hmwatchmanager:9.12.5:151689",
            "app_name" to "com.huami.midong",
            "country_code" to "US",
            "grant_type" to "access_token",
            "allow_registration" to "false",
            "lang" to "en",
            "countryState" to "US-NY"
        )

        val postData = buildFormUrlEncoded(params)
        val conn = (URL(loginUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("app_name", "com.huami.webapp")
            setRequestProperty("appname", "com.huami.webapp")
            setRequestProperty("origin", "https://user.zepp.com")
            setRequestProperty("referer", "https://user.zepp.com/")
            setRequestProperty("user-agent", USER_AGENT)
            setRequestProperty("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
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

        val appToken = tokenInfo.getString("app_token")
        val userId = tokenInfo.getString("user_id")

        return Pair(appToken, userId)
    }

    private fun getUserDevices(appToken: String, userId: String): List<ZeppDeviceInfo> {
        val urlStr = "https://api-mifit.zepp.com/users/$userId/devices?channel=a100900101016&country=US&cv=151689_9.12.5&device=android_32&device_type=android_phone&enableMultiDevice=true&lang=en_US&v=2.0&r=1&r=2&userid=$userId&appid=123"
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("appname", "com.huami.midong")
            setRequestProperty("apptoken", appToken)
            setRequestProperty("user-agent", USER_AGENT)
            connectTimeout = 10000
            readTimeout = 10000
        }

        val responseText = conn.inputStream.use { readStream(it) }
        val result = mutableListOf<ZeppDeviceInfo>()

        val json = JSONObject(responseText)
        val items = json.optJSONArray("items") ?: JSONArray()

        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val mac = if (item.has("macAddress")) item.optString("macAddress", "") else item.optString("mac", "")
            val cleanMac = mac.uppercase()

            val addInfoStr = if (item.has("additionalInfo")) item.optString("additionalInfo", "") else item.optString("additional_info", "")
            var authKey = ""
            val deviceName = item.optString("displayName", item.optString("device_name", "Mi Smart Band 6"))

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
                        mac = cleanMac,
                        authKey = authKey,
                        deviceName = if (deviceName.isBlank()) "Mi Smart Band 6" else deviceName
                    )
                )
            }
        }

        return result
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun buildFormUrlEncoded(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v)}"
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
