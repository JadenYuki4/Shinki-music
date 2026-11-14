# Presentación del proyecto

## Descripción breve para GitHub

Reproductor de música de código abierto para Android y escritorio, hecho con Kotlin y Compose Multiplatform. Incluye búsqueda en varias fuentes, cola automática, biblioteca local, descargas, letras sincronizadas y restauración de sesión.

## Qué hace

Shinki music permite buscar canciones en YouTube Music, YouTube, SoundCloud y Bandcamp, reproducirlas y organizar una biblioteca personal. Incluye favoritos, historial, listas locales, descargas compatibles con reproducción sin conexión y letras sincronizadas cuando están disponibles. Al volver a abrir la app, recupera la canción y la posición guardadas para continuar con el primer Play.

## Diseño

La edición Aurora combina una interfaz oscura con acentos violeta y cian, tarjetas redondeadas y controles de reproducción siempre accesibles. En Android usa navegación inferior; en escritorio, una barra lateral y paneles para la cola y las letras. Las paletas y los ajustes visuales permiten adaptar la apariencia. Las referencias y atribuciones están en [NOTICE.md](../NOTICE.md).

## Herramientas utilizadas

- Kotlin Multiplatform y Compose Multiplatform: lógica e interfaz compartidas.
- Media3 y ExoPlayer: reproducción y sesión multimedia de Android.
- libmpv y JNA: reproducción en escritorio.
- NewPipe Extractor: acceso a las fuentes musicales.
- SQLDelight y SQLite: biblioteca e historial locales.
- Multiplatform Settings: preferencias y sesión guardada.
- Corutinas y Flow: trabajo asíncrono y estado de la aplicación.
- Koin: inyección de dependencias.
- Gradle y GitHub Actions: compilación y distribución.

## Presentación pública

El repositorio ya sirve como proyecto de portfolio. El README reúne funciones, diseño, captura de Android, tecnologías, compilación y licencia. Un tablero de GitHub Projects puede servir para organizar pendientes; no hace falta crear otro repositorio para presentar la aplicación.

La [última versión pública](https://github.com/JadenYuki4/Shinki-music/releases/latest) incluye el APK para Android y el instalador MSI para Windows x64. El APK usa el mismo certificado Android Debug que la versión 5.3.1. El MSI se distribuye sin firma Authenticode. La validación en Linux real sigue pendiente.
