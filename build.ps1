# Compila MixtliLibros.apk sin Gradle: aapt2 + javac + d8 + zipalign + apksigner.
# Uso:  pwsh -File build.ps1            (deja android\MixtliLibros.apk)
#       pwsh -File build.ps1 -Install   (además lo instala en el teléfono conectado)
param([switch]$Install, [string]$Serial)
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$sdk  = "$env:LOCALAPPDATA\Android\Sdk"
$bt   = "$sdk\build-tools\33.0.2"
$jar  = "$sdk\platforms\android-33\android.jar"
$env:JAVA_HOME = (Get-ChildItem "$env:LOCALAPPDATA\jdk17" -Directory | Select-Object -First 1).FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$b = "$here\build"

if (Test-Path $b) { Remove-Item $b -Recurse -Force }
New-Item -ItemType Directory "$b\gen","$b\classes","$b\dex" | Out-Null

Write-Host "1/6 recursos"
& "$bt\aapt2.exe" compile --dir "$here\res" -o "$b\res.zip"
if ($LASTEXITCODE) { throw "aapt2 compile" }
& "$bt\aapt2.exe" link -o "$b\base.apk" -I $jar --manifest "$here\AndroidManifest.xml" `
    --java "$b\gen" "$b\res.zip" --auto-add-overlay
if ($LASTEXITCODE) { throw "aapt2 link" }

Write-Host "2/6 java"
$srcs = Get-ChildItem "$here\src","$b\gen" -Recurse -Filter *.java | ForEach-Object FullName
& javac -nowarn -encoding UTF-8 --release 8 -cp $jar -d "$b\classes" @srcs 2>&1 |
    Where-Object { $_ -notmatch "bootstrap class path|source value 8|target value 8|To suppress warnings|^\d+ warnings?$|^warning: \[options\]" }
if ($LASTEXITCODE) { throw "javac" }

Write-Host "3/6 dex"
$classes = Get-ChildItem "$b\classes" -Recurse -Filter *.class | ForEach-Object FullName
& java -cp "$bt\lib\d8.jar" com.android.tools.r8.D8 --min-api 26 --lib $jar --output "$b\dex" @classes
if ($LASTEXITCODE) { throw "d8" }

Write-Host "4/6 empaquetar"
python -c "import zipfile,shutil; shutil.copy(r'$b\base.apk', r'$b\unsigned.apk'); z=zipfile.ZipFile(r'$b\unsigned.apk','a',zipfile.ZIP_DEFLATED); z.write(r'$b\dex\classes.dex','classes.dex'); z.close()"
& "$bt\zipalign.exe" -f 4 "$b\unsigned.apk" "$b\aligned.apk"
if ($LASTEXITCODE) { throw "zipalign" }

Write-Host "5/6 firmar"
$ks = "$here\mixtli.keystore"
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -storepass mixtli -keypass mixtli -alias mixtli `
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Mixtli Libros" 2>&1 | Out-Null
}
& java -jar "$bt\lib\apksigner.jar" sign --ks $ks --ks-pass pass:mixtli --out "$here\MixtliLibros.apk" "$b\aligned.apk"
if ($LASTEXITCODE) { throw "apksigner" }
Write-Host ("6/6 listo: {0} ({1:n0} KB)" -f "$here\MixtliLibros.apk", ((Get-Item "$here\MixtliLibros.apk").Length / 1KB))

if ($Install) {
    $adb = "$sdk\platform-tools\adb.exe"
    $s = if ($Serial) { @("-s", $Serial) } else { @() }
    & $adb @s install -r "$here\MixtliLibros.apk"
}

