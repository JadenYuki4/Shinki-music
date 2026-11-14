# Revisión de Aurora 5.3.3

Fecha: 13 de noviembre de 2026. Rama de trabajo: `release/5.3.3-limpieza`.

## Pruebas de Android

Se descargaron los APK públicos de las releases `v5.3.0` y `v5.3.1` de GitHub y se instalaron en un emulador `Medium_Phone_API_37.0`, Android 17, x86_64.

1. Ajustes de la 5.3.0 mostró «Aurora v5.3.0 (compilación 168)».
2. Buscar actualizaciones mostró «Actualización disponible · v5.3.1» y el botón Ver descarga.
3. `adb install -r` instaló el APK público 5.3.1 sin desinstalar la 5.3.0. Android confirmó `versionName=5.3.1` y `versionCode=169`.
4. La 5.3.1 reprodujo una canción real por streaming. Se pausó «Daft Punk - Get Lucky (Official Audio) ft. Pharrell Williams, Nile Rodgers» en 1:28, se envió la app al inicio, se cerró su proceso y se volvió a abrir. La misma canción apareció en 1:28, en pausa. El primer Play continuó la reproducción.
5. En el APK final local 5.3.3, una pista WAV descargada de prueba se restauró en pausa a 0:20 después de cerrar el proceso. El primer Play avanzó a 0:22 con la misma pista. La secuencia se ejecutó con datos de QA limpios y después de comprobar el guardado periódico y la posición exacta al pausar.

El ensayo usa instalación por ADB del APK publicado. Se verificó el aviso dentro de la app; no se recorrió la descarga e instalación desde el navegador del teléfono. Las capturas de jerarquía de UI y el script de comprobación están en `.qa/`, excluido de Git.

## Compilación y tests

- Se ejecutó de nuevo `:shared:jvmTest :shared:testAndroidHostTest :desktopApp:test` con `--rerun-tasks`: resultado exitoso.
- La suite habitual, después de agregar la regresión del recurso, registra 58 pasados y uno omitido en JVM, 36 pasados en Android host y dos pasados y dos omitidos en Desktop. Las ejecuciones con red se detallan por separado para no contar un omitido como aprobado.
- `build` encontró dos copias idénticas de `anime_shrine_bg.jpg` en los recursos compartidos. Ambas tenían SHA-256 `842b11aab2a94ce24325b6a7705d5817b01d76e6e535f0ecf5016a0a104d0bd4`. Se conservó la de `commonMain` y se quitó la de `jvmAndAndroidMain`.
- Se agregó `BundledResourcesTest` para comprobar que el fondo siga disponible como JPEG en el classpath. El build completo pasó después de la corrección, incluidos Android Debug/Release y Android Lint.
- `:androidApp:connectedDebugAndroidTest` con `liveHls=true`: tres tests pasados, sin fallos. Cubren HLS de audio real, reproducción en segundo plano con notificación, seek después del fin y persistencia de posición. La prueba HLS es optativa para mantener la suite habitual independiente de internet.
- El test de sesión comprueba un checkpoint mientras la pista sigue sonando y después exige que el valor persistido coincida con la posición pausada. Limpia la sesión previa y desactiva autoplay en la fixture para evitar aceptar datos antiguos o consultar recomendaciones durante la prueba.
- `:shared:jvmTest :desktopApp:test -PorbitLive=true`: 59 tests compartidos pasados y tres de escritorio pasados. Se comprobaron fuentes públicas, letras, búsqueda, continuación de resultados, resolución de stream y decodificación nativa. El test de teclas multimedia quedó omitido porque requiere una activación separada y puede interferir con la prueba manual de Windows.

Para repetir la prueba HLS en PowerShell con un Android conectado:

```powershell
.\gradlew.bat :androidApp:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.liveHls=true"
```

## Contraste del contexto con el código

| Punto | Evidencia y alcance |
| --- | --- |
| Aurora 5.3.3, compilación 171 | `gradle.properties` y el `ReleaseInfo.kt` generado contienen esos valores. GitHub devuelve 5.3.1 como última release pública consultada. |
| Seek unificado y sesión restaurada | `Playback.seekTo`, sus consumidores de barra/letras y los tests de `PlayerFeaturesTest`; reapertura probada en Android. |
| Seek después del fin | `MpvEngine.seek` vuelve a cargar el stream; pasó la prueba nativa de EOF. El test instrumentado Android también busca después de `STATE_ENDED`. |
| libmpv anterior a 0.38 | El código usa `set start` y `loadfile` sin el argumento adicional. No se ejecutó una versión antigua de la biblioteca. |
| HLS en Android | Dependencia Media3 HLS, tipo MIME y detección de `.m3u8` presentes; tests de mapeo y reproducción HLS real de audio en el emulador pasados. La muestra es la variante AAC de los [ejemplos públicos de Apple](https://developer.apple.com/streaming/examples/). |
| Permisos y segundo plano | Manifest, solicitud de notificaciones observada en Android y test de notificación multimedia pasado. |
| Rendimiento | Búsqueda paralela, UI que separa pista/posición y escrituras acotadas presentes. No se ejecutó un benchmark comparativo. |
| Cola automática | `playFromSearch`, lote por defecto de 8 y umbral de recarga de 3 presentes. Los tests cubren variantes, ranking y cola manual. El máximo de dos artistas consecutivos tiene las excepciones indicadas más abajo. |
| Pesos | `RecommendationWeights` declara 0.45 / 0.30 / 0.25 y el scoring los usa. |
| Volumen, mute y autoplay | Campos guardados y restaurados en `SessionStore`; tests de volumen/mute con debounce y aplicación previa a la carga pasados. |
| Eventos de guardado | `MainActivity.onPause/onStop`, disposición del host Desktop, cambios de reproducción y umbral de posición presentes. Se probó cierre de proceso en Android; el cierre interactivo de Windows queda a la prueba del usuario. |

## Limpieza

Se conservaron las APIs públicas y los algoritmos de reproducción, búsqueda y recomendaciones. La limpieza retira imports sin uso, comentarios que narraban la interfaz y emojis de etiquetas; reemplaza aserciones de nulidad y mejora nombres locales. Se mantienen cabeceras de licencia, atribuciones y explicaciones sobre límites de almacenamiento, concurrencia, cancelación y aislamiento de previews.

Se ejecutó `ktlintFormat detekt build` al terminar los archivos editados. El comando se detiene porque `ktlintFormat` no existe en este proyecto. La comprobación independiente de `detekt` también devolvió «Task 'detekt' not found». Se ejecutó `build` por separado; no se agregaron plugins ni dependencias para simular esos controles. Android Lint terminó sin errores y con 14 advertencias en su reporte.

Las capturas genéricas de excepciones que protegen fronteras de plataforma, proveedores y almacenamiento se conservan cuando reducirlas dejaría escapar errores antes convertidos en mensajes de UI. En particular, la captura de `Throwable` del descargador elimina el archivo parcial y vuelve a lanzar el mismo error. Eliminarla sin conservar esa garantía alteraría el comportamiento. No se encontraron `println` ni `Log.d` en los archivos Kotlin revisados; se mantiene el registro de errores reales de ExoPlayer.

El barrido final no encontró `!!` ni imports explícitos sin referencias textuales, excluyendo operadores implícitos como `getValue`. Esa búsqueda es una comprobación auxiliar, no sustituye un analizador estático. No se eliminaron APIs públicas por carecer de consumidores internos.

### Resumen por archivo

Los archivos Kotlin modificados se verificaron de forma sucesiva con el comando solicitado y con `build` por separado, que terminó correctamente. Las tareas de linters ausentes no se cuentan como aprobadas.

| Archivo | Cambio |
| --- | --- |
| `AndroidEngine.kt` | Referencias locales comprobadas en lugar de dos `!!`. |
| `MpvEngine.kt` | Comprobaciones con mensaje para biblioteca y contexto nativos, sin `!!`. |
| Desktop `main.kt` | Import de `Color` sin uso eliminado. |
| `AndroidStorage.kt` | KDoc redundante eliminado. |
| `EqualizerAndShortcuts.kt` | Cuatro KDoc descriptivos eliminados. |
| `ListeningStats.kt` | Título sin símbolo decorativo. |
| `M3uAndBackup.kt` | Comentarios redundantes y emoji de lista eliminados. |
| `MusicShell.kt` | Cuatro imports sin uso, descripciones visuales y pictogramas limpiados; atribuciones conservadas. |
| `OfflineDownloads.kt` | Etiquetas de descarga, carpeta y papelera con texto simple. |
| `Playback.kt` | Respuestas de recomendaciones y streams con nombres locales del dominio; KDoc redundante eliminado. |
| `PlayerDock.kt` | Tres imports sin uso y comentarios visuales redundantes eliminados. |
| `PlayerOptions.kt` | Tres imports sin uso, comentarios redundantes y emoji de estadísticas eliminados. |
| `Recommendations.kt` | Comentarios por pasos, dos KDoc descriptivos y emoji de tendencia eliminados. |
| `SessionStore.kt` | Imports ordenados y nombres locales aclarados; formato e intervalos conservados. |
| `SyncedLyrics.kt` | Cuatro KDoc descriptivos eliminados y nombres locales de respuesta y marcas de tiempo aclarados. |
| `SqlHistoryStore.kt` | Documentación redundante eliminada y descripción incorrecta de “no-op” corregida. |
| `MusicSource.kt` | Etiqueta SoundCloud sin pictograma. |
| `TrackDownloader.kt` | KDoc genérico eliminado; interfaz conservada. |
| `SearchScreen.kt` | Cuatro comentarios descriptivos eliminados; explicación del límite de scroll conservada. |
| `FileTrackDownloader.kt` | Nombres locales de archivos, MIME, formato y error aclarados; garantía de limpieza conservada. |
| `NewPipeGateway.kt` | Nombres locales de páginas y lotes aclarados y llamada segura innecesaria retirada. |
| `PlayerFeaturesTest.kt` | Comparación explícita 5.3.0/5.3.1, `assertNotNull` y comentarios redundantes retirados. |
| `PlaybackServiceTest.kt` | Fixture aislada, checkpoint durante reproducción y coincidencia exacta al pausar. |
| `AndroidHlsTest.kt` | Prueba optativa de reproducción HLS de audio real. |
| `BundledResourcesTest.kt` | Regresión de disponibilidad del fondo en el classpath. |

También se quitó la copia idéntica del JPEG, se excluyó `.qa/`, se actualizó el README y su captura real de Android y se preparó la descripción en español para GitHub.

No se encontraron archivos de agentes versionados ni trailers `Co-authored-by` o `Generated with` en el historial local examinado. La ausencia de esos patrones no demuestra cómo se escribió el código.

## Diferencias y límites pendientes

- La primera muestra HLS, de Mux, incluía video y falló en el decodificador H.264 del emulador (`c2.goldfish.h264.decoder`, `ERROR_CODE_DECODING_FAILED`). Se conservó el motor y se verificó el caso de audio con una muestra AAC de Apple. La reproducción HLS con video no quedó validada.
- El límite de dos canciones consecutivas del mismo artista no es estricto: se calcula por lote, sin incluir el final de la cola existente, y permite excederlo cuando no queda otro artista. No se cambió ese algoritmo durante la limpieza.
- La persistencia de posición usa un umbral monotónico de cinco segundos, emisiones del motor y una espera mínima entre escrituras. No es un temporizador que garantice escrituras exactamente cada cinco segundos.
- La compatibilidad con libmpv anterior a 0.38 está respaldada por el código de carga; no se ejecutó una biblioteca antigua ni Linux real. La prueba nativa ejecutada usa la biblioteca de Windows disponible.
- El APK público 5.3.1 tiene certificado Android Debug. Se comprobó con `apksigner verify --print-certs`. La firma estable de distribución sigue pendiente.
- No se probaron Android físico/API 26, Android Auto ni CarPlay. Doble toque, crossfade/gapless y edición de playlists guardadas siguen fuera de estos cambios.
- Los cambios de esta revisión se registraron en la rama `release/5.3.3-limpieza`. La publicación de instaladores firmados queda pendiente.

La presentación en español para GitHub está en [GITHUB.md](GITHUB.md). El README mantiene enlaces a la versión pública 5.3.1 y distingue la revisión local de una publicación nueva.
