package online.ujing.mini.net

/**
 * U净云端请求头构造。
 *
 * 服务端用 `x-app-code` 分流：不同值走不同鉴权链。
 *  - ZI：验证码 / 登录（无需 token）
 *  - BI：业务接口（需 Bearer token）—— 与 cookie / storage 携带的 weex-version 配合
 *  - BA：Android 侧通知配置（本项目未用）
 *
 * 逆向来源：U-Clean-Reserve + APK 内 assets/washer 下 Weex JS bundle 的 api 模块 header 表。
 */
object Headers {

    const val BASE_URL = "https://phoenix.ujing.online"

    private const val APP_VERSION_CODE = "2.4.3"
    private const val APP_VERSION_BIZ = "2.4.2"
    private const val WEEX_VERSION = "1.1.30"
    private const val UA = "U jing/$APP_VERSION_CODE (iPhone; iOS 17.3; Scale/3.00)"

    /** ZI 组：取验证码。 */
    fun ziCode(): Map<String, String> = mapOf(
        "x-app-code" to "ZI",
        "x-app-version" to APP_VERSION_CODE,
        "User-Agent" to UA,
        "x-mobile-brand" to "apple",
        "x-mobile-model" to "iPhone14,5",
        "x-user-geo" to "-180.000000,-180.000000"
    )

    /** ZI 组 + JSON：登录。 */
    fun ziLogin(): Map<String, String> = ziCode() + mapOf(
        "Content-Type" to "application/json"
    )

    /** BI 组：业务接口（需 token）。 */
    fun bi(token: String): Map<String, String> = mapOf(
        "x-app-code" to "BI",
        "x-app-version" to APP_VERSION_BIZ,
        "weex-version" to WEEX_VERSION,
        "User-Agent" to UA,
        "x-mobile-brand" to "apple",
        "x-mobile-model" to "iPhone14,5",
        "x-user-geo" to "-180.000000,-180.000000",
        "Content-Type" to "application/json",
        "Authorization" to "Bearer $token"
    )

    /** 带 query 的业务 GET（编码由 OkHttp HttpUrl 负责）。 */
    fun biQuery(token: String): Map<String, String> = bi(token) - "Content-Type"
}
