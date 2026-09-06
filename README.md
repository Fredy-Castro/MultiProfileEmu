# MultiProfileEmu

App para gestionar **múltiples perfiles de guardado** sobre una misma carpeta de save de
emulador, pensada para dispositivos compartidos en familia (PC y handhelds). En vez de pisar la
partida de otra persona, cada quien tiene su propio perfil; cambiar de perfil copia sus archivos
hacia la carpeta activa del emulador y guarda automáticamente lo que había antes, sin perder nada.

## Estado actual

MVP de escritorio (Windows/Linux) funcional:

- Crear "grupos" (una carpeta de save activa + sus perfiles).
- Capturar el contenido actual de una carpeta como el primer perfil (flujo de primer uso, evita
  descartar saves no gestionados).
- Crear perfiles nuevos (vacíos o duplicando uno existente), renombrar, eliminar.
- **Cambiar de perfil** con respaldo automático del perfil saliente antes de sobrescribir la
  carpeta activa.
- Sincronizar manualmente un perfil sin cambiar el activo.
- Navegación por teclado (flechas / Enter / Esc) y por mando XInput-compatible (GLFW), pensada
  para handhelds.

Android y los presets de rutas por emulador son la siguiente iteración — ver "Roadmap" abajo.

## Arquitectura

Kotlin Multiplatform + Compose Multiplatform: la lógica de dominio y toda la UI viven en
`shared/src/commonMain` y corren igual en desktop (JVM) y, a futuro, Android.

```
shared/
  commonMain/  → modelos, motor de swap (ProfileSwapEngine), persistencia JSON, UI Compose
  desktopMain/ → implementaciones concretas: filesystem real, selector de carpeta (Swing),
                 mando vía GLFW/LWJGL
desktopApp/    → punto de entrada de escritorio + empaquetado (Msi/Deb)
```

- **`ProfileSwapEngine`** (`shared/.../domain/ProfileSwapEngine.kt`): toda la mecánica de
  cambiar/crear/sincronizar/duplicar perfiles, expresada solo en términos de la interfaz
  `FileSystemAdapter` — no sabe si está tocando carpetas normales (desktop) o SAF (Android futuro).
- **Mando**: `GamepadController` (interfaz común) + `DesktopGamepadController` (GLFW), que expone
  el layout estándar de gamepad (A/B/X/Y, D-Pad, bumpers) sobre XInput en Windows y evdev en Linux.
- Los datos de la app (config + snapshots de cada perfil) se guardan en
  `%APPDATA%\MultiProfileEmu` (Windows) o `~/.config/MultiProfileEmu` (Linux).

## Cómo correrlo

```bash
./gradlew :desktopApp:run
```

## Cómo empaquetarlo

```bash
./gradlew :desktopApp:packageMsi   # Windows
./gradlew :desktopApp:packageDeb   # Linux
```

## Tests

```bash
./gradlew :shared:desktopTest
```

Cubre el flujo central: crear el primer perfil desde el contenido activo, crear un segundo
perfil, y cambiar entre ambos verificando que ninguno pierde datos.

## Roadmap

- **Android**: mismo módulo `shared`, agregando `androidMain` con acceso a carpetas vía SAF
  (Storage Access Framework) y mando vía `InputManager`/`KeyEvent`, portando los patrones ya
  probados en [SaveState-App](https://github.com/Matteo842/SaveState-App).
- **Presets de emuladores**: detectar automáticamente rutas de save típicas de RetroArch,
  Dolphin, PCSX2, Cemu, Yuzu/Ryujinx, DuckStation, etc. en Windows/Linux.
- Multi-idioma, theming, ícono de la app, distribución AppImage/Flatpak para Linux.
