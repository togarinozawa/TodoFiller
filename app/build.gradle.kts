import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * リリース署名の設定。
 *
 * このリポジトリは公開なので、鍵もパスワードもリポジトリの外に置いてある。
 *
 * **鍵かパスワードを失うと、配布済みアプリへ二度と更新を配れない。**
 * Android は別の鍵で署名されたAPKの上書きインストールを拒否するので、
 * 利用者は一度アンインストールするしかなくなる。両方を必ず別の場所へ控えること。
 *
 * 見つからない場合は release ビルドを未署名にする（デバッグ鍵で署名して
 * 「リリース版のつもり」の物が出回るのが一番まずい）。
 */
val keystorePropsFile = rootProject.file("../keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

/**
 * デバッグ鍵で署名したリリースビルドを作る（`-PuseDebugKey=true`）。
 *
 * **配信中の版はデバッグ鍵（v31〜）。配る時は必ず `-PuseDebugKey=true` を付ける。**
 *
 * 鍵を変えると上書き更新できない。Android は署名が違うAPKの上書きを拒否し、
 * 端末には「アプリがインストールされていません」としか出ない。
 * v29でリリース鍵へ移そうとしたが、そのたびにアンインストールと復元が要り、
 * 得るものが無かったので戻した。**利用者が本人だけのうちは移す理由が無い。**
 * 実際に配布する時が来たら、その時だけの作業として計画すること。
 */
val useDebugKey = (project.findProperty("useDebugKey") as String?)?.toBoolean() == true
val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
val hasReleaseKey = if (useDebugKey) debugKeystore.exists()
    else keystoreProps.getProperty("storeFile")?.let { file(it).exists() } == true
if (!hasReleaseKey) {
    // 黙って未署名APKが出ると「リリース版を作ったつもり」で配ってしまう。必ず気付かせる。
    logger.warn("警告: 署名鍵が見つかりません。release ビルドは未署名になります。")
} else if (useDebugKey) {
    logger.lifecycle("注意: デバッグ鍵で署名します（-PuseDebugKey）。リリース鍵の版とは相互に上書きできません。")
}

android {
    namespace = "dev.togar.dynasched"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.togar.dynasched"
        minSdk = 26
        targetSdk = 34
        versionCode = 39
        versionName = "39.0"
        // 更新の確認先。GitHubの dist ブランチに最新版だけを置いてある。
        // サーバーは畳んだので、通信はここ1本だけ。
        buildConfigField(
            "String", "UPDATE_MANIFEST_URL",
            "\"https://raw.githubusercontent.com/togarinozawa/TodoFiller/dist/version.json\""
        )
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                if (useDebugKey) {
                    // Android SDK が作る共通のデバッグ鍵。パスワードは固定値
                    storeFile = debugKeystore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                } else {
                    storeFile = file(keystoreProps.getProperty("storeFile"))
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    // テストのみ。APKには入らないので「外部ライブラリ不使用」の方針とは衝突しない
    testImplementation("junit:junit:4.13.2")
    // android.jar に入っている org.json は中身が空で、呼ぶと例外になる。
    // 本物をテスト側にだけ入れて、控えの読み取りをJVM上で確かめられるようにする
    testImplementation("org.json:json:20240303")
}
