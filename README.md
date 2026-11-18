# Mixtli Libros

App Android para **enviar libros (EPUB) a un lector Xteink X4 / X3 por Bluetooth**, sin WiFi ni cables.

Funciona con el firmware **[CrossPoint Mixtli](https://github.com/cxrlind7/crosspoint-mixtli)**, que agrega al lector la pantalla *Receive via Bluetooth*. Con el firmware original de CrossPoint no funciona.

## Instalar

1. Instala el firmware [CrossPoint Mixtli](https://github.com/cxrlind7/crosspoint-mixtli#instalar-en-el-lector) en tu Xteink.
2. Descarga `MixtliLibros.apk` de la sección [Releases](https://github.com/cxrlind7/mixtli-libros/releases) e instálalo en el teléfono (Android 8 o más nuevo). Android te pedirá permitir instalar apps de origen desconocido.
3. La primera vez que envíes un libro, acepta el permiso **Dispositivos cercanos**. La app lo usa solo para encontrar al lector, no para tu ubicación.

## Usar

1. En el Xteink, en la pantalla de inicio, abre **Receive via Bluetooth** (*Recibir por Bluetooth* si el lector está en español).
2. En el teléfono puedes:
   - abrir **Mixtli Libros** → **Agregar** y elegir uno o varios EPUB, o
   - desde cualquier app (Archivos, Drive, Telegram, el navegador…) usar **Compartir → Mixtli Libros**.
3. Los libros se envían solos. Cada uno muestra su estado:

   | Ícono | Estado |
   | --- | --- |
   | 📖 | En espera |
   | ⬆️ | Enviando (con barra de progreso) |
   | ✅ | Ya está en el Xteink |
   | ⚠️ | Error (toca **Enviar** para reintentar) |

En el lector, los libros quedan en `/Mis Libros Kindle` y aparecen en la biblioteca.

### Si no encuentra el lector

- Revisa que la pantalla **Receive via Bluetooth** esté abierta en el Xteink (debe decir *Ready to receive books* y *Xteink Mixtli*).
- Revisa que el Bluetooth del teléfono esté encendido. No hace falta emparejar desde los ajustes de Android.
- Si el WiFi del lector está activo (por ejemplo, saliste de *File Transfer* hace un momento), reinícialo.

## Cómo funciona

La app busca un dispositivo BLE con el servicio `8f2c1000-6b1e-4d3a-9b7e-4d6978746c69`, se conecta y envía cada archivo en paquetes de hasta 244 bytes. El lector confirma cada 4 KB y la app nunca deja más de 8 KB sin confirmar. Al final, el lector verifica el CRC32 antes de guardar el archivo. El protocolo completo está en el [README del firmware](https://github.com/cxrlind7/crosspoint-mixtli#protocolo-para-quien-quiera-hacer-otro-cliente).

Todo el código está en [`src/com/mixtli/libros/MainActivity.java`](src/com/mixtli/libros/MainActivity.java). La interfaz se arma en código, sin layouts XML, y los íconos son Material Icons en `res/drawable`.

## Compilar

Se compila sin Gradle, solo con las herramientas del Android SDK (`aapt2`, `javac`, `d8`, `zipalign`, `apksigner`). El script es para Windows/PowerShell.

Requisitos:

- Android SDK con `build-tools/33.0.2` y `platforms/android-33` en `%LOCALAPPDATA%\Android\Sdk`
- JDK 17 en `%LOCALAPPDATA%\jdk17\<versión>`
- Python 3 (para empaquetar el `.dex` en el APK)

```powershell
pwsh -File build.ps1            # genera MixtliLibros.apk
pwsh -File build.ps1 -Install   # además lo instala con adb en el teléfono conectado
```

La primera vez, el script crea una llave de firma local (`mixtli.keystore`). Esa llave no se sube al repo. Si compilas con otra llave, desinstala antes la versión de *Releases*, porque Android no permite actualizar una app firmada con una llave diferente.

## Licencia

MIT. Los íconos son [Material Icons](https://fonts.google.com/icons) (Apache 2.0).
