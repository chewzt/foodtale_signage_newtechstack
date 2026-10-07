plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.foodtale.signage"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.foodtale.signage"
        minSdk = 24
        targetSdk = 35
        versionCode = 34
        versionName = "0.2.33"
    }

    flavorDimensions += "role"
    productFlavors {
        create("player") {
            dimension = "role"
            buildConfigField("boolean", "START_CMS", "false")
        }
        create("cms") {
            dimension = "role"
            buildConfigField("boolean", "START_CMS", "true")
        }
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = false
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

val cmsDir = rootProject.layout.projectDirectory.dir("../cms")
val cmsArm = layout.projectDirectory.file("src/cms/jniLibs/armeabi-v7a/libfoodtale_cms.so")
val cmsArm64 = layout.projectDirectory.file("src/cms/jniLibs/arm64-v8a/libfoodtale_cms.so")
val buildCms = tasks.register<Exec>("buildCms") {
    workingDir(cmsDir)
    commandLine(
        "bash", "-c",
        """
        set -e
        mkdir -p '${cmsArm.asFile.parent}' '${cmsArm64.asFile.parent}'
        CGO_ENABLED=0 GOOS=linux GOARCH=arm go build -o '${cmsArm.asFile.absolutePath}' .
        CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -o '${cmsArm64.asFile.absolutePath}' .
        """.trimIndent()
    )
    outputs.files(cmsArm, cmsArm64)
    inputs.dir(cmsDir.dir("internal"))
    inputs.file(cmsDir.file("main.go"))
}
tasks.configureEach {
    if (name == "preCmsDebugBuild" || name == "preCmsReleaseBuild") {
        dependsOn(buildCms)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
