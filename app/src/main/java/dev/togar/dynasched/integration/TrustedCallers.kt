package dev.togar.dynasched.integration

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * スキマスの中身を読ませてよい相手。パッケージ名と、その署名（証明書の SHA-256）の組で決める。
 *
 * **署名権限（protectionLevel="signature"）は使えない。** スキマスは v46 からリリース鍵、
 * ドパチルはデバッグ鍵で署名していて、鍵が違うと権限は黙って与えられない。
 * 代わりに、呼んできた相手の署名をこちらで見て、知っている鍵の時だけ答える。
 * パッケージ名だけで信じると、同じ名前を名乗る別のアプリに読まれる。
 */
object TrustedCallers {

    const val DOPACHIRU = "com.dopachiru"

    /**
     * ドパチル（0.44 から FlexHarness。パッケージ名は com.dopachiru のまま）の署名。
     * ドパチルを別の鍵で作り直したら、ここに足す（`apksigner verify --print-certs` の SHA-256）
     */
    private val DOPACHIRU_CERTS = setOf(
        // 専用の鍵（0.42 から。CN=dopachiru）。いまのドパチルはこれ
        "d4a015b4a3fb8d39541ad54efdd31ebb3b0376f511a44ccc42c7051859e9ea73",
        // 前のPC（ドパチル 0.40 まで）
        "827020ecce900217b5523d8a574fe2fb93a0acd1dbc3e04f9e72720509623c83",
        // いまのPC（ドパチル 0.41 から）
        "cf3470b1af34854262db3b8ce5794797c6936c770e2ecb0f45557f463cd489a6"
    )

    private val ALLOWED: Map<String, Set<String>> = mapOf(DOPACHIRU to DOPACHIRU_CERTS)

    /** 読ませてよいか。署名は1つでも知っている鍵なら可（鍵を回した後も前の鍵が履歴に残る） */
    fun allowed(pkg: String?, certDigests: Collection<String>): Boolean {
        val known = ALLOWED[pkg ?: return false] ?: return false
        return certDigests.any { it.lowercase() in known }
    }

    /** そのパッケージの署名（SHA-256 の16進）。入っていなければ空 */
    fun certDigests(ctx: Context, pkg: String): List<String> = try {
        val pm = ctx.packageManager
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            when {
                info == null -> emptyArray()
                info.hasMultipleSigners() -> info.apkContentsSigners
                else -> info.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        certs.orEmpty().map { sha256(it.toByteArray()) }
    } catch (e: PackageManager.NameNotFoundException) {
        emptyList()
    }

    fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
