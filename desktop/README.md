# Mochy Desktop (Linux)

Port de escritorio basado en [Mochy Music](https://github.com/realidkroo/mochy-music) de realidkroo
(que a su vez es fork de ViviMusic, construido sobre Metrolist). Usa el mismo código de
`providers/innertube` que la app Android, compilado para la JVM, y reproduce el audio con `mpv`.

## Requisitos

- `mpv`: `sudo dnf install mpv`
- Recomendado: `yt-dlp` (`sudo dnf install yt-dlp`), el último respaldo para canciones que ningún cliente sirve; con la sesión iniciada recibe tus cookies, así que también suenan las que piden cuenta (por edad). `mpv-mpris` no hace
  falta: Mochy publica sus propios controles multimedia (panel de GNOME, pantalla de bloqueo, teclas
  multimedia) con `src/main/resources/mpris/mochy-mpris.py`, que usa el Python y GLib que trae Fedora.
- Java 21 solo para compilar; la app instalada trae su propio Java.

## Usar

```
./gradlew -p desktop run         # probar sin instalar
desktop/install.sh               # instalar en ~/Applications y en el menú de GNOME
./gradlew -p desktop test        # búsqueda, audio, letras y, si hay mpv, reproducción real en silencio
```

## Qué hay y qué falta

Hay, con el aspecto de la app Android (fondo negro, fuente Inter, iconos, textos y traducciones de
la propia app):

- Inicio con portada grande, chips de ánimo y secciones que cargan más al bajar.
- Buscar con sugerencias, búsquedas recientes, filtros (canciones, vídeos, álbumes, artistas,
  playlists) y álbumes recién lanzados.
- Páginas de artista, álbum y playlist (con carga por partes en playlists largas).
- Barra de navegación y mini reproductor flotantes, con los botones animados del reproductor
  "flagship".
- Reproductor a pantalla completa: carátula, etiqueta de formato, progreso, volumen, aleatorio,
  repetir, letra sincronizada (LrcLib, luego YouTube Music) y cola editable.
- Radio automática al reproducir una canción suelta, como la app.
- Biblioteca local: canciones que te gustan, historial, álbumes, artistas y playlists guardados,
  en `~/.local/share/mochy-desktop/library.json`.
- Ajustes: calidad de audio, borrar historiales y créditos.
- Clic derecho en canciones, atajos (Espacio, Esc, Alt+←, Ctrl+←/→) y botón "atrás" del ratón.

- Cuenta de YouTube Music (Ajustes): toma la sesión del navegador donde ya la iniciaste (Firefox, Zen,
  LibreWolf, Floorp, Brave, Chrome, Chromium, Vivaldi, también en Flatpak; las de Chromium se descifran
  con la clave del llavero de GNOME vía `secret-tool`) o una cookie pegada. Se guarda en
  `~/.local/share/mochy-desktop/account.json` con permisos 600. Google no deja iniciar sesión dentro de
  navegadores incrustados, por eso no hay ventana de login propia como la WebView de Android.

Falta respecto a la app Android: sincronizar biblioteca y "me gusta" con YouTube Music, importar de
Spotify/Apple Music, descargas, ecualizador y efectos, estilos alternativos del reproductor (iPod,
tocadiscos), Discord, y los clientes de respaldo de `YTPlayerUtils` para canciones que el cliente
principal rechaza.
