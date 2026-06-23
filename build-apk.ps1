$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
$jdkHome = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
$androidHome = 'C:\Users\ASUS\AppData\Local\Android\Sdk'
$apkSource = Join-Path $projectRoot 'android\app\build\outputs\apk\debug\app-debug.apk'
$apkTarget = Join-Path $projectRoot '知行网络学堂-debug.apk'

if (-not (Test-Path -LiteralPath $jdkHome)) {
  throw "未找到 JDK 21：$jdkHome"
}

if (-not (Test-Path -LiteralPath $androidHome)) {
  throw "未找到 Android SDK：$androidHome"
}

$env:JAVA_HOME = $jdkHome
$env:ANDROID_HOME = $androidHome
$env:ANDROID_SDK_ROOT = $androidHome

Push-Location $projectRoot
try {
  if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'node_modules'))) {
    npm install
  }

  npm run sync

  Push-Location (Join-Path $projectRoot 'android')
  try {
    .\gradlew.bat assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) {
      throw "Android Gradle 构建失败，退出代码：$LASTEXITCODE"
    }
  }
  finally {
    Pop-Location
  }

  if (-not (Test-Path -LiteralPath $apkSource)) {
    throw "构建完成但未找到 APK：$apkSource"
  }

  Copy-Item -LiteralPath $apkSource -Destination $apkTarget -Force
  Write-Host "APK 已生成：$apkTarget"
}
finally {
  Pop-Location
}
