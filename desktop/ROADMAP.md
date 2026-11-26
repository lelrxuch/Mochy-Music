# Mochy Desktop: hoja de ruta

Meta: que la versión de PC tenga todo lo de la app Android (`app/`, ~274 000 líneas) salvo lo que
solo tiene sentido en un teléfono, con la interfaz en "liquid glass" y movimiento con resortes.
Cada fase reutiliza el código Android cuando es Kotlin puro y lo reescribe cuando depende de Android.

## Hecho
- Inicio, búsqueda, artista, álbum, playlist, reproductor flagship básico, letras sincronizadas, cola,
  radio automática, biblioteca local, ajustes, atajos.
- Cuenta de YouTube Music desde el navegador (y refresco cuando Google rota la sesión).
- Biblioteca sincronizada: me gusta, álbumes, artistas, playlists, con cambios pendientes.
- Una sola instancia; controles multimedia de GNOME y teclas multimedia (MPRIS propio).
- CI en GitHub Actions (`.github/workflows/desktop.yml`): compila, prueba y saca capturas a la rama `desktop-ci`.

## Fase 1: liquid glass y movimiento (base de todo lo demás) — casi completa
Hecho: vidrio líquido real (Glass.kt) en barra, mini reproductor, avisos y botón atrás; píldora de la
barra con resorte; presión 3D; aparición escalonada; fondo ambiental del reproductor; la carátula
viaja entre mini reproductor y reproductor; letras con brillo y onda; explosión de me gusta.
Pendiente: menús y diálogos en vidrio (los menús se abren en ventanas aparte, sin fondo que leer),
título y artista que viajan con la carátula.

Android: `ui/component/GlassKit.kt`, `FrostedGlass.kt`, `ProgressiveBlur.kt`, `AgslShader.kt`,
`Press3D.kt`, `ContainerTransform.kt`, `RevealOnAppear.kt`, `PageWipe.kt`, `ui/player/flagship/`.
- Vidrio real: el fondo de la app capturado (`GraphicsLayer`) y pasado por un shader SkSL de Skia:
  desenfoque, refracción en los bordes, brillo especular, aberración cromática leve y grano.
  Mismos valores que el Figma de Android (#131313 al 78 %, línea de 2 px al 5 %).
- En la barra, el mini reproductor, los chips, los menús, los diálogos y las hojas.
- Movimiento: resortes en todo; el mini reproductor se transforma en el reproductor; presión 3D;
  explosión al dar me gusta; aparición escalonada de listas; transiciones de página; contador
  tipo odómetro; indicador de reproducción animado.
- Reproductor flagship completo: fondo ambiental con los colores de la carátula, carátula
  desenfocada, reflejo, letras con brillo y escalonado, puntos de interludio.

## Fase 2: biblioteca, playlists y menús — hecha
Playlists de tu cuenta editables y locales, añadir a playlist (canciones, álbumes y playlists enteras),
reordenar arrastrando, selección múltiple, menús completos, diálogos en vidrio, Explorar e historial de
tu cuenta.

## Fase 3: reproducción — en curso
Hecho: clientes de respaldo (VISIONOS, TV y WEB_REMIX con NewPipe, extractor de NewPipe, yt-dlp con
tus cookies), temporizador de sueño, la cola sobrevive al cierre, escuchas al historial de YouTube,
descargas para escuchar sin conexión, normalización de volumen.
Pendiente: crossmix (fundidos entre canciones, con dos mpv).
Android: `playback/`, `utils/YTPlayerUtils`, `crossmix/`.
- Clientes de respaldo para canciones que YouTube rechaza.
- Temporizador de sueño, cola que sobrevive al cierre, reporte de escuchas al historial de YouTube.
- Caché y descargas para escuchar sin conexión.
- Crossmix: fundidos y transiciones entre canciones (dos instancias de mpv); normalización.

## Fase 4: Audio Lab — primera versión hecha
Hecho: ecualizador de 10 bandas con los 20 presets de la app, reverberación, 8D, crossfeed, mono, velocidad.
Hecho: presets propios del ecualizador (PR #1, escrito por GPT Astra y revisado).
Pendiente: presets de efectos (reverberación, 8D, velocidad...), corrección de auriculares (AutoEq), compresor de volumen.
Android: `eq/`, `ui/screens/audiolab/`, `ui/screens/equalizer/`.
- Ecualizador con presets, reverberación, audio 8D y demás efectos, con los filtros de mpv (lavfi).

## Fase 5: letras
Android: `lyrics/`.
- Más proveedores (BetterLyrics, KuGou, NetEase, QQ Music, Deezer, SimpMusic, YouLyPlus…),
  orden configurable, palabra por palabra, traducción, subtítulos de YouTube.

## Fase 6: integraciones
Android: `scrobble/`, `discord/`, `importer/`, `backup/`.
- Last.fm, estado de Discord (en PC es más fácil: socket local de Discord),
  importar de Spotify, Apple Music y Tidal, copia de seguridad y restauración.

## Fase 7: estilos y extras
Android: `ui/player/styles/`, `ipod/`, `studio/`, `bedside/`, `modules/`, `ui/screens/recap/`.
- Estilos de reproductor (iPod, tocadiscos, studio, mesita de noche), módulos y skins,
  resumen y estadísticas, portadas de playlist, editar canción, fondo de video,
  asistente de bienvenida, perfil.
- Equivalente de PC de la ventana flotante: mini reproductor siempre encima.

## Base: que aguante crecer
Hecho: la biblioteca en SQLite (`LibraryDb.kt`): cada cambio escribe solo sus filas, en un hilo aparte.
Hecho: paquetes por función. El núcleo (`text/`, `playback/`, `library/`, `account/`) no dibuja nada;
`ui/` y `ui/screens/` lo muestran; `Main`, `Nav`, `AppState` y `Mpris` los conectan. `ArchitectureCheck`
falla la compilación si el núcleo importa la interfaz.
`AppState` se queda como está: ya es solo el punto donde se juntan las piezas, cada una con su estado.
Hecho: caché de páginas con límite (60) y búsqueda filtrada que carga más al bajar.
Pendiente:
- Pruebas de interacción de pantallas (clic → resultado), no solo capturas.

## Fuera de alcance (solo tienen sentido en Android)
Android Auto (`car/`), widgets, Android TV (`ui/tv/`), gestos de pantalla, aviso de llamadas,
voz del sistema, actualizador de la tienda.
Por decidir: control remoto entre dispositivos (`link/`, `devices/`), reconocer canciones (micrófono), cast.
