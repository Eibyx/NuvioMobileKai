<div align="center">

  <img src="https://nuvio.tv/assets/nuvio-app-logo-wordmark.webp" alt="Nuvio" width="320" />

  <h1>Nuvio Kai</h1>

  <p>
    Fork no oficial de <a href="https://github.com/NuvioMedia/NuvioMobile">Nuvio Mobile</a>, construido sobre
    <a href="https://github.com/luqmanfadlli/NuvioMobile-Enhanced">Nuvio Enhanced</a>.
    <br /><br />
    Trae tus propias fuentes. Nuvio las convierte en una biblioteca con carátulas, valoraciones, subtítulos y tu progreso guardado en cada pantalla.
  </p>

  <p>
    <a href="https://github.com/Eibyx/NuvioMobileKai/releases/latest">Releases</a> ·
    <a href="https://github.com/NuvioMedia/NuvioMobile">Proyecto original</a> ·
    <a href="https://github.com/luqmanfadlli/NuvioMobile-Enhanced">Nuvio Enhanced</a> ·
    <a href="https://nuvio.tv/support">Apoya a Nuvio</a>
  </p>

</div>

> **No oficial.** Este fork no está afiliado ni soportado por el equipo de Nuvio ni por el autor de Nuvio Enhanced.
> Reporta aquí los fallos que encuentres, no en los repositorios originales, salvo que también ocurran en sus builds.

---

## Qué es

Nuvio Kai parte de la rama `cmp-rewrite` de NuvioMedia/NuvioMobile e incorpora todas las funciones de
[Nuvio Enhanced](https://github.com/luqmanfadlli/NuvioMobile-Enhanced) (Live TV, mejoras del reproductor, Liquid Glass,
AniList, WeTrakr, Discord Rich Presence, fuentes de subtítulos personalizadas y más). La lista completa de lo que añade
Enhanced está en [su README](https://github.com/luqmanfadlli/NuvioMobile-Enhanced#what-enhanced-adds).

Además:

- Se instala aparte (`com.eibyx.nuviokai`), así que convive con la app oficial y con Nuvio Enhanced.
- El actualizador integrado busca nuevas versiones en los releases de este repositorio.

## Instalar

### Android

- **Versión publicada:** descarga el APK del [último release](https://github.com/Eibyx/NuvioMobileKai/releases/latest).
- **Build de desarrollo:** cada push a la rama `kai` compila un APK de depuración. Lo encuentras en la pestaña
  [Actions](https://github.com/Eibyx/NuvioMobileKai/actions/workflows/kai-debug-build.yml), dentro de la ejecución, en
  **Artifacts**. Se instala como `com.eibyx.nuviokai.debug`.

### iOS

Todavía no hay builds de iOS de Nuvio Kai.

## Compilar

```bash
git clone https://github.com/Eibyx/NuvioMobileKai.git
cd NuvioMobileKai
git checkout kai
```

### Android

Requiere JDK 21 y el Android SDK.

```bash
./gradlew :androidApp:assembleFullDebug        # sabor sideload
./gradlew :androidApp:assemblePlaystoreDebug   # sabor tienda
```

### iOS

Requiere macOS y Xcode.

```bash
env NUVIO_IOS_DISTRIBUTION=full xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  -derivedDataPath build/ios-derived-full-simulator \
  CODE_SIGNING_ALLOWED=NO \
  build
```

## Mantenerse al día

La rama `kai` sigue tanto al proyecto original como a Nuvio Enhanced:

```bash
git remote add upstream https://github.com/NuvioMedia/NuvioMobile.git
git remote add luqman https://github.com/luqmanfadlli/NuvioMobile-Enhanced.git
git fetch upstream cmp-rewrite
git fetch luqman enhanced
git merge upstream/cmp-rewrite
git merge luqman/enhanced
```

---

## Créditos

- **[NuvioMedia](https://github.com/NuvioMedia)** y los colaboradores de
  [Nuvio Mobile](https://github.com/NuvioMedia/NuvioMobile) han creado la app. Todo el mérito de Nuvio es suyo. Si te
  gusta Nuvio, [apoya el proyecto original](https://nuvio.tv/support).
- **[luqmanfadlli](https://github.com/luqmanfadlli)** y los colaboradores de
  [Nuvio Enhanced](https://github.com/luqmanfadlli/NuvioMobile-Enhanced) han creado las funciones extra en las que se
  basa este fork.

Nuvio Kai solo suma cambios encima de su trabajo.

## Licencia

[GNU General Public License v3.0](./LICENSE), igual que el proyecto original.
