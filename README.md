# MultiProfileEmu

App para gestionar **múltiples perfiles de guardado** sobre una misma carpeta de save de
emulador, pensada para dispositivos compartidos en familia (PC y handhelds). En vez de pisar la
partida de otra persona, cada quien tiene su propio perfil; cambiar de perfil copia sus archivos
hacia la carpeta activa del emulador y guarda automáticamente lo que había antes, sin perder nada.

## Estado actual

Funcional en **Windows, Linux y Android**:

- Crear "grupos" (una carpeta de save activa + sus perfiles). En Android la carpeta activa se
  elige vía SAF (Storage Access Framework), ya que es una carpeta ajena a la app bajo scoped
  storage.
- Capturar el contenido actual de una carpeta como el primer perfil (flujo de primer uso, evita
  descartar saves no gestionados).
- Crear perfiles nuevos (vacíos o duplicando uno existente), renombrar, eliminar.
- **Cambiar de perfil** con respaldo automático del perfil saliente antes de sobrescribir la
  carpeta activa.
- Sincronizar manualmente un perfil sin cambiar el activo.
- Navegación por teclado (flechas / Enter / Esc) y por mando XInput-compatible (GLFW en desktop,
  `InputManager` en Android), pensada para handhelds.
- Elegir dónde vive la carpeta de datos de la app (primer uso en desktop, u "omitir" para usar la
  carpeta por defecto) y migrarla a otra ubicación después sin perder nada, desde Configuración —
  incluso a una carpeta SAF en Android.
- **Modo línea de comandos** (solo desktop) para cambiar de perfil sin abrir la ventana — pensado
  para encadenarse antes de lanzar el emulador (ver "Integración con Steam" abajo).

Presets de rutas por emulador y modo root en Android son la siguiente iteración — ver "Roadmap".

## Arquitectura

Kotlin Multiplatform + Compose Multiplatform: la lógica de dominio y toda la UI viven en
`shared/src/commonMain` y corren igual en desktop (JVM) y, a futuro, Android.

```
shared/
  commonMain/  → modelos, motor de swap (ProfileSwapEngine), persistencia JSON, UI Compose
  desktopMain/ → filesystem real, selector de carpeta (Swing), mando vía GLFW/LWJGL
  androidMain/ → filesystem vía SAF + File, selector de carpeta vía ActivityResultLauncher,
                 mando vía InputManager/KeyEvent
desktopApp/    → punto de entrada de escritorio + empaquetado (Msi/Deb)
androidApp/    → punto de entrada Android (MainActivity) + manifest/recursos + keystore de release
```

- **`ProfileSwapEngine`** (`shared/.../domain/ProfileSwapEngine.kt`): toda la mecánica de
  cambiar/crear/sincronizar/duplicar perfiles, expresada solo en términos de la interfaz
  `FileSystemAdapter` — no sabe si está tocando carpetas normales o SAF.
- **Mando**: `GamepadController` (interfaz común), con `DesktopGamepadController` (GLFW, expone
  el layout estándar de gamepad sobre XInput en Windows y evdev en Linux) y
  `AndroidGamepadController` (`InputManager`/`KeyEvent`, alimentado desde
  `Activity.dispatchKeyEvent`).
- Los datos de la app (config + snapshots de cada perfil) se guardan por defecto en
  `%APPDATA%\MultiProfileEmu` (Windows), `~/.config/MultiProfileEmu` (Linux), o el almacenamiento
  privado de la app (Android) — migrable a cualquier otra carpeta (incluida una SAF en Android)
  desde Configuración.

## Cómo correrlo

```bash
./gradlew :desktopApp:run
```

## Cómo empaquetarlo

### Desktop

```bash
./gradlew :desktopApp:packageMsi   # Windows — instalador .msi por usuario (sin admin/UAC)
./gradlew :desktopApp:packageDeb   # Linux — hay que correrlo *en* Linux, jpackage no cross-compila
```

`packageMsi` necesita [WiX Toolset](https://wixtoolset.org/) instalado (o lo descarga solo la
primera vez, vía la tarea `downloadWix` del plugin de Compose). El instalador resultante queda en
`desktopApp/build/compose/binaries/main/msi/MultiProfileEmu-<version>.msi` y se instala en
`%LOCALAPPDATA%\MultiProfileEmu` — sin pedir privilegios de administrador.

### Android

```bash
./gradlew :androidApp:assembleDebug     # sin firma de release, para probar
./gradlew :androidApp:assembleRelease   # firmado, listo para instalar/distribuir
```

El APK de release necesita un keystore local (nunca se commitea a git). Generar uno una sola vez:

```bash
keytool -genkeypair -v -keystore androidApp/release.jks -alias multiprofileemu \
  -keyalg RSA -keysize 2048 -validity 10000
```

Y crear `androidApp/keystore.properties` (también gitignored) apuntando a él:

```properties
storeFile=release.jks
storePassword=<tu contraseña>
keyAlias=multiprofileemu
keyPassword=<tu contraseña>
```

**Importante:** guardá una copia de `release.jks` y `keystore.properties` en un lugar seguro fuera
del repo. Si se pierden, cualquier futura versión firmada con un keystore distinto no podrá
instalarse *sobre* una ya instalada con este — Android lo rechaza como si fuera una app distinta.

El APK queda en `androidApp/build/outputs/apk/release/androidApp-release.apk`.

## Línea de comandos

El mismo ejecutable, invocado con argumentos, corre en modo headless (sin abrir ventana ni tocar
GLFW/Compose) y termina casi instantáneamente:

```bash
MultiProfileEmu list                        # lista grupos y perfiles, marcando el activo con *
MultiProfileEmu switch "Zelda BOTW" "Dad"    # cambia el perfil activo (nombre o id)
```

Códigos de salida: `0` éxito (o "ya estaba activo", no-op seguro), `1` grupo/perfil no encontrado,
`2` error de uso.

### Integración con Steam / Steam ROM Manager

Steam no encadena comandos por sí solo — el campo "Opciones de lanzamiento" solo agrega argumentos
al ejecutable configurado como destino del acceso directo. Para lograr "sincronizar el perfil y
después abrir el juego" en un solo click, apuntá el acceso directo a un pequeño script que llame
primero a `MultiProfileEmu switch` y recién después al emulador real:

**Windows** (`launch-zelda.bat`, destino del acceso directo en Steam):
```bat
@echo off
"%LOCALAPPDATA%\MultiProfileEmu\MultiProfileEmu.exe" switch "Zelda BOTW" "%USERNAME%"
if errorlevel 1 exit /b %errorlevel%
"C:\Emulators\Ryujinx\Ryujinx.exe" %*
```

**Linux** (`launch-zelda.sh`):
```bash
#!/bin/bash
MultiProfileEmu switch "Zelda BOTW" "$USER" || exit $?
exec /path/to/ryujinx "$@"
```

Las "Opciones de lanzamiento" que Steam ROM Manager generó para el emulador (ruta de la ROM, flags,
etc.) siguen funcionando igual — el script solo las reenvía (`%*` / `"$@"`) después de sincronizar
el perfil.

## Tests

```bash
./gradlew :shared:desktopTest
```

Cubre el flujo central: crear el primer perfil desde el contenido activo, crear un segundo
perfil, y cambiar entre ambos verificando que ninguno pierde datos.

## Roadmap

- **Presets de emuladores**: detectar automáticamente rutas de save típicas de RetroArch,
  Dolphin, PCSX2, Cemu, Yuzu/Ryujinx, DuckStation, etc., portando los patrones de
  [SaveState-App](https://github.com/Matteo842/SaveState-App).
- **Modo root en Android** para paths protegidos bajo `Android/data/...`, portando
  `RootAccessHelper.kt` de SaveState-App.
- Multi-idioma, theming, distribución AppImage/Flatpak para Linux, subir a Play Store (necesitaría
  su propio keystore de upload distinto del de firma local).
