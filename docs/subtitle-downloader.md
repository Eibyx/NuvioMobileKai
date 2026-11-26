# Subtitle Downloader

Extrae pistas ASS/SSA originales y las fuentes incrustadas de MKV remotos sin abrir ExoPlayer ni mpv y sin descargar el archivo completo.

## Cómo se usa

1. En el selector de enlaces, **mantén pulsado un enlace → «Subtítulos y fuentes»**.
2. Nuvio resuelve el enlace (nube TeraBox, debrid, servidor o HTTP) y lee solo las cabeceras: SeekHead, Info, Tracks, CodecPrivate, Attachments, Cues y Tags.
3. Elige:
   - pistas y fuentes;
   - modo (Automático, Ahorro máximo, Máxima velocidad);
   - presupuesto (5–75 % del MKV);
   - destino.
4. Pulsa uno de los botones:
   - **Descargar selección**
   - **Todas las pistas**
   - **Solo fuentes**
   - **Subtítulos y fuentes**
   - **Temporada completa…**
5. **Cola e historial** permite pausar, reanudar, cancelar, reintentar, ampliar el presupuesto, quitar tareas, y cambiar los límites de caché y los episodios a la vez.
6. **Diagnóstico de red** mide el enlace (lee como máximo 48 MB) y exporta el informe en TXT o JSON.

## Dónde se guarda

Por defecto se guarda en `Download/Nuvio/Subtitles/` mediante MediaStore. También puedes elegir otra carpeta con el selector de carpetas de Android (SAF).

```
Serie/
  Temporada 01/
    Episodio 01/
      English - Full.ass
      Español - Latino.ass
    Fonts/
```

- Las fuentes se guardan una sola vez por temporada.
- Una fuente se considera repetida solo si su SHA-256 coincide; el nombre, el tamaño y el FileUID no bastan.
- Si dos fuentes distintas se llaman igual, la segunda se guarda con un número, por ejemplo `Nombre (2).ttf`.

## Cómo funciona

- **Detección:** lee el principio y el final del archivo en paralelo, sigue el SeekHead y, si el bloque de adjuntos es pequeño (≤ 64 MB y ≤ 10 % del MKV), lo descarga en paralelo ya en este paso.
- **Indexado (Cues):** pide directamente los bloques que indica `CueRelativePosition`.
  - La marca de tiempo sale de `CueTime`.
  - Se comprueba en una muestra de Clusters (1 de cada 16). Si no coincide, se leen todas las cabeceras de Cluster.
  - El planificador prueba varias agrupaciones de rangos y elige según el modo, sin pasar del presupuesto.
- **Disperso (sin Cues de subtítulos):** recorre cada Cluster por sus cabeceras, varios Clusters en paralelo.
  - Salta el audio y el vídeo; cuánto se lee de corrido depende de la latencia y el ancho de banda medidos, y se reajusta en marcha según el tiempo y el tráfico previstos.
  - Admite Clusters de tamaño desconocido, Clusters que no aparecen en ningún Cue, CRC-32 y Void.
- **Híbrido:** usa el índice donde existe y recorre los Clusters cuando el índice no basta para verificar.
- **Bloques:** zlib, header stripping y los tres tipos de lacing (Xiph, EBML y fijo).
  - Un evento sin `BlockDuration` termina donde empieza el siguiente, como define Matroska, y se indica en el resultado.
  - Las pistas con bzlib, lzo o cifrado se marcan como no soportadas.
- **ASS reconstruido:** CodecPrivate intacto y cada evento byte a byte (estilos, etiquetas, karaoke, dibujos, Unicode) en orden de ReadOrder. Solo Start y End se calculan desde las marcas de tiempo del MKV.
- **Integridad:**
  - «verificada» si el número de eventos coincide con las estadísticas del archivo o si se recorrieron todos los Clusters;
  - «coherente con el índice» si no hay estadísticas;
  - «incompleta» en cualquier otro caso. Nunca se marca como completa sin poder verificarla.
- **Red:**
  - OkHttp directo al CDN, sin el servidor local, y se reutiliza la URL final de la redirección;
  - conexiones reutilizadas y HTTP/2 si el servidor lo ofrece;
  - enlace renovado ante 401/403/404/410, comprobando que el archivo sigue siendo el mismo;
  - reintentos con espera creciente ante 503/504/429;
  - conexiones ajustadas en marcha (máximo 8; 2 mientras el reproductor está abierto);
  - una respuesta 200 con el archivo entero se corta antes de leerla;
  - todo el tráfico recibido se cuenta contra el presupuesto.
- **Caché:** dispersa, separada por versión del archivo (id + tamaño + huella de los primeros 64 KB), en RAM y en disco con límites configurables. Reanudar o ampliar el presupuesto no vuelve a descargar lo que ya se leyó.

## Benchmark simulado (no es una medición real)

Condiciones del servidor de pruebas:
- MKV sintético de 323 MiB (24 min, vídeo a 1,87 Mbps), con dos pistas ASS (16.609 eventos) y 19 fuentes (11 MiB);
- 448 ms por petición y 2,71 MiB/s compartidos;
- máximo 8 peticiones a la vez (503 al pasarse).

Todas las pruebas reconstruyeron el ASS idéntico byte a byte y quedaron verificadas con las estadísticas del archivo.

| Caso | Tiempo | Peticiones | Tráfico |
|---|---|---|---|
| Detección (incluye las 19 fuentes) | 5,5 s | 6 | 11 MiB |
| Solo fuentes, después de la detección | < 0,1 s | 0 extra | 0 extra |
| Cues, máxima velocidad (presupuesto 25 %) | 85 s | 1.384 | 85 MiB (26 %)¹ |
| Cues, automático (antes del último ajuste) | 131 s | 2.281 | 29 MiB (9 %) |
| Cues, ahorro máximo | 186 s | 3.261 | 17 MiB (5 %) |

¹ El tráfico de la tabla incluye los 11 MiB de la detección; el presupuesto del 25 % se aplica solo a la extracción.

- Después de esa prueba, el modo automático se cambió para priorizar la velocidad: elige el plan de menor tráfico entre los que tardan como mucho un 15 % más que el más rápido. Ese ajuste todavía no se ha medido.
- El caso sin Cues de subtítulos se verificó en tests más pequeños, pero no tiene benchmark a escala completa.

## Qué impide bajar de 60–120 s

- **Latencia por petición:** con 448 ms y 8 conexiones caben unas 1.070 peticiones en 60 s.
- **Subtítulos densos y repartidos por todo el archivo:** obligan a elegir entre muchas peticiones (más tiempo) y rangos más grandes (más tráfico).
- **Sin Cues de subtítulos:** el recorrido dentro de cada Cluster va en serie, así que tarda mucho más o descarga mucho más.
- **Presupuesto bajo:** limita cuánto se puede agrupar.

El diagnóstico integrado mide estos valores en tu red real.

## Pruebas automatizadas

- `SubtitleEngineTest` (12 tests): Cues, sin Cues, sin índice ni estadísticas, Clusters de tamaño desconocido con CRC, Cues sin posiciones, zlib + lacing, bloques sin duración, 503/504/enlaces caducados con límite de conexiones, servidor sin Range, presupuesto, reconstrucción de tiempos y los tres tipos de lacing.
- `SubtitleEngineBenchmark`: se ejecuta con `NUVIO_SUBDL_BENCH=1`.

## Limitaciones

- Los enlaces torrent sin debrid no se pueden leer por rangos.
- En una temporada, cada episodio busca el mismo release (mismo addon y rasgos del nombre); si no lo encuentra, falla con un aviso.
- Si el proceso muere, la tarea vuelve a empezar desde la caché, no desde el evento exacto donde quedó.
- No hay soporte para iOS.
